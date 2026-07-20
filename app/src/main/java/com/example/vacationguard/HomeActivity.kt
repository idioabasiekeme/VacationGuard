package com.example.vacationguard

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.ImageFormat
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * HOME UNIT - runs on the phone left at the vacation home.
 * - Watches the camera for motion (frame differencing).
 * - On motion: publishes an alert + snapshot, records 20 s of footage
 *   and emails it to the owner (Gmail SMTP).
 * - Streams a live feed (~1.5 fps JPEG frames) when the owner asks.
 * - Sounds the siren on remote command (protective response).
 */
@SuppressLint("MissingPermission")
class HomeActivity : AppCompatActivity() {

    private lateinit var mqtt: Mqtt
    private lateinit var status: TextView
    private lateinit var motionText: TextView
    private lateinit var armButton: Button
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var armed = true
    private var lastAlertAt = 0L
    private var siren: Ringtone? = null
    private var liveStreaming = false
    private var liveUntil = 0L
    private var lastLiveFrameAt = 0L
    private var snapshotWanted = false
    private var emailFrom = ""
    private var emailPass = ""
    private var emailTo = ""
    private lateinit var homeId: String
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val mailExecutor = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        const val MOTION_THRESHOLD = 14.0
        const val ALERT_COOLDOWN_MS = 30_000L
        const val HEARTBEAT_MS = 20_000L
        const val FOOTAGE_MS = 20_000L
        const val LIVE_FRAME_INTERVAL_MS = 700L
        const val LIVE_AUTO_OFF_MS = 60_000L
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            mqtt.publish(Mqtt.T_STATUS, "ONLINE ${fmt.format(Date())}".toByteArray(), true)
            if (liveStreaming && System.currentTimeMillis() > liveUntil) liveStreaming = false
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        status = findViewById(R.id.txtHomeStatus)
        motionText = findViewById(R.id.txtMotion)
        armButton = findViewById(R.id.btnArm)

        armButton.setOnClickListener {
            armed = !armed
            armButton.text = getString(if (armed) R.string.disarm else R.string.arm)
            status.text = getString(if (armed) R.string.status_armed else R.string.status_disarmed)
        }

        homeId = intent.getStringExtra(Mqtt.EXTRA_HOME_ID) ?: "demo"
        emailFrom = intent.getStringExtra(Mqtt.EXTRA_EMAIL_FROM) ?: ""
        emailPass = intent.getStringExtra(Mqtt.EXTRA_EMAIL_PASS) ?: ""
        emailTo = intent.getStringExtra(Mqtt.EXTRA_EMAIL_TO) ?: ""

        mqtt = Mqtt(homeId)
        mqtt.connect(
            lastWillOffline = true,
            onMessage = { sub, payload -> if (sub == Mqtt.T_CMD) onCommand(String(payload)) },
            onConnected = { ok ->
                runOnUiThread {
                    status.text = getString(
                        if (ok) { if (armed) R.string.status_armed else R.string.status_disarmed }
                        else R.string.status_connecting
                    )
                }
                if (ok) handler.post(heartbeat)
            },
            subs = listOf(Mqtt.T_CMD)
        )

        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            }
            analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(executor, MotionDetector(
                        onScore = { score -> onMotionScore(score) },
                        frameWanted = {
                            snapshotWanted || (liveStreaming &&
                                System.currentTimeMillis() - lastLiveFrameAt > LIVE_FRAME_INTERVAL_MS)
                        },
                        onFrame = { nv21, w, h -> onFrame(nv21, w, h) }
                    ))
                }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            bindNormal()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindNormal() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview!!, analysis!!)
    }

    /** Called from the analyzer with an NV21 frame when snapshot/live wanted. */
    private fun onFrame(nv21: ByteArray, w: Int, h: Int) {
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(Rect(0, 0, w, h), 55, out)
        val jpeg = out.toByteArray()
        if (snapshotWanted) {
            snapshotWanted = false
            mqtt.publish(Mqtt.T_SNAPSHOT, jpeg)
        }
        if (liveStreaming) {
            lastLiveFrameAt = System.currentTimeMillis()
            mqtt.publish(Mqtt.T_LIVE, jpeg)
        }
    }

    private fun onMotionScore(score: Double) {
        runOnUiThread { motionText.text = getString(R.string.motion_level, score) }
        val now = System.currentTimeMillis()
        if (armed && score > MOTION_THRESHOLD && now - lastAlertAt > ALERT_COOLDOWN_MS
            && activeRecording == null) {
            lastAlertAt = now
            val msg = getString(R.string.alert_motion, fmt.format(Date()))
            mqtt.publish(Mqtt.T_ALERT, msg.toByteArray())
            snapshotWanted = true
            runOnUiThread {
                status.text = msg
                recordAndEmailFootage()
            }
        }
    }

    private fun onCommand(cmd: String) = runOnUiThread {
        when (cmd) {
            Mqtt.CMD_SNAPSHOT -> snapshotWanted = true
            Mqtt.CMD_SIREN -> startSiren()
            Mqtt.CMD_SIREN_OFF -> stopSiren()
            Mqtt.CMD_LIVE_ON -> {
                liveStreaming = true
                liveUntil = System.currentTimeMillis() + LIVE_AUTO_OFF_MS
            }
            Mqtt.CMD_LIVE_OFF -> liveStreaming = false
        }
    }

    /** Records 20 s of video, then emails it to the owner. */
    private fun recordAndEmailFootage() {
        if (emailFrom.isBlank() || emailPass.isBlank() || emailTo.isBlank()) return
        val provider = cameraProvider ?: return
        val vc = videoCapture ?: return
        if (activeRecording != null) return

        // Rebind with VideoCapture (analysis pauses while recording).
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview!!, vc)

        val file = File(cacheDir, "vacationguard_footage.mp4")
        if (file.exists()) file.delete()
        status.text = getString(R.string.recording_footage)
        activeRecording = vc.output
            .prepareRecording(this, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    activeRecording = null
                    bindNormal()
                    status.text = getString(if (armed) R.string.status_armed else R.string.status_disarmed)
                    if (!event.hasError()) emailFootage(file)
                }
            }
        handler.postDelayed({ activeRecording?.stop() }, FOOTAGE_MS)
    }

    private fun emailFootage(file: File) {
        mailExecutor.execute {
            try {
                MailSender.send(
                    from = emailFrom,
                    appPassword = emailPass,
                    to = emailTo,
                    subject = getString(R.string.email_subject, homeId),
                    body = getString(R.string.email_body, fmt.format(Date()), homeId),
                    attachment = file
                )
                runOnUiThread {
                    Toast.makeText(this, R.string.email_sent, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this,
                        getString(R.string.email_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startSiren() {
        if (siren == null) {
            siren = RingtoneManager.getRingtone(
                this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            )
        }
        siren?.play()
        status.text = getString(R.string.siren_on)
    }

    private fun stopSiren() {
        siren?.stop()
        status.text = getString(if (armed) R.string.status_armed else R.string.status_disarmed)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(heartbeat)
        activeRecording?.stop()
        mqtt.publish(Mqtt.T_STATUS, "OFFLINE".toByteArray(), true)
        mqtt.disconnect()
        stopSiren()
        executor.shutdown()
        mailExecutor.shutdown()
    }
}
