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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import android.content.BroadcastReceiver
import android.content.IntentFilter

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
        const val ACTION_CYCLE_COMPLETE = "CYCLE_COMPLETE"

        var currentTimerSeconds = 120
        var isTimerRunning = false
        var isMachineRunning = false
        var currentVibrationStatus = "⚪ NO VIBRATION"
        var currentMachineStatus = "⚪ IDLE"
        var dialogShownForCurrentCycle = false
        var isUserLoggedIn = false
        var currentAlertNotificationId = NOTIFICATION_ID + 1

        fun resetCompanion() {
            currentTimerSeconds = 120
            isTimerRunning = false
            isMachineRunning = false
            currentVibrationStatus = "⚪ NO VIBRATION"
            currentMachineStatus = "⚪ IDLE"
        }
        fun forceStopService(context: Context) {
            isUserLoggedIn = false
            dialogShownForCurrentCycle = false
            currentTimerSeconds = 120
            isTimerRunning = false
            isMachineRunning = false
            currentMachineStatus = "⚪ IDLE"
            currentVibrationStatus = "⚪ NO VIBRATION"

            // Stop the service
            context.stopService(Intent(context, WashingMonitorService::class.java))

            // Cancel all notifications
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancelAll()
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(NOTIFICATION_ID + 1)
        }
    }
    fun forceStop(context: Context) {
        isUserLoggedIn = false
        dialogShownForCurrentCycle = false
        currentTimerSeconds = 120
        isTimerRunning = false
        isMachineRunning = false
        currentMachineStatus = "⚪ IDLE"
        currentVibrationStatus = "⚪ NO VIBRATION"

        // Send broadcast to stop foreground
        val intent = Intent("STOP_FOREGROUND")
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

        context.stopService(Intent(context, WashingMonitorService::class.java))

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }


    private val stopForegroundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "STOP_FOREGROUND") {
                stopForeground(true)
                stopSelf()
            }
        }
    }
    fun stopServiceNow() {
        stopForeground(true)
        stopTimer()
        stopSelf()
    }
    override fun onCreate() {
        super.onCreate()
        resetCompanion()
        Log.d("WashingMonitor", "Service created")

        database =
            FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        listenToVibration()
        listenToStatus()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            stopForegroundReceiver,
            IntentFilter("STOP_FOREGROUND")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If user is not logged in, stop service immediately
        if (!isUserLoggedIn) {
            Log.d("WashingMonitor", "User not logged in - stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            "ACTION_RESET" -> resetEverything()
            "ACTION_SET_TIMER" -> {
                val seconds = intent.getIntExtra("TIMER_SECONDS", 120)
                totalTimerSeconds = seconds
                remainingSeconds = seconds
                currentTimerSeconds = seconds
                saveTimerToFirebase(seconds)
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }
    private fun saveTimerToFirebase(seconds: Int) {
        val timerRef = database.child("washingMachine").child("setTimer")
        timerRef.setValue(seconds)
            .addOnSuccessListener {
                Log.d("WashingMonitor", "Timer saved to Firebase: $seconds seconds")
            }
            .addOnFailureListener {
                Log.e("WashingMonitor", "Failed to save timer: ${it.message}")
            }
    }


    private fun listenToVibration() {
        database.child("washingMachine").child("currentVibration").child("value")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isUserLoggedIn) return
                    val newValue = snapshot.getValue(Int::class.java) ?: 0
                    vibrationValue = newValue
                    Log.d("WashingMonitor", "Vibration: $newValue")

                    if (newValue == 1) {
                        dialogShownForCurrentCycle = false
                        currentVibrationStatus = "🔴 VIBRATION DETECTED!"
                        cycleCompleteNotified = false

                        if (timerRunning) {
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
                            currentMachineStatus = "🟠 MACHINE PAUSED"
                            startTimer()
                        } else if (machineRunning && newValue == 0 && timerRunning) {
                            currentMachineStatus = "🟠 MACHINE PAUSED"
                        }
                    }
                    updateNotification()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun listenToStatus() {
        database.child("washingMachine").child("status")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isUserLoggedIn) return
                    val status = snapshot.getValue(String::class.java) ?: "idle"
                    when (status) {
                        "running" -> {
                            cycleCompleteNotified = false
                            if (timerRunning) stopTimer()
                            machineRunning = true
                            isMachineRunning = true
                            currentMachineStatus = "🟢 WASHING IN PROGRESS"
                        }

                        "stopped" -> {
                            currentMachineStatus = "🟠 MACHINE PAUSED"
                            if (machineRunning && !timerRunning && vibrationValue == 0) startTimer()
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

                            // RESET TIMER TO DEFAULT WHEN LOGGED OUT
                            totalTimerSeconds = 120
                            remainingSeconds = 120
                            currentTimerSeconds = 120
                            Log.d("WashingMonitor", "Timer reset to 2 minutes due to logout")
                        }
                    }
                    updateNotification()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun startTimer() {
        if (timerRunning) return
        timerRunning = true
        isTimerRunning = true
        remainingSeconds = totalTimerSeconds
        currentTimerSeconds = remainingSeconds
        startTime = System.currentTimeMillis()

        Log.d("WashingMonitor", "⏱️ Timer STARTED in service")

        timerRunnable = object : Runnable {
            override fun run() {
                if (!timerRunning) return
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                remainingSeconds = (totalTimerSeconds - elapsed).toInt()
                currentTimerSeconds = remainingSeconds

                Log.d("WashingMonitor", "⏱️ Service timer: $remainingSeconds seconds left")

                if (vibrationValue == 1) {
                    stopTimer()
                    machineRunning = true
                    isMachineRunning = true
                    currentMachineStatus = "🟢 WASHING IN PROGRESS"
                    updateNotification()
                    return
                }

                if (remainingSeconds <= 0) {
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
        updateNotification()
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
    }

    private fun shouldShowFullScreenNotification(): Boolean {
        val currentActivity = ForegroundTracker.currentActivity
        return currentActivity == null || (
                currentActivity != "LoginActivity" &&
                        currentActivity != "RegisterActivity" &&
                        currentActivity != "ForgotPasswordActivity" &&
                        currentActivity != "LaundryReadyActivity"
                )
    }

    private fun showAlertAndDialog() {
        Log.d("WashingMonitor", "Cycle complete")

        // Don't proceed if user is not logged in
        if (!isUserLoggedIn) {
            Log.d("WashingMonitor", "User not logged in - skipping notification")
            return
        }

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        val broadcastIntent = Intent(ACTION_CYCLE_COMPLETE)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

        if (shouldShowFullScreenNotification()) {
            val intent = Intent(this, WashingMachineActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_CYCLE_COMPLETE_DIALOG", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, "${CHANNEL_ID}_alert")
                .setContentTitle("🧺 Laundry Done!")
                .setContentText("Your washing cycle is complete! Time to take out your laundry.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            currentAlertNotificationId++
            manager.notify(NOTIFICATION_ID + 1, notification)
        } else {
            Log.d(
                "WashingMonitor",
                "Full-screen notification suppressed on ${ForegroundTracker.currentActivity}"
            )
        }
    }

    private fun updateFirebaseFinished() {
        database.child("washingMachine").updateChildren(
            mapOf(
                "status" to "finished",
                "intensity" to 0,
                "lastUpdate" to System.currentTimeMillis()
            )
        )
        database.child("notifications").push().setValue(
            mapOf(
                "title" to "Laundry Done!",
                "message" to "Your washing cycle is complete!",
                "timestamp" to System.currentTimeMillis(),
                "read" to false
            )
        )
    }

    private fun updateNotification() {
        val statusText = when {
            timerRunning -> "⏱️ Timer: ${formatTime(currentTimerSeconds)}"
            machineRunning -> "🟢 Washing in progress..."
            else -> "⚪ Idle"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(statusText))
    }

    private fun createNotification(contentText: String = "Monitoring..."): Notification {
        val intent = Intent(this, WashingMachineActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Washing Machine Monitor",
                NotificationManager.IMPORTANCE_LOW
            )

            val alertChannel = NotificationChannel(
                "${CHANNEL_ID}_alert",
                "Washing Machine Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            alertChannel.setBypassDnd(true)

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)

            Log.d("WashingMonitor", "Notification channels created")
        }
    }


    private fun formatTime(seconds: Int) = String.format("%02d:%02d", seconds / 60, seconds % 60)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        // Stop foreground FIRST - this removes the notification
        stopForeground(true)

        // Then clean up everything else
        stopTimer()
        handler.removeCallbacksAndMessages(null)

        // Cancel any remaining notifications
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(NOTIFICATION_ID + 1)

        Log.d("WashingMonitor", "Service destroyed")
    }
    fun forceStopService(context: Context) {
        isUserLoggedIn = false
        dialogShownForCurrentCycle = false
        currentTimerSeconds = 120
        isTimerRunning = false
        isMachineRunning = false
        currentMachineStatus = "⚪ IDLE"
        currentVibrationStatus = "⚪ NO VIBRATION"

        context.stopService(Intent(context, WashingMonitorService::class.java))

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }

    private fun stopTimer() {
        if (timerRunning) {
            timerRunning = false
            isTimerRunning = false
            timerRunnable?.let { handler.removeCallbacks(it) }
            timerRunnable = null
            updateNotification()
        }
    }

}