package linuxboot.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import linuxboot.MainActivity
import linuxboot.R

/**
 * Keeps the app alive while an install, backup, shell or desktop session is running. Without it,
 * Android can freeze or kill a backgrounded app and the Linux processes it started at any moment.
 * It runs only while something needs it and shows a notification so that is never hidden.
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("LinuxBoot is running")
            .setContentText("A system is installing or running. Tap to open.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed to run in the foreground right now; the session still works while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // A long installation must not stall when the screen turns off. The timeout is a safety net.
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linuxboot:session").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Running systems", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL = "session"
        private const val ID = 1

        fun setRunning(context: Context, run: Boolean) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (run) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
            } catch (e: Exception) {
                // Starting a foreground service from the background is not allowed; nothing to do.
            }
        }
    }
}
