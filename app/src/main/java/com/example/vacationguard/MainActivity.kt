package com.example.vacationguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Mode picker. Both phones must use the SAME Home ID - it forms the
 * private MQTT topic that links the home unit and the owner app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var edtHomeId: EditText
    private var pendingTarget: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        edtHomeId = findViewById(R.id.edtHomeId)

        findViewById<Button>(R.id.btnHomeMode).setOnClickListener {
            launchWithPermissions(HomeActivity::class.java,
                mutableListOf(Manifest.permission.CAMERA).withNotifications())
        }
        findViewById<Button>(R.id.btnOwnerMode).setOnClickListener {
            launchWithPermissions(OwnerActivity::class.java,
                mutableListOf<String>().withNotifications())
        }
    }

    private fun MutableList<String>.withNotifications(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return this
    }

    private fun launchWithPermissions(target: Class<*>, permissions: List<String>) {
        val homeId = edtHomeId.text.toString().trim()
        if (homeId.length < 4) {
            Toast.makeText(this, R.string.home_id_required, Toast.LENGTH_LONG).show()
            return
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startActivity(Intent(this, target).putExtra(Mqtt.EXTRA_HOME_ID, homeId))
        } else {
            pendingTarget = target
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, R.string.permissions_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
        }
    }
}
