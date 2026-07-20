package com.example.vacationguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OWNER APP - runs on the homeowner's phone anywhere in the world.
 * Shows home status (online/offline), receives intrusion alerts with
 * snapshots, and can request a live snapshot or trigger the siren.
 */
class OwnerActivity : AppCompatActivity() {

    private lateinit var mqtt: Mqtt
    private lateinit var statusText: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var snapshot: ImageView
    private var lastHeartbeat = 0L
    private var liveOn = false
    private lateinit var btnLive: Button
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        const val OFFLINE_AFTER_MS = 60_000L
        const val CHANNEL = "vg_alerts"
        const val NOTIF_ID = 10
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (lastHeartbeat > 0 && System.currentTimeMillis() - lastHeartbeat > OFFLINE_AFTER_MS) {
                statusText.text = getString(R.string.home_offline)
            }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner)
        statusText = findViewById(R.id.txtOwnerStatus)
        log = findViewById(R.id.txtLog)
        logScroll = findViewById(R.id.logScroll)
        snapshot = findViewById(R.id.imgSnapshot)

        findViewById<Button>(R.id.btnSnapshot).setOnClickListener {
            mqtt.publish(Mqtt.T_CMD, Mqtt.CMD_SNAPSHOT.toByteArray())
            appendLog(getString(R.string.log_snapshot_requested))
        }
        findViewById<Button>(R.id.btnSiren).setOnClickListener {
            mqtt.publish(Mqtt.T_CMD, Mqtt.CMD_SIREN.toByteArray())
            appendLog(getString(R.string.log_siren_sent))
        }
        findViewById<Button>(R.id.btnSirenOff).setOnClickListener {
            mqtt.publish(Mqtt.T_CMD, Mqtt.CMD_SIREN_OFF.toByteArray())
            appendLog(getString(R.string.log_siren_off_sent))
        }
        btnLive = findViewById(R.id.btnLive)
        btnLive.setOnClickListener {
            liveOn = !liveOn
            mqtt.publish(Mqtt.T_CMD,
                (if (liveOn) Mqtt.CMD_LIVE_ON else Mqtt.CMD_LIVE_OFF).toByteArray())
            btnLive.text = getString(if (liveOn) R.string.live_stop else R.string.live_button)
            appendLog(getString(if (liveOn) R.string.log_live_on else R.string.log_live_off))
        }

        createChannel()
        val homeId = intent.getStringExtra(Mqtt.EXTRA_HOME_ID) ?: "demo"
        mqtt = Mqtt(homeId)
        mqtt.connect(
            lastWillOffline = false,
            onMessage = { sub, payload -> runOnUiThread { onMessage(sub, payload) } },
            onConnected = { ok ->
                runOnUiThread {
                    statusText.text = getString(
                        if (ok) R.string.waiting_for_home else R.string.status_connecting
                    )
                }
            },
            subs = listOf(Mqtt.T_STATUS, Mqtt.T_ALERT, Mqtt.T_SNAPSHOT, Mqtt.T_LIVE)
        )
        handler.post(watchdog)
    }

    private fun onMessage(sub: String, payload: ByteArray) {
        when (sub) {
            Mqtt.T_STATUS -> {
                val text = String(payload)
                if (text.startsWith("ONLINE")) {
                    lastHeartbeat = System.currentTimeMillis()
                    statusText.text = getString(R.string.home_online)
                } else {
                    statusText.text = getString(R.string.home_offline)
                }
            }
            Mqtt.T_ALERT -> {
                val text = String(payload)
                appendLog(text)
                notifyAlert(text)
                vibrate()
            }
            Mqtt.T_SNAPSHOT -> {
                val bmp = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                if (bmp != null) {
                    snapshot.setImageBitmap(bmp)
                    appendLog(getString(R.string.log_snapshot_received, payload.size / 1024))
                }
            }
            Mqtt.T_LIVE -> {
                val bmp = BitmapFactory.decodeByteArray(payload, 0, payload.size)
                if (bmp != null) snapshot.setImageBitmap(bmp)
            }
        }
    }

    private fun appendLog(line: String) {
        log.append("[${fmt.format(Date())}] $line\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun notifyAlert(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.alert_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, n)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdog)
        mqtt.disconnect()
    }
}
