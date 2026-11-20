"""Speech synthesis: Edge neural voices with an on-disk cache, plus an offline
Windows (SAPI) fallback.

Edge "Read Aloud" is unofficial but long-stable; see the Android ``EdgeTts.kt``
for the protocol notes. Anki bundles no websocket package, so a minimal
RFC 6455 client over ``ssl`` is implemented here. Everything in this module is
blocking and meant to run on worker threads.
"""

from __future__ import annotations

import base64
import hashlib
import os
import queue
import socket
import ssl
import struct
import threading
import time
import uuid
from typing import Optional, Tuple

from . import edge_token

DEFAULT_VOICE = "en-US-AndrewMultilingualNeural"

# Must track current edge-tts values or the server answers 403.
CHROMIUM_FULL_VERSION = "143.0.3650.75"
CHROMIUM_MAJOR = CHROMIUM_FULL_VERSION.split(".")[0]
OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

# Edge moved Read Aloud to a new host in 2026-10 (rany2/edge-tts#490); it needs
# the v2 token (edge-tts PR #492).
HOST = "api.msedgeservices.com"
PATH = "/tts/cognitiveservices/websocket/v1"
ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    f"(KHTML, like Gecko) Chrome/{CHROMIUM_MAJOR}.0.0.0 Safari/537.36 "
    f"Edg/{CHROMIUM_MAJOR}.0.0.0"
)

CONNECT_TIMEOUT_S = 6
TOTAL_TIMEOUT_S = 15

#: Bumped when the cached audio format changes, so old clips regenerate.
CACHE_VERSION = "d1"


class TtsError(Exception):
    pass


class TtsUnavailable(TtsError):
    """The service can't be reached or refused us; retrying soon is pointless.
    ``retry_s`` is how long to leave it alone."""

    def __init__(self, message: str, retry_s: int = 60) -> None:
        super().__init__(message)
        self.retry_s = retry_s


class _ConnectionLost(TtsError):
    """A reused connection died; the request is worth one retry on a new one."""


#: Edge refuses new connections in bursts (HTTP 429 at the handshake), so a
#: few connections are kept open and reused for many requests, the way Edge's
#: own Read Aloud does.
MAX_CONNECTIONS = 2

#: A connection idle this long is replaced rather than trusted.
IDLE_S = 45


# ---------------------------------------------------------------------------
# Cache


class Cache:
    """MP3s keyed by (voice, speed, text). Trimmed by size, least recently used first."""

    def __init__(self, directory: str, max_mb: int = 300, target_mb: int = 200) -> None:
        self.directory = directory
        self.max_bytes = max_mb * 1024 * 1024
        self.target_bytes = target_mb * 1024 * 1024
        os.makedirs(directory, exist_ok=True)

    def path_for(self, voice: str, speed: str, text: str, ext: str = ".mp3") -> str:
        key = hashlib.sha256(f"{CACHE_VERSION}|{voice}|{speed}|{text}".encode("utf-8")).hexdigest()
        return os.path.join(self.directory, key + ext)

    def trim(self) -> None:
        try:
            entries = []
            total = 0
            for name in os.listdir(self.directory):
                path = os.path.join(self.directory, name)
                if not os.path.isfile(path):
                    continue
                st = os.stat(path)
                if name.endswith(".tmp") and time.time() - st.st_mtime > 3600:
                    os.remove(path)
                    continue
                entries.append((st.st_mtime, st.st_size, path))
                total += st.st_size
            if total <= self.max_bytes:
                return
            for _mtime, size, path in sorted(entries):
                if total <= self.target_bytes:
                    break
                try:
                    os.remove(path)
                    total -= size
                except OSError:
                    pass
        except OSError:
            pass


# ---------------------------------------------------------------------------
# Edge


def ssml_body(text: str, speed: str) -> str:
    """The <voice> body. Edge only accepts a whole-utterance prosody element
    with all three attributes, exactly as edge-tts sends it."""
    escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if speed in ("", "+0%"):
        return escaped
    return f"<prosody pitch='+0Hz' rate='{speed}' volume='+0%'>{escaped}</prosody>"


