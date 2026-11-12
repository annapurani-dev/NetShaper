package com.netshaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Local VPN that captures ALL device traffic (0.0.0.0/0) and hands it to [PacketForwarder].
 *
 * Safety: START_NOT_STICKY, and Android tears the VPN down automatically when this process dies
 * (crash / force-stop / swipe), which restores normal networking. Stop = close the TUN.
 */
class ShaperVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.netshaper.START"
        const val ACTION_STOP = "com.netshaper.STOP"
        private const val CHANNEL = "netshaper"
        private const val NOTIF_ID = 1

        @Volatile var running = false
    }

    private var pfd: ParcelFileDescriptor? = null
    private var fwd: PacketForwarder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopShaping()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startShaping()
        return START_NOT_STICKY
    }

    private fun startShaping() {
        if (running) return
        AppLog.init(this)
        val (cfg, warn) = ConfigStore.load(this)
        if (warn != null) AppLog.log("WARN $warn")
        ShaperEngine.config = cfg
        if (ShaperEngine.settings == null) ShaperEngine.settings = cfg.defaultSettings()

        val b = Builder()
            .setSession("NetShaper")
            .setMtu(1500)
            .addAddress("10.77.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        if (cfg.blockIpv6) {
            b.addAddress("fd00:77::2", 128)
            b.addRoute("::", 0)
        }
        for (d in cfg.dnsServers) {
            try {
                b.addDnsServer(d)
            } catch (_: Exception) {
            }
        }
        try {
            b.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }

        val p = try {
            b.establish()
        } catch (e: Exception) {
            AppLog.log("establish() failed: $e")
            null
        }
        if (p == null) {
            AppLog.log("VPN permission missing or revoked")
            stopShaping()
            return
        }
        pfd = p
        val f = PacketForwarder(this, FileInputStream(p.fileDescriptor), FileOutputStream(p.fileDescriptor), cfg.blockIpv6)
        fwd = f
        running = true
        ShaperEngine.start()
        Thread({
            f.run()
            if (running) {
                AppLog.log("packet loop exited unexpectedly; stopping")
                Handler(Looper.getMainLooper()).post { stopShaping() }
            }
        }, "tun-loop").apply { isDaemon = true }.start()
        AppLog.log("VPN started (blockIpv6=${cfg.blockIpv6}, config=${ConfigStore.externalFile(this).absolutePath})")
    }

    private fun teardown() {
        val wasRunning = running
        running = false
        ShaperEngine.stop()
        fwd?.stop()
        fwd = null
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        if (wasRunning) AppLog.log("VPN stopped; network is back to normal")
    }

    private fun stopShaping() {
        teardown()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        stopShaping()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "NetShaper", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ShaperVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return nb
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("NetShaper is shaping ALL traffic")
            .setContentText("Tap to open controls. Use Stop to restore normal network.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop
                ).build()
            )
            .build()
    }
}
