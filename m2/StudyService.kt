package com.eugen.ankiaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M2 — eyes-free, screen-off study session.
 *
 * A foreground media service wraps [StudyEngine]. While the session runs, an
 * active MediaSession with *remote volume control* reroutes the hardware
 * volume keys to us — including with the screen off:
 *
 *   question playing/idle:  vol-up = reveal answer · vol-down = replay question
 *   answer shown:           vol-up = Good          · vol-down = Again
 *
 * Wrong key? Wake the phone: the lock-screen notification has an **Undo**
 * button (ratings are committed lazily, so the last one can be taken back).
 *
 * Trade-off while a session is active: volume keys no longer change the real
 * media volume — set your listening volume before starting.
 */
class StudyService : Service() {

    companion object {
        const val ACTION_START = "com.eugen.ankiaudio.START"
        const val ACTION_STOP = "com.eugen.ankiaudio.STOP"
        const val ACTION_PRIMARY = "com.eugen.ankiaudio.PRIMARY"
        const val ACTION_SECONDARY = "com.eugen.ankiaudio.SECONDARY"
        const val ACTION_UNDO = "com.eugen.ankiaudio.UNDO"
        const val EXTRA_DECK = "deck"

        private const val TAG = "StudyService"
        private const val CHANNEL_ID = "study_session"
        private const val NOTIF_ID = 1

        /** Volume rockers auto-repeat when held; collapse bursts into one action. */
        private const val KEY_DEBOUNCE_MS = 450L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var speaker: CardSpeaker
    private lateinit var engine: StudyEngine
    private lateinit var session: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var lastKeyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speaker = CardSpeaker(this, scope)
        engine = StudyEngine(this, speaker) { text -> notifyState(text) }
        engine.onFinished = { endSessionSoon() }

        session = MediaSession(this, "AnkiAudio").apply {
            // Required even when empty: without a callback the framework never
            // creates its callback handler, and VolumeProvider.onAdjustVolume
            // messages are silently dropped.
            setCallback(object : MediaSession.Callback() {})
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            setPlaybackToRemote(object :
                VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    val now = SystemClock.elapsedRealtime()
                    if (direction == 0 || now - lastKeyAt < KEY_DEBOUNCE_MS) return
                    lastKeyAt = now
                    Log.i(TAG, "volume key direction=$direction answerShown=${engine.answerShown}")
                    if (direction > 0) onPrimary() else onSecondary()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startSession(
                intent.getStringExtra(EXTRA_DECK) ?: MainActivity.DEFAULT_DECK_NAME
            )
            ACTION_PRIMARY -> onPrimary()
            ACTION_SECONDARY -> onSecondary()
            ACTION_UNDO -> scope.launch { engine.undo() }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        engine.stopBlocking() // commits a pending rating; must not lose it
        speaker.shutdown()
        session.release()
        wakeLock?.release()
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        scope.cancel()
        super.onDestroy()
    }

    // ---- session lifecycle ----

    private fun startSession(deckName: String) {
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        acquireWakeLock()
        requestAudioFocus()
        scope.launch {
            if (!engine.start(deckName)) endSessionSoon()
        }
    }

    private fun endSessionSoon() {
        scope.launch {
            delay(5000)
            stopSelf()
        }
    }

    // ---- controls ----

    private fun onPrimary() {
        scope.launch {
            if (!engine.answerShown) engine.reveal() else engine.rate(AnkiDroidApi.EASE_GOOD)
        }
    }

    private fun onSecondary() {
        scope.launch {
            if (!engine.answerShown) engine.replayQuestion() else engine.rate(AnkiDroidApi.EASE_AGAIN)
        }
    }

    // ---- plumbing ----

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnkiAudio:study").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // 4h safety cap
        }
    }

    private fun requestAudioFocus() {
        val am = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(focusRequest!!)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Study session", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notifyState(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        fun serviceAction(action: String): PendingIntent = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, StudyService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )

        fun action(iconRes: Int, title: String, intentAction: String): Notification.Action =
            Notification.Action.Builder(
                Icon.createWithResource(this, iconRes), title, serviceAction(intentAction)
            ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Anki Audio — ${engine.deck?.name ?: "…"}")
            .setContentText(text)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .addAction(action(android.R.drawable.ic_media_rew, "Undo", ACTION_UNDO))
            .addAction(action(android.R.drawable.ic_media_previous, "Replay/Again", ACTION_SECONDARY))
            .addAction(action(android.R.drawable.ic_media_next, "Reveal/Good", ACTION_PRIMARY))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .build()
    }
}
