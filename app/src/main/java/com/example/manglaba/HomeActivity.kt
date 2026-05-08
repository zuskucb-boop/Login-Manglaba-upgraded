package com.example.manglaba

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import android.widget.Toast

class HomeActivity : AppCompatActivity() {

    private lateinit var sharedPref: SharedPreferences
    private var userEmail: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        sharedPref = getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
        userEmail = sharedPref.getString("USER_EMAIL", "") ?: ""

        // Also get from intent if available (for fresh login)
        val intentEmail = intent.getStringExtra("USERNAME")
        if (intentEmail != null && intentEmail.isNotEmpty()) {
            userEmail = intentEmail
            sharedPref.edit().putString("USER_EMAIL", userEmail).apply()
        }

        WashingMonitorService.isUserLoggedIn = true

        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
        val btnStartWashing = findViewById<Button>(R.id.btnStartWashing)
        val btnHistory = findViewById<Button>(R.id.btnHistory)
        val btnProfile = findViewById<Button>(R.id.btnProfile)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        if (userEmail.isNotEmpty()) {
            tvWelcome.text = "Welcome, $userEmail!"
        }

        btnStartWashing.setOnClickListener {
            val intent = Intent(this, MachineListActivity::class.java)
            intent.putExtra("USER_EMAIL", userEmail)
            startActivity(intent)
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        btnProfile.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            intent.putExtra("USER_EMAIL", userEmail)
            startActivity(intent)
        }

        btnLogout.setOnClickListener {
            WashingMonitorService.isUserLoggedIn = false
            WashingMonitorService.dialogShownForCurrentCycle = false

            // Stop service with cleanup
            val stopIntent = Intent(this, WashingMonitorService::class.java)
            stopIntent.action = "ACTION_STOP_SERVICE"
            stopService(stopIntent)

            // Reset Firebase
            FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/")
                .reference.child("washingMachines").child(WashingMonitorService.currentMachineId)
                .updateChildren(mapOf(
                    "status" to "idle",
                    "appConnected" to false,
                    "intensity" to 0,
                    "timer" to 120,
                    "lastUpdate" to System.currentTimeMillis()
                ))

            sharedPref.edit().remove("USER_EMAIL").apply()
            WashingMonitorService.resetCompanion()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancelAll()

            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
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

    override fun onBackPressed() {
        // Go back to LaundryReadyActivity (welcome screen)
        val intent = Intent(this, LaundryReadyActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun resetWashingMachineStatus() {
        val database =
            FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference
        val updates = mapOf<String, Any>(
            "status" to "idle",
            "appConnected" to false,
            "intensity" to 0,
            "timer" to 120,
            "lastUpdate" to System.currentTimeMillis()
        )
        // Fix: "washingMachine" -> "washingMachines"
        database.child("washingMachines").child(WashingMonitorService.currentMachineId)
            .updateChildren(updates)
    }
}