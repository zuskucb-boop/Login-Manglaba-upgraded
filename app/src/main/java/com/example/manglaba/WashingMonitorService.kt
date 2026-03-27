package com.example.manglaba

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*

class WashingMonitorService : Service() {

    private lateinit var database: DatabaseReference
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var timerRunning = false
    private var machineRunning = false
    private var vibrationValue = 0
    private var remainingSeconds = 120
    private var totalTimerSeconds = 120
    private var startTime = 0L
    private var cycleCompleteNotified = false

    companion object {
        const val CHANNEL_ID = "washing_monitor"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CYCLE_COMPLETE = "CYCLE_COMPLETE"  // ← MAKE SURE THIS LINE EXISTS!

        var currentTimerSeconds = 120
        var isTimerRunning = false
        var isMachineRunning = false
        var currentVibrationStatus = "⚪ NO VIBRATION"
        var currentMachineStatus = "⚪ IDLE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("WashingMonitor", "Service started")

        database = FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        listenToVibration()
        listenToStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_RESET" -> {
                resetEverything()
            }
            "ACTION_SET_TIMER" -> {
                val seconds = intent.getIntExtra("TIMER_SECONDS", 120)
                totalTimerSeconds = seconds
                remainingSeconds = seconds
                currentTimerSeconds = seconds
                updateNotification()
            }
        }
        return START_STICKY
    }

    private fun listenToVibration() {
        Log.d("WashingMonitor", "Starting FAST vibration listener...")

        database.child("washingMachine").child("currentVibration").child("value")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newValue = snapshot.getValue(Int::class.java) ?: 0
                    vibrationValue = newValue

                    Log.d("WashingMonitor", "🔥 VIBRATION: $newValue")

                    // Update UI IMMEDIATELY
                    if (newValue == 1) {
                        currentVibrationStatus = "🔴 VIBRATION DETECTED!"
                        Log.d("WashingMonitor", "✅ Vibration status updated")

                        cycleCompleteNotified = false

                        if (timerRunning) {
                            Log.d("WashingMonitor", "🔄 RESETTING TIMER")
                            stopTimer()
                            machineRunning = true
                            isMachineRunning = true
                            currentMachineStatus = "🟢 WASHING IN PROGRESS"
                        } else if (!machineRunning) {
                            machineRunning = true
                            isMachineRunning = true
                            currentMachineStatus = "🟢 WASHING IN PROGRESS"
                        }
                    } else {
                        currentVibrationStatus = "⚪ NO VIBRATION"

                        if (machineRunning && !timerRunning && newValue == 0) {
                            Log.d("WashingMonitor", "⏱️ Starting timer")
                            startTimer()
                        }
                    }
                    updateNotification()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("WashingMonitor", "❌ Firebase error: ${error.message}")
                }
            })
    }

    private fun listenToStatus() {
        database.child("washingMachine").child("status")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.getValue(String::class.java) ?: "idle"
                    Log.d("WashingMonitor", "Status: $status")

                    when (status) {
                        "running" -> {
                            cycleCompleteNotified = false
                            if (timerRunning) stopTimer()
                            machineRunning = true
                            isMachineRunning = true
                            currentMachineStatus = "🟢 WASHING IN PROGRESS"
                        }
                        "stopped" -> {
                            if (machineRunning && !timerRunning && vibrationValue == 0) {
                                startTimer()
                            }
                            currentMachineStatus = "⏸️ MACHINE PAUSED"
                        }
                        "finished" -> {
                            machineRunning = false
                            isMachineRunning = false
                            if (!cycleCompleteNotified) {
                                cycleCompleteNotified = true
                                showAlertAndDialog()
                            }
                            stopTimer()
                            currentMachineStatus = "✅ CYCLE COMPLETE!"
                        }
                        "idle" -> {
                            machineRunning = false
                            isMachineRunning = false
                            stopTimer()
                            currentMachineStatus = "⚪ IDLE"
                        }
                    }
                    updateNotification()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("WashingMonitor", "Status error: ${error.message}")
                }
            })
    }

    private fun startTimer() {
        if (timerRunning) return

        timerRunning = true
        isTimerRunning = true
        remainingSeconds = totalTimerSeconds
        currentTimerSeconds = remainingSeconds
        startTime = System.currentTimeMillis()

        Log.d("WashingMonitor", "Timer started: ${remainingSeconds/60} minutes")
        updateNotification()

        timerRunnable = object : Runnable {
            override fun run() {
                if (!timerRunning) return

                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                remainingSeconds = (totalTimerSeconds - elapsed).toInt()
                currentTimerSeconds = remainingSeconds

                if (vibrationValue == 1) {
                    Log.d("WashingMonitor", "Vibration during timer - Resetting")
                    stopTimer()
                    machineRunning = true
                    isMachineRunning = true
                    currentMachineStatus = "🟢 WASHING IN PROGRESS"
                    updateNotification()
                    return
                }

                if (remainingSeconds <= 0) {
                    Log.d("WashingMonitor", "Timer finished!")
                    stopTimer()
                    machineRunning = false
                    isMachineRunning = false
                    if (!cycleCompleteNotified) {
                        cycleCompleteNotified = true
                        showAlertAndDialog()
                    }
                    updateFirebaseFinished()
                    currentMachineStatus = "✅ CYCLE COMPLETE!"
                    updateNotification()
                } else {
                    updateNotification()
                    handler.postDelayed(this, 1000)
                }
            }
        }

        handler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        if (timerRunning) {
            timerRunning = false
            isTimerRunning = false
            handler.removeCallbacks(timerRunnable!!)
            Log.d("WashingMonitor", "Timer stopped")
            updateNotification()
        }
    }

    private fun resetEverything() {
        stopTimer()
        machineRunning = false
        isMachineRunning = false
        cycleCompleteNotified = false
        vibrationValue = 0
        remainingSeconds = totalTimerSeconds
        currentTimerSeconds = totalTimerSeconds
        currentMachineStatus = "⚪ IDLE"
        currentVibrationStatus = "⚪ NO VIBRATION"

        val updates = mapOf<String, Any>(
            "status" to "idle",
            "intensity" to 0,
            "lastUpdate" to System.currentTimeMillis()
        )
        database.child("washingMachine").updateChildren(updates)

        updateNotification()
        Log.d("WashingMonitor", "Everything reset")
    }

    private fun showAlertAndDialog() {
        Log.d("WashingMonitor", "Showing alert and dialog!")

        // Vibrate
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            val vibrationEffect = VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(vibrationEffect)
        }

        // Show full-screen dialog using an Intent to start a transparent activity
        val dialogIntent = Intent(this, DialogActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(dialogIntent)
        Log.d("WashingMonitor", "Started DialogActivity to show full-screen dialog")

        // Also show notification
        val notificationIntent = Intent(this, LaundryReadyActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🧺 Laundry Done!")
            .setContentText("Your washing cycle is complete! Time to take out your laundry.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun updateFirebaseFinished() {
        val updates = mapOf<String, Any>(
            "status" to "finished",
            "intensity" to 0,
            "lastUpdate" to System.currentTimeMillis()
        )
        database.child("washingMachine").updateChildren(updates)

        val notification = mapOf<String, Any>(
            "title" to "Laundry Done!",
            "message" to "Your washing cycle is complete!",
            "timestamp" to System.currentTimeMillis(),
            "read" to false
        )
        database.child("notifications").push().setValue(notification)
    }

    private fun updateNotification() {
        val statusText = when {
            timerRunning -> "⏱️ Timer: ${formatTime(currentTimerSeconds)}"
            machineRunning -> "🟢 Washing in progress..."
            else -> "⚪ Idle"
        }

        val notification = createNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(contentText: String = "Monitoring..."): Notification {
        val intent = Intent(this, LaundryReadyActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🧺 Washing Machine Monitor")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Washing Machine Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        handler.removeCallbacksAndMessages(null)
        Log.d("WashingMonitor", "Service destroyed")
    }
}