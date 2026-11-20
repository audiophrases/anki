"""Plays an audio script.

Every speech clip is synthesized (Edge, cached; Windows voice when Edge is
unreachable), decoded to 24 kHz mono PCM with Qt, trimmed of its outer silence
and joined with the recorded blanks into ONE buffer per card side. This matches
the Android ``SentencePcm`` assembly (20 ms kept at each trimmed edge, 3 ms
fades, 30 ms joins, 225 ms after each blank), and playing one buffer means no
gaps from player setup or synthesis between lines.
"""

from __future__ import annotations

import logging
import math
import operator
import os
import threading
import time
import traceback
from array import array
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Dict, List, Optional, Sequence, Tuple

from PyQt6 import QtMultimedia
from PyQt6.QtCore import QBuffer, QByteArray, QIODevice, QObject, QTimer, QUrl, pyqtSignal, pyqtSlot
from PyQt6.QtMultimedia import QAudioDecoder, QAudioFormat, QAudioSink, QMediaDevices

from . import tts
from .audio_script import Blank, Pause, Segment, Speech

RATE = 24_000
AFTER_BLANK_MS = 225
JOIN_MS = 30
TRIM_PAD_MS = 20
FADE_MS = 3

#: Pauses between retries when Edge is busy (429) for the card being read.
EDGE_RETRY_DELAYS_S = (1.0, 2.0, 3.0)
#: After Edge was busy, prefetching (not reading) holds off this long.
EDGE_BUSY_S = 30
#: After Edge was out of reach (no network, hard refusal), read with the
#: Windows voice without first waiting on Edge, for this long.
EDGE_OFFLINE_S = 30

log = logging.getLogger("ankiaudio")

_State = getattr(QtMultimedia, "QtAudio", None) or QtMultimedia.QAudio


def _silence(ms: float) -> array:
    return array("h", bytes(2 * int(RATE * ms / 1000)))


def trim(samples: array) -> array:
    """Trim only outer silence, keeping quiet consonants and inner pauses."""
    if not samples:
        return samples
    hop = RATE // 200  # 5 ms windows
    rms = []
    for start in range(0, len(samples), hop):
        window = samples[start : start + hop]
        rms.append(math.sqrt(sum(map(operator.mul, window, window)) / len(window)))
    threshold = max(32768 * 10 ** (-45 / 20), max(rms) * 10 ** (-35 / 20))
    loud = [i for i, r in enumerate(rms) if r > threshold]
    if not loud:
        return array("h")
    pad = RATE * TRIM_PAD_MS // 1000
    return samples[max(0, loud[0] * hop - pad) : min(len(samples), (loud[-1] + 1) * hop + pad)]


