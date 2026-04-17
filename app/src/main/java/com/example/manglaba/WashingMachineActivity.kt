package com.example.manglaba

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.FirebaseDatabase

class WashingMachineActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvVibration: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnSetTimer: Button
    private lateinit var btnReset: Button
    private lateinit var cardStatus: CardView

    private val handler = Handler(Looper.getMainLooper())
    private var totalTimerSeconds = 120
    private var uiRunnable: Runnable? = null

    // Broadcast receiver for cycle complete with flag check
    private val cycleCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WashingMonitorService.ACTION_CYCLE_COMPLETE) {
                Log.d("WashingMachine", "Received cycle complete broadcast!")
                if (!WashingMonitorService.dialogShownForCurrentCycle) {
                    showFullScreenAlert()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_washing_machine)

        startService(Intent(this, WashingMonitorService::class.java))

        // Show dialog if opened from notification
        if (intent.getBooleanExtra("SHOW_CYCLE_COMPLETE_DIALOG", false)) {
            showFullScreenAlert()
        }

        // Request permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        requestOverlayPermission()
        requestNotificationPermission()
        requestBatteryOptimizationPermission()

        // Initialize views
        tvStatus = findViewById(R.id.tvStatus)
        tvVibration = findViewById(R.id.tvVibration)
        tvTimer = findViewById(R.id.tvTimer)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSetTimer = findViewById(R.id.btnSetTimer)
        btnReset = findViewById(R.id.btnReset)
        cardStatus = findViewById(R.id.cardStatus)

        // Register broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            cycleCompleteReceiver,
            IntentFilter(WashingMonitorService.ACTION_CYCLE_COMPLETE)
        )

        // Start the background service
        startService(Intent(this, WashingMonitorService::class.java))

        // Sync timer from service
        totalTimerSeconds = WashingMonitorService.currentTimerSeconds
        tvTimer.text = formatTime(totalTimerSeconds)

        // Button listeners
        btnSetTimer.setOnClickListener { showTimerPickerDialog() }
        btnRefresh.setOnClickListener {
            updateUIFromService()
            Toast.makeText(this, "Timer: ${WashingMonitorService.currentTimerSeconds} seconds", Toast.LENGTH_SHORT).show()
        }
        btnReset.setOnClickListener {
            Intent(this, WashingMonitorService::class.java).apply { action = "ACTION_RESET" }.let { startService(it) }
            updateUIFromService()
            Toast.makeText(this, "All systems reset!", Toast.LENGTH_SHORT).show()
        }

        startUIUpdater()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("SHOW_CYCLE_COMPLETE_DIALOG", false) == true) {
            showFullScreenAlert()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 100)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    override fun onBackPressed() {
        Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(this)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(uiRunnable!!)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cycleCompleteReceiver)
    }

    private fun showFullScreenAlert() {
        Log.d("WashingMachine", "showFullScreenAlert() CALLED")

        // Set flag to prevent multiple dialogs
        WashingMonitorService.dialogShownForCurrentCycle = true

        val dialog = Dialog(this, R.style.FullScreenDialog)
        dialog.setContentView(R.layout.dialog_cycle_complete)
        dialog.setCancelable(false)

        val btnOk = dialog.findViewById<Button>(R.id.btnDialogOk)
        btnOk.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        Log.d("WashingMachine", "Dialog should be visible now")
    }

    private fun showTimerPickerDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_timer_picker)

        val numberPicker = dialog.findViewById<NumberPicker>(R.id.numberPickerMinutes)
        val btnSet = dialog.findViewById<Button>(R.id.btnSetTimer)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancel)

        numberPicker.minValue = 1
        numberPicker.maxValue = 60
        numberPicker.value = totalTimerSeconds / 60

        btnSet.setOnClickListener {
            val minutes = numberPicker.value
            totalTimerSeconds = minutes * 60

            // Save to Firebase for ESP32
            val timerRef = FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference
            timerRef.child("washingMachine").child("setTimer").setValue(totalTimerSeconds)
                .addOnSuccessListener { Log.d("WashingMachine", "Timer saved: $totalTimerSeconds seconds") }
                .addOnFailureListener { e -> Log.e("WashingMachine", "Timer save failed: ${e.message}") }

            // Update local service
            Intent(this, WashingMonitorService::class.java).apply {
                action = "ACTION_SET_TIMER"
                putExtra("TIMER_SECONDS", totalTimerSeconds)
            }.let { startService(it) }

            tvTimer.text = formatTime(totalTimerSeconds)
            Toast.makeText(this, "Timer set to $minutes minute(s)", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun startUIUpdater() {
        uiRunnable = object : Runnable {
            override fun run() {
                updateUIFromService()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(uiRunnable!!)
    }

    private fun updateUIFromService() {
        // Directly use the service's timer value
        val serviceTimer = WashingMonitorService.currentTimerSeconds
        tvTimer.text = formatTime(serviceTimer)

        tvStatus.text = WashingMonitorService.currentMachineStatus
        tvVibration.text = WashingMonitorService.currentVibrationStatus

        when {
            WashingMonitorService.isTimerRunning -> {
                tvStatus.setTextColor(0xFFFF9800.toInt())
                cardStatus.setCardBackgroundColor(0xFFFFF3E0.toInt())
                when {
                    serviceTimer <= 10 -> tvTimer.setTextColor(0xFFF44336.toInt())
                    serviceTimer <= 30 -> tvTimer.setTextColor(0xFFFF9800.toInt())
                    else -> tvTimer.setTextColor(0xFF2196F3.toInt())
                }
            }
            WashingMonitorService.isMachineRunning -> {
                tvStatus.setTextColor(0xFF4CAF50.toInt())
                cardStatus.setCardBackgroundColor(0xFFE8F5E9.toInt())
                tvTimer.setTextColor(0xFF4CAF50.toInt())
            }
            else -> {
                tvStatus.setTextColor(0xFF9E9E9E.toInt())
                cardStatus.setCardBackgroundColor(0xFFF5F5F5.toInt())
                tvTimer.setTextColor(0xFF9E9E9E.toInt())
            }
        }
    }

    private fun formatTime(seconds: Int) = String.format("%02d:%02d", seconds / 60, seconds % 60)

    override fun onResume() {
        super.onResume()
        ForegroundTracker.currentActivity = "WashingMachineActivity"
        updateUIFromService() // Force update when returning to the activity
    }

    override fun onPause() {
        super.onPause()
        if (ForegroundTracker.currentActivity == "WashingMachineActivity") ForegroundTracker.currentActivity = null
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted – dialogs will appear over other apps", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Overlay permission denied – dialogs may not appear over other apps", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}