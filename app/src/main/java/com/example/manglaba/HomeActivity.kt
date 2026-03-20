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
        setContentView(R.layout.activity_home)  // This now correctly points to activity_home.xml

        // Get references to all views from activity_home.xml
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

        // Start Washing button
        btnStartWashing.setOnClickListener {
            Toast.makeText(this, "Start Washing clicked", Toast.LENGTH_SHORT).show()
            // TODO: Add navigation to washing screen later
        }

        // View History button
        btnHistory.setOnClickListener {
            Toast.makeText(this, "View History clicked", Toast.LENGTH_SHORT).show()
            // TODO: Add navigation to history screen later
        }

        // My Profile button
        btnProfile.setOnClickListener {
            Toast.makeText(this, "My Profile clicked", Toast.LENGTH_SHORT).show()
            // TODO: Add navigation to profile screen later
        }

        // Log Out button
        btnLogout.setOnClickListener {
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()

            // Go back to Login screen
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}