def synthesize_edge(cache: Cache, text: str, voice: str, speed: str) -> str:
    """Returns the path of a cached or freshly synthesized MP3. Raises TtsError."""
    path = cache.path_for(voice, speed, text)
    if os.path.isfile(path) and os.path.getsize(path) > 0:
        try:
            os.utime(path)  # refresh last-used time for LRU trimming
        except OSError:
            pass
        return path
    session = _sessions.get()
    try:
        audio = session.synthesize(ssml_body(text, speed), voice)
    finally:
        _sessions.put(session)
    # Temp file + atomic rename: a crash can never leave a half-written clip
    # that a later run would mistake for a cache hit.
    tmp = f"{path}.{uuid.uuid4().hex}.tmp"
    with open(tmp, "wb") as f:
        f.write(audio)
    os.replace(tmp, path)
    return path


def close_connections() -> None:
    for _ in range(MAX_CONNECTIONS):
        session = _sessions.get()
        session.close()
        _sessions.put(session)


def _timestamp() -> str:
    return time.strftime("%a %b %d %Y %H:%M:%S GMT+0000 (Coordinated Universal Time)", time.gmtime())


class _Session:
    """One Edge websocket, reused for request after request."""

    def __init__(self) -> None:
        self.ws: Optional["_WebSocket"] = None
        self.last_used = 0.0

    def synthesize(self, body: str, voice: str) -> bytes:
        reused = self.ws is not None and time.monotonic() - self.last_used < IDLE_S
        attempts = 2 if reused else 1  # a reused connection may have been dropped
        for attempt in range(attempts):
            try:
                if self.ws is None or attempt > 0 or not reused:
                    self.close()
                    self._connect()
                audio = self._request(body, voice)
                self.last_used = time.monotonic()
                return audio
            except _ConnectionLost as e:
                self.close()
                if attempt == attempts - 1:
                    raise TtsUnavailable(str(e)) from e
            except BaseException:
                self.close()
                raise
        raise TtsUnavailable("unreachable")  # not reached

    def _connect(self) -> None:
        query = (
            f"?Ocp-Apim-Subscription-Key={edge_token.TRUSTED_CLIENT_TOKEN}"
            f"&Sec-MS-GEC={edge_token.generate()}"
            f"&Sec-MS-GEC-Version=2-{CHROMIUM_FULL_VERSION}"
            f"&ConnectionId={uuid.uuid4().hex}"
        )
        self.ws = _WebSocket(HOST, PATH + query, time.monotonic() + TOTAL_TIMEOUT_S)
        self.ws.send_text(
            f"X-Timestamp:{_timestamp()}\r\n"
            "Content-Type:application/json; charset=utf-8\r\n"
            "Path:speech.config\r\n\r\n"
            '{"context":{"synthesis":{"audio":{"metadataoptions":'
            '{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},'
            f'"outputFormat":"{OUTPUT_FORMAT}"' + "}}}}"
        )

    def _request(self, body: str, voice: str) -> bytes:
        ws = self.ws
        assert ws is not None
        ws.deadline = time.monotonic() + TOTAL_TIMEOUT_S
        try:
            ws.send_text(
                f"X-RequestId:{uuid.uuid4().hex}\r\n"
                "Content-Type:application/ssml+xml\r\n"
                f"X-Timestamp:{_timestamp()}\r\n"
                "Path:ssml\r\n\r\n"
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                f"<voice name='{voice}'>{body}</voice></speak>"
            )
            audio = bytearray()
            while True:
                opcode, payload = ws.receive()
                if opcode == 0x2 and len(payload) >= 2:
                    header_len = struct.unpack(">H", payload[:2])[0]
                    if b"Path:audio" in payload[2 : 2 + header_len]:
                        audio += payload[2 + header_len :]
                elif opcode == 0x1:
                    if b"Path:turn.end" in payload:
                        break
                elif opcode == 0x8:
                    code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else 0
                    reason = payload[2:].decode("utf-8", "replace")
                    if code in (1000, 1001) and not audio:
                        raise _ConnectionLost(f"server closed: {code} {reason}".strip())
                    raise TtsError(f"server closed early: {code} {reason}".strip())
        except (OSError, TtsUnavailable) as e:
            raise _ConnectionLost(str(e)) from e
        if not audio:
            raise TtsError("no audio received")
        return bytes(audio)

    def close(self) -> None:
        if self.ws is not None:
            self.ws.close()
            self.ws = None


# LIFO: sequential requests keep reusing the warm connection.
_sessions: "queue.LifoQueue[_Session]" = queue.LifoQueue()
for _ in range(MAX_CONNECTIONS):
    _sessions.put(_Session())


