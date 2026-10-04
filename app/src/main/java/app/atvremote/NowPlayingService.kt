package app.atvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import app.atvremote.protocol.NowPlaying
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Hands the Apple TV's now-playing state to [NowPlayingService], and its buttons back to the remote. */
object NowPlayingBridge {
    data class State(val nowPlaying: NowPlaying, val artwork: Bitmap?, val appName: String?, val deviceName: String)

    interface Controls {
        fun playPause()
        fun next()
        fun previous()
        fun seek(seconds: Double)
    }

    /** Null when nothing is playing; the service stops itself then. */
    val state = MutableStateFlow<State?>(null)
    @Volatile var controls: Controls? = null
    @Volatile var running = false
}

/**
 * Foreground service behind the now-playing notification: a media-style notification tied to a
 * MediaSession, so it shows as a media player in the notification drawer (play/pause, previous,
 * next, seek). Running in the foreground also keeps the Apple TV connection alive while the app
 * is in the background.
 */
class NowPlayingService : Service() {
    private val scope = MainScope()
    private lateinit var session: MediaSession
    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        NowPlayingBridge.running = true
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "atvremote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { NowPlayingBridge.controls?.playPause() }
                override fun onPause() { NowPlayingBridge.controls?.playPause() }
                override fun onSkipToNext() { NowPlayingBridge.controls?.next() }
                override fun onSkipToPrevious() { NowPlayingBridge.controls?.previous() }
                override fun onSeekTo(pos: Long) { NowPlayingBridge.controls?.seek(pos / 1000.0) }
            })
            isActive = true
        }
        val first = NowPlayingBridge.state.value
        val notification = if (first != null) update(first) else placeholder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch {
            NowPlayingBridge.state.collect { s ->
                if (s == null) stopSelf() else manager.notify(NOTIFICATION_ID, update(s))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = NowPlayingBridge.controls
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> c?.playPause()
            ACTION_NEXT -> c?.next()
            ACTION_PREVIOUS -> c?.previous()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NowPlayingBridge.running = false
        scope.cancel()
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Pushes [s] to the media session and returns the matching notification. */
    private fun update(s: NowPlayingBridge.State): Notification {
        val np = s.nowPlaying
        val subtitle = np.subtitle ?: s.appName ?: s.deviceName
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, np.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .apply { np.duration?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, (it * 1000).toLong()) } }
                .apply { s.artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
                .build(),
        )
        // The session wants the position at an elapsedRealtime timestamp; ours is at positionAtMs (wall clock).
        val updated = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - np.positionAtMs)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        (if (np.duration != null) PlaybackState.ACTION_SEEK_TO else 0L),
                )
                .setState(
                    if (np.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    np.position?.let { (it * 1000).toLong() } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (np.playing) np.rate.toFloat() else 0f,
                    updated,
                )
                .build(),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(np.title)
            .setContentText(subtitle)
            .setLargeIcon(s.artwork)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (np.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_PLAY_PAUSE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY_PAUSE),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun placeholder(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_remote)
        .setContentTitle("Apple TV")
        .setContentIntent(openApp())
        .build()

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(), Intent(this, NowPlayingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL = "now_playing"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "app.atvremote.PLAY_PAUSE"
        const val ACTION_NEXT = "app.atvremote.NEXT"
        const val ACTION_PREVIOUS = "app.atvremote.PREVIOUS"
    }
}