def fade(samples: array) -> array:
    out = array("h", samples)
    n = min(RATE * FADE_MS // 1000, len(out) // 2)
    for i in range(n):
        out[i] = round(out[i] * i / n)
        out[-1 - i] = round(out[-1 - i] * i / n)
    return out


class _Poster(QObject):
    """Runs callables on the thread that created it (the GUI thread)."""

    call = pyqtSignal(object)

    def __init__(self) -> None:
        super().__init__()
        self.call.connect(self._run)

    @pyqtSlot(object)
    def _run(self, fn: Callable[[], None]) -> None:
        fn()


class Speaker:
    def __init__(
        self,
        cache_dir: str,
        blank_pcm_path: str,
        settings: Callable[[], Dict],
        on_speaking: Callable[[bool], None] = lambda on: None,
        on_notice: Callable[[str], None] = lambda msg: None,
    ) -> None:
        self.cache = tts.Cache(cache_dir)
        self.settings = settings
        self.on_speaking = on_speaking
        self.on_notice = on_notice
        with open(blank_pcm_path, "rb") as f:
            self.blank = array("h", f.read())
        self.poster = _Poster()
        self.pool = ThreadPoolExecutor(max_workers=tts.MAX_CONNECTIONS, thread_name_prefix="ankiaudio")
        self.prefetch_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="ankiaudio-prefetch")
        self.generation = 0
        self.sink: Optional[QAudioSink] = None
        self.buffer: Optional[QBuffer] = None
        self.decoders: set = set()
        self.decoded: "OrderedDict[str, array]" = OrderedDict()
        self.assembled: "OrderedDict[tuple, bytes]" = OrderedDict()
        self.path_locks: Dict[str, threading.Lock] = {}
        self.locks_guard = threading.Lock()
        self.offline_until = 0.0
        self.busy_until = 0.0
        self.last_notice = 0.0
        self.speaking = False
        threading.Thread(target=self.cache.trim, daemon=True).start()

    # ------------------------------------------------------------------ API

    def speak(self, segments: Sequence[Segment]) -> None:
        """Starts playing ``segments``, replacing whatever was playing."""
        self.stop()
        segments = list(segments)
        while segments and isinstance(segments[-1], Pause):
            segments.pop()
        if not any(isinstance(s, (Speech, Blank)) for s in segments):
            return
        gen = self.generation
        voice, speed = self._voice()
        key = (voice, speed, self.settings().get("lead_in_ms", 150), tuple(segments))
        cached = self.assembled.get(key)
        if cached is not None:
            self.assembled.move_to_end(key)
            self._play(cached, gen)
            return
        self._set_speaking(True)
        texts = list(dict.fromkeys(s.text for s in segments if isinstance(s, Speech)))
        if not texts:
            self._assemble_and_play(segments, {}, key, gen)
            return
        files: Dict[str, Optional[str]] = {}

        def done(text: str, path: Optional[str]) -> None:
            if gen != self.generation:
                return
            files[text] = path
            if len(files) == len(texts):
                self._decode_all(segments, files, key, gen)

        for text in texts:
            self.pool.submit(self._synth_job, text, voice, speed, False, gen).add_done_callback(
                lambda fut, t=text: self.poster.call.emit(lambda: done(t, fut.result()))
            )

    def prefetch(self, segments: Sequence[Segment]) -> None:
        """Synthesizes the clips of a script that will probably play soon."""
        voice, speed = self._voice()
        for text in dict.fromkeys(s.text for s in segments if isinstance(s, Speech)):
            self.prefetch_pool.submit(self._synth_job, text, voice, speed, True)

    def stop(self) -> None:
        self.generation += 1
        sink = self.sink
        self.sink = self.buffer = None
        try:
            if sink is not None:
                # The buffer is the sink's child and goes with it; closing it
                # here could race the audio thread still pulling from it.
                sink.stop()
                sink.deleteLater()
        except RuntimeError:  # Qt already deleted it (shutdown)
            pass
        self._set_speaking(False)

    def shutdown(self) -> None:
        self.stop()
        self.pool.shutdown(wait=False, cancel_futures=True)
        self.prefetch_pool.shutdown(wait=False, cancel_futures=True)
        threading.Thread(target=tts.close_connections, daemon=True).start()

    def clear_cache(self) -> None:
        self.assembled.clear()
        self.decoded.clear()
        for name in os.listdir(self.cache.directory):
            try:
                os.remove(os.path.join(self.cache.directory, name))
            except OSError:
                pass

    # ------------------------------------------------------------ synthesis

    def _voice(self) -> Tuple[str, str]:
        s = self.settings()
        return s.get("voice") or tts.DEFAULT_VOICE, s.get("speed") or "+0%"

    def _lock_for(self, path: str) -> threading.Lock:
        with self.locks_guard:
            return self.path_locks.setdefault(path, threading.Lock())

    def _synth_job(
        self, text: str, voice: str, speed: str, prefetch: bool = False, gen: Optional[int] = None
    ) -> Optional[str]:
        """Worker thread: a playable file for ``text``, or None.

        The natural Edge voice is the point of the add-on, so a card being
        read waits out a busy Edge (429, timeouts) with a few short retries.
        The Windows voice is only for when Edge is really out of reach."""
        try:
            path = self.cache.path_for(voice, speed, text)
            with self._lock_for(path):
                found = self._edge(text, voice, speed, prefetch, gen)
            if found or prefetch or (gen is not None and gen != self.generation):
                return found
            fallback = self.cache.path_for("sapi", speed, text, ".wav")
            with self._lock_for(fallback):
                if not os.path.isfile(fallback):
                    tmp = fallback + ".tmp.wav"
                    tts.synthesize_windows(text, tmp, speed)
                    os.replace(tmp, fallback)
            log.warning("Windows voice used for %r", text[:60])
            self._notice("Edge's natural voice isn't reachable right now; using the Windows voice. P retries.")
            return fallback
        except Exception as e:
            log.exception("read aloud failed for %r", text[:60])
            self._notice(f"Read aloud failed: {e}")
            return None

    def _edge(self, text: str, voice: str, speed: str, prefetch: bool, gen: Optional[int]) -> Optional[str]:
        delays = () if prefetch else EDGE_RETRY_DELAYS_S
        for attempt in range(len(delays) + 1):
            now = time.monotonic()
            if now < self.offline_until or (prefetch and now < self.busy_until):
                return None
            try:
                return tts.synthesize_edge(self.cache, text, voice, speed)
            except tts.TtsUnavailable as e:
                log.warning("Edge attempt %d for %r: %s", attempt + 1, text[:60], e)
                if not e.transient:  # e.g. 403: retrying won't help
                    self.offline_until = time.monotonic() + EDGE_OFFLINE_S
                    return None
                self.busy_until = time.monotonic() + EDGE_BUSY_S  # prefetch backs off
            except OSError as e:  # no network: don't make every card wait
                log.warning("Edge unreachable for %r: %s", text[:60], e)
                self.offline_until = time.monotonic() + EDGE_OFFLINE_S
                return None
            except Exception as e:  # a problem with this text only
                log.warning("Edge failed for %r: %s", text[:60], e)
                return None
            if attempt < len(delays):
                time.sleep(delays[attempt])
                if gen is not None and gen != self.generation:
                    return None  # the learner moved on
        return None

    def _notice(self, message: str) -> None:
        now = time.monotonic()
        if now - self.last_notice < 600:
            return
        self.last_notice = now
        self.poster.call.emit(lambda: self.on_notice(message))

    # ------------------------------------------------------------- decoding

    def _decode_all(self, segments: List[Segment], files: Dict[str, Optional[str]], key: tuple, gen: int) -> None:
        pending = [p for p in dict.fromkeys(files.values()) if p and p not in self.decoded]
        if not pending:
            self._assemble_and_play(segments, files, key, gen)
            return
        remaining = [len(pending)]

        def one_done() -> None:
            remaining[0] -= 1
            if remaining[0] == 0 and gen == self.generation:
                self._assemble_and_play(segments, files, key, gen)

        for path in pending:
            self._decode(path, one_done)

    def _decode(self, path: str, done: Callable[[], None]) -> None:
        fmt = QAudioFormat()
        fmt.setSampleRate(RATE)
        fmt.setChannelCount(1)
        fmt.setSampleFormat(QAudioFormat.SampleFormat.Int16)
        decoder = QAudioDecoder()
        decoder.setAudioFormat(fmt)
        decoder.setSource(QUrl.fromLocalFile(path))
        samples = array("h")
        finished = [False]
        self.decoders.add(decoder)

        def ready() -> None:
            buf = decoder.read()
            if buf.isValid() and buf.byteCount():
                samples.frombytes(buf.constData().asstring(buf.byteCount()))

        def finish(ok: bool) -> None:
            if finished[0]:
                return
            finished[0] = True
            if ok and samples:
                self.decoded[path] = fade(trim(samples))
                while len(self.decoded) > 400:
                    self.decoded.popitem(last=False)
            elif not ok:
                print(f"ankiaudio: could not decode {path}: {decoder.errorString()}")
            self.decoders.discard(decoder)
            decoder.deleteLater()
            done()

        decoder.bufferReady.connect(ready)
        decoder.finished.connect(lambda: finish(True))
        decoder.error.connect(lambda _e: finish(False))
        QTimer.singleShot(15_000, lambda: finish(False))
        decoder.start()

    # ------------------------------------------------------------- assembly

    def _assemble_and_play(self, segments: List[Segment], files: Dict[str, Optional[str]], key: tuple, gen: int) -> None:
        if gen != self.generation:
            return
        pcm = _silence(self.settings().get("lead_in_ms", 150))
        spoke = False
        i = 0
        while i < len(segments):
            seg = segments[i]
            if isinstance(seg, Pause):
                pcm += _silence(seg.ms)
                i += 1
                continue
            # A line: speech clips and recorded blanks, joined closely.
            first = True
            while i < len(segments) and isinstance(segments[i], (Speech, Blank)):
                part = segments[i]
                if isinstance(part, Blank):
                    clip, after = self.blank, AFTER_BLANK_MS
                else:
                    path = files.get(part.text)
                    clip, after = (self.decoded.get(path) if path else None), 0
                if clip:
                    if not first:
                        pcm += _silence(JOIN_MS)
                    pcm += clip
                    pcm += _silence(after)
                    first = False
                    spoke = spoke or isinstance(part, Speech)
                i += 1
        if not spoke:
            self._set_speaking(False)
            return
        data = pcm.tobytes()
        # A reading with Windows-voice stand-ins isn't kept: P should try Edge again.
        if not any(p and p.endswith(".wav") for p in files.values()):
            self.assembled[key] = data
        while len(self.assembled) > 30:
            self.assembled.popitem(last=False)
        self._play(data, gen)

    # ------------------------------------------------------------- playback

    def _play(self, data: bytes, gen: int) -> None:
        if gen != self.generation:
            return
        fmt = QAudioFormat()
        fmt.setSampleRate(RATE)
        fmt.setChannelCount(1)
        fmt.setSampleFormat(QAudioFormat.SampleFormat.Int16)
        sink = QAudioSink(QMediaDevices.defaultAudioOutput(), fmt)
        sink.setVolume(float(self.settings().get("volume", 1.0)))
        buffer = QBuffer(sink)
        buffer.setData(QByteArray(data))
        buffer.open(QIODevice.OpenModeFlag.ReadOnly)
        self.sink, self.buffer = sink, buffer
        sink.stateChanged.connect(lambda state, s=sink: self._on_sink_state(s, state))
        self._set_speaking(True)
        sink.start(buffer)

    def _on_sink_state(self, sink: QAudioSink, state) -> None:
        if sink is not self.sink:
            return
        try:
            failed = state == _State.State.StoppedState and sink.error() != _State.Error.NoError
        except RuntimeError:  # Qt already deleted it (shutdown)
            failed = True
        if state == _State.State.IdleState or failed:
            self.stop()

    def _set_speaking(self, on: bool) -> None:
        if on != self.speaking:
            self.speaking = on
            try:
                self.on_speaking(on)
            except Exception:
                traceback.print_exc()