class _WebSocket:
    """Just enough of RFC 6455 for one Edge request/response exchange."""

    def __init__(self, host: str, path: str, deadline: float) -> None:
        self.deadline = deadline
        raw = socket.create_connection((host, 443), timeout=CONNECT_TIMEOUT_S)
        try:
            self.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=host)
        except Exception:
            raw.close()
            raise
        self.buf = b""
        key = base64.b64encode(os.urandom(16)).decode()
        muid = uuid.uuid4().hex.upper()
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Pragma: no-cache\r\n"
            "Cache-Control: no-cache\r\n"
            f"Origin: {ORIGIN}\r\n"
            f"User-Agent: {USER_AGENT}\r\n"
            "Accept-Encoding: gzip, deflate, br, zstd\r\n"
            "Accept-Language: en-US,en;q=0.9\r\n"
            f"Cookie: muid={muid};\r\n"
            "\r\n"
        )
        self.sock.sendall(request.encode("ascii"))
        while b"\r\n\r\n" not in self.buf:
            self._fill()
            if len(self.buf) > 65536:
                raise TtsError("oversized handshake response")
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        status = head.split(b"\r\n", 1)[0].decode("latin-1")
        if " 101 " not in status + " ":
            # 429: a short burst limit. 403: the client constants are stale.
            raise TtsUnavailable(f"handshake refused: {status}", retry_s=15 if " 429 " in status + " " else 60)

    def _fill(self) -> None:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TtsUnavailable("Edge TTS request timed out")
        self.sock.settimeout(remaining)
        try:
            chunk = self.sock.recv(65536)
        except socket.timeout as e:
            raise TtsUnavailable("Edge TTS request timed out") from e
        if not chunk:
            raise TtsUnavailable("connection closed")
        self.buf += chunk

    def _read(self, n: int) -> bytes:
        while len(self.buf) < n:
            self._fill()
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        header = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def send_text(self, text: str) -> None:
        self._send_frame(0x1, text.encode("utf-8"))

    def receive(self) -> Tuple[int, bytes]:
        """The next complete message as (opcode, payload); answers pings."""
        message_opcode: Optional[int] = None
        parts = []
        while True:
            b0, b1 = self._read(2)
            fin = b0 & 0x80
            opcode = b0 & 0x0F
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if b1 & 0x80 else None
            payload = self._read(n)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x9:  # ping
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:  # pong
                continue
            if opcode == 0x8:
                return opcode, payload
            if opcode != 0x0:
                message_opcode = opcode
            parts.append(payload)
            if fin:
                return message_opcode or 0x1, b"".join(parts)

    def close(self) -> None:
        try:
            self._send_frame(0x8, struct.pack(">H", 1000))
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Windows SAPI fallback

_sapi_lock = threading.Lock()


def synthesize_windows(text: str, path: str, speed: str) -> str:
    """Writes ``text`` spoken by a local Windows voice to a WAV at ``path``.

    Used only when Edge is unreachable, so studying never goes silent."""
    try:
        import pythoncom  # type: ignore
        import win32com.client  # type: ignore
    except ImportError as e:
        raise TtsError("no offline voice available") from e

    with _sapi_lock:
        pythoncom.CoInitialize()
        try:
            voice = win32com.client.Dispatch("SAPI.SpVoice")
            voices = voice.GetVoices()
            for i in range(voices.Count):
                if "english" in voices.Item(i).GetDescription().lower():
                    voice.Voice = voices.Item(i)
                    break
            voice.Rate = _sapi_rate(speed)
            stream = win32com.client.Dispatch("SAPI.SpFileStream")
            fmt = win32com.client.Dispatch("SAPI.SpAudioFormat")
            fmt.Type = 22  # SAFT22kHz16BitMono
            stream.Format = fmt
            stream.Open(path, 3)  # SSFMCreateForWrite
            try:
                voice.AudioOutputStream = stream
                voice.Speak(text, 0)
            finally:
                stream.Close()
        finally:
            pythoncom.CoUninitialize()
    return path


def _sapi_rate(speed: str) -> int:
    """"+20%" → SAPI's -10..10 scale (roughly 10% per step)."""
    try:
        percent = int(speed.strip().rstrip("%"))
    except ValueError:
        return 0
    return max(-10, min(10, round(percent / 10)))
