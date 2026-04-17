package com.example.manglaba

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        WashingMonitorService.isUserLoggedIn = true

        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
        val btnStartWashing = findViewById<Button>(R.id.btnStartWashing)
        val btnHistory = findViewById<Button>(R.id.btnHistory)
        val btnProfile = findViewById<Button>(R.id.btnProfile)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        val username = intent.getStringExtra("USERNAME")
        if (username != null) {
            tvWelcome.text = "Welcome, $username!"
        }

        btnStartWashing.setOnClickListener {
            startActivity(Intent(this, WashingMachineActivity::class.java))
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        btnProfile.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            val username = intent.getStringExtra("USERNAME") ?: tvWelcome.text.toString().replace("Welcome, ", "").replace("!", "")
            intent.putExtra("USER_EMAIL", username)
            startActivity(intent)
        }

        btnLogout.setOnClickListener {
            resetWashingMachineStatus()

            // Reset values
            WashingMonitorService.isUserLoggedIn = false
            WashingMonitorService.dialogShownForCurrentCycle = false

            // Stop service
            val serviceIntent = Intent(this, WashingMonitorService::class.java)
            stopService(serviceIntent)

            // Cancel notifications
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancelAll()

            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()

            // Kill the process to ensure everything is cleared
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onResume() {
        super.onResume()
        ForegroundTracker.currentActivity = "HomeActivity"
    }

    override fun onPause() {
        super.onPause()
        if (ForegroundTracker.currentActivity == "HomeActivity") {
            ForegroundTracker.currentActivity = null
        }
    }

    private fun resetWashingMachineStatus() {
        resetWashingMachineStatus()
        val firebaseUrl = "https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/"
        val database = FirebaseDatabase.getInstance(firebaseUrl).reference
        val updates = mapOf<String, Any>(
            "status" to "idle",
            "intensity" to 0,
            "lastUpdate" to System.currentTimeMillis()
        )
        database.child("washingMachine").updateChildren(updates)
            .addOnSuccessListener { Log.d("HomeActivity", "Firebase reset on logout") }
            .addOnFailureListener { Log.e("HomeActivity", "Firebase reset failed") }
    }
}