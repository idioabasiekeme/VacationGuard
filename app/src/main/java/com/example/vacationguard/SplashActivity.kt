package com.example.vacationguard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Intro screen: scrolling (marquee) assignment attribution + Next button. */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        // A marquee TextView only scrolls while it is "selected".
        findViewById<TextView>(R.id.txtScroll).isSelected = true
        findViewById<Button>(R.id.btnNext).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
