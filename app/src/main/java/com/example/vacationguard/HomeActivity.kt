package com.example.vacationguard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * HOME UNIT - runs on the phone left at the vacation home.
 * Watches the camera for motion; on motion (or on owner request) it
 * captures a snapshot and publishes an alert + JPEG over MQTT.
 * The owner can also trigger the siren remotely (protective response).
 */
@SuppressLint("MissingPermission")
class HomeActivity : AppCompatActivity() {

    private lateinit var mqtt: Mqtt
    private lateinit var status: TextView
    private lateinit var motionText: TextView
    private lateinit var armButton: Button
    private var imageCapture: ImageCapture? = null
    private var armed = true
    private var lastAlertAt = 0L
    private var siren: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        const val MOTION_THRESHOLD = 14.0
        const val ALERT_COOLDOWN_MS = 15_000L
        const val HEARTBEAT_MS = 20_000L
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            mqtt.publish(Mqtt.T_STATUS, "ONLINE ${fmt.format(Date())}".toByteArray(), true)
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

        val homeId = intent.getStringExtra(Mqtt.EXTRA_HOME_ID) ?: "demo"
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
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(executor, MotionDetector { score -> onMotionScore(score) })
                }
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onMotionScore(score: Double) {
        runOnUiThread { motionText.text = getString(R.string.motion_level, score) }
        val now = System.currentTimeMillis()
        if (armed && score > MOTION_THRESHOLD && now - lastAlertAt > ALERT_COOLDOWN_MS) {
            lastAlertAt = now
            val msg = getString(R.string.alert_motion, fmt.format(Date()))
            mqtt.publish(Mqtt.T_ALERT, msg.toByteArray())
            runOnUiThread { status.text = msg }
            captureAndPublish()
        }
    }

    private fun onCommand(cmd: String) = runOnUiThread {
        when (cmd) {
            Mqtt.CMD_SNAPSHOT -> captureAndPublish()
            Mqtt.CMD_SIREN -> startSiren()
            Mqtt.CMD_SIREN_OFF -> stopSiren()
        }
    }

    private fun captureAndPublish() {
        val capture = imageCapture ?: return
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                image.close()
                publishJpeg(bytes)
            }
            override fun onError(exception: ImageCaptureException) {}
        })
    }

    /** Downscale to ~640px wide, JPEG q60, then publish (keeps payload small). */
    private fun publishJpeg(jpeg: ByteArray) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return
        val scale = 640.0 / bmp.width
        val small = if (scale < 1.0) Bitmap.createScaledBitmap(
            bmp, 640, (bmp.height * scale).toInt(), true
        ) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        mqtt.publish(Mqtt.T_SNAPSHOT, out.toByteArray())
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
        mqtt.publish(Mqtt.T_STATUS, "OFFLINE".toByteArray(), true)
        mqtt.disconnect()
        stopSiren()
        executor.shutdown()
    }
}
