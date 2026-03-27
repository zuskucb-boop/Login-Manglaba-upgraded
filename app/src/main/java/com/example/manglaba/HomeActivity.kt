package com.example.manglaba

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Get references to all views
        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
        val btnStartWashing = findViewById<Button>(R.id.btnStartWashing)
        val btnHistory = findViewById<Button>(R.id.btnHistory)
        val btnProfile = findViewById<Button>(R.id.btnProfile)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        // Get username from login (if passed)
        val username = intent.getStringExtra("USERNAME")
        if (username != null) {
            tvWelcome.text = "Welcome, $username!"
        }

        // Start Washing button - Navigate to Washing Machine Monitor
        btnStartWashing.setOnClickListener {
            val intent = Intent(this, WashingMachineActivity::class.java)
            startActivity(intent)
        }

        // View History button
        btnHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        // My Profile button
        btnProfile.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            // Get username from welcome text or intent
            val username = intent.getStringExtra("USERNAME") ?: tvWelcome.text.toString().replace("Welcome, ", "").replace("!", "")
            intent.putExtra("USER_EMAIL", username)
            startActivity(intent)
        }

        btnLogout.setOnClickListener {
            // Stop the background service
            stopService(Intent(this, WashingMonitorService::class.java))

            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

    }
}