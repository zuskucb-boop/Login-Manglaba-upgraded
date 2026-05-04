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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

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

    // Machine ID and Name
    private lateinit var machineId: String
    private lateinit var machineName: String
    private lateinit var userEmail: String
    private lateinit var database: DatabaseReference

    // Broadcast receiver for cycle complete
    private val cycleCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WashingMonitorService.ACTION_CYCLE_COMPLETE) {
                val intentMachineId = intent.getStringExtra("MACHINE_ID") ?: ""
                if (intentMachineId == machineId) {
                    Log.d("WashingMachine", "Received cycle complete broadcast for this machine!")
                    if (!WashingMonitorService.dialogShownForCurrentCycle) {
                        showFullScreenAlert()
                    }
                }
            }
        }
    }

    // Broadcast receiver for machine data updates
    private val machineDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WashingMonitorService.ACTION_MACHINE_DATA_UPDATED) {
                val updatedMachineId = intent.getStringExtra("MACHINE_ID") ?: return
                if (updatedMachineId == machineId) {
                    Log.d("WashingMachine", "Data update received for this machine")
                    updateUIFromMaps()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_washing_machine)

        // Get Machine ID and Name from Intent
        machineId = intent.getStringExtra("MACHINE_ID") ?: "machine_001"
        machineName = intent.getStringExtra("MACHINE_NAME") ?: "Washing Machine"

        // Get user email from SharedPreferences
        val sharedPref = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)
        userEmail = sharedPref.getString("USER_EMAIL", "") ?: ""

        // Set title
        supportActionBar?.title = machineName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        Log.d("WashingMachine", "Monitoring machine: $machineName ($machineId)")

        // Initialize Firebase
        val firebaseUrl = "https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/"
        database = FirebaseDatabase.getInstance(firebaseUrl).reference

        // Start service
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

        // Register broadcast receivers
        LocalBroadcastManager.getInstance(this).registerReceiver(
            cycleCompleteReceiver,
            IntentFilter(WashingMonitorService.ACTION_CYCLE_COMPLETE)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            machineDataReceiver,
            IntentFilter(WashingMonitorService.ACTION_MACHINE_DATA_UPDATED)
        )

        // Initial UI update from maps
        updateUIFromMaps()

        // Button listeners
        btnSetTimer.setOnClickListener { showTimerPickerDialog() }
        btnRefresh.setOnClickListener {
            updateUIFromMaps()
            Toast.makeText(this, "Status refreshed", Toast.LENGTH_SHORT).show()
        }
        btnReset.setOnClickListener {
            resetMachine()
        }

        startUIUpdater()
    }

    private fun updateUIFromMaps() {
        // READ FROM MAPS using this machine's ID (data comes from ESP32)
        val status = WashingMonitorService.machineStatusMap[machineId] ?: "idle"
        val vibration = WashingMonitorService.machineVibrationMap[machineId] ?: "⚪ NO VIBRATION"
        val timerValue = WashingMonitorService.machineTimerMap[machineId] ?: 120

        tvTimer.text = formatTime(timerValue)
        tvVibration.text = vibration

        when (status) {
            "running" -> {
                tvStatus.text = "🟢 WASHING IN PROGRESS"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
                cardStatus.setCardBackgroundColor(0xFFE8F5E9.toInt())
                tvTimer.setTextColor(0xFF4CAF50.toInt())
            }
            "finished" -> {
                tvStatus.text = "✅ CYCLE COMPLETE!"
                tvStatus.setTextColor(0xFF2196F3.toInt())
                cardStatus.setCardBackgroundColor(0xFFE3F2FD.toInt())
                tvTimer.setTextColor(0xFF2196F3.toInt())
            }
            "stopped" -> {
                tvStatus.text = "🟠 MACHINE PAUSED"
                tvStatus.setTextColor(0xFFFF9800.toInt())
                cardStatus.setCardBackgroundColor(0xFFFFF3E0.toInt())
                when {
                    timerValue <= 10 -> tvTimer.setTextColor(0xFFF44336.toInt())
                    timerValue <= 30 -> tvTimer.setTextColor(0xFFFF9800.toInt())
                    else -> tvTimer.setTextColor(0xFF2196F3.toInt())
                }
            }
            else -> {
                tvStatus.text = "⚪ IDLE"
                tvStatus.setTextColor(0xFF9E9E9E.toInt())
                cardStatus.setCardBackgroundColor(0xFFF5F5F5.toInt())
                tvTimer.setTextColor(0xFF9E9E9E.toInt())
            }
        }
    }

    private fun resetMachine() {
        // Send intent to service to stop timer for this machine
        val intent = Intent(this, WashingMonitorService::class.java).apply {
            action = "ACTION_RESET_MACHINE"
            putExtra("MACHINE_ID", machineId)
        }
        startService(intent)

        // Immediately update local UI
        WashingMonitorService.machineStatusMap[machineId] = "idle"
        WashingMonitorService.machineTimerMap[machineId] = 120
        updateUIFromMaps()
        Toast.makeText(this, "Machine reset!", Toast.LENGTH_SHORT).show()
    }

    private fun checkFirebaseData() {
        val testRef = database.child("washingMachines").child(machineId).child("status")
        testRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java) ?: "null"
                Log.d("WashingMachine", "Firebase status for $machineId: $status")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("WashingMachine", "Error: ${error.message}")
            }
        })
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
        val intent = Intent(this, MachineListActivity::class.java)
        intent.putExtra("USER_EMAIL", userEmail)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(uiRunnable!!)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cycleCompleteReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(machineDataReceiver)
    }

    private fun showFullScreenAlert() {
        Log.d("WashingMachine", "showFullScreenAlert() CALLED")

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

            // Save timer for THIS machine in Firebase
            database.child("washingMachines").child(machineId).child("timer").setValue(totalTimerSeconds)
                .addOnSuccessListener {
                    Log.d("WashingMachine", "Timer saved: $totalTimerSeconds seconds for $machineName")
                    // Tell service to update timer for this machine
                    val intent = Intent(this, WashingMonitorService::class.java).apply {
                        action = "ACTION_SET_TIMER"
                        putExtra("TIMER_SECONDS", totalTimerSeconds)
                        putExtra("MACHINE_ID", machineId)
                    }
                    startService(intent)
                    WashingMonitorService.machineTimerMap[machineId] = totalTimerSeconds
                    updateUIFromMaps()
                }
                .addOnFailureListener { e -> Log.e("WashingMachine", "Timer save failed: ${e.message}") }

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
                updateUIFromMaps()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(uiRunnable!!)
    }

    private fun formatTime(seconds: Int) = String.format("%02d:%02d", seconds / 60, seconds % 60)

    override fun onResume() {
        super.onResume()
        ForegroundTracker.currentActivity = "WashingMachineActivity"
        updateUIFromMaps()
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