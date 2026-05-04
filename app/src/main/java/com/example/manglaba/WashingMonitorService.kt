package com.example.manglaba

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlin.math.abs

class WashingMonitorService : Service() {

    private lateinit var database: DatabaseReference
    private val handler = Handler(Looper.getMainLooper())

    // Store timer data for EACH machine independently
    private val machineTimers = mutableMapOf<String, MachineTimerData>()

    // Original timer variables (kept for backward compatibility)
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
        const val ACTION_MACHINE_DATA_UPDATED = "MACHINE_DATA_UPDATED"

        var currentTimerSeconds = 120
        var isTimerRunning = false
        var isMachineRunning = false
        var currentVibrationStatus = "⚪ NO VIBRATION"
        var currentMachineStatus = "⚪ IDLE"
        var dialogShownForCurrentCycle = false
        var isUserLoggedIn = false
        var currentAlertNotificationId = NOTIFICATION_ID + 1

        // Current machine ID
        var currentMachineId = "machine_001"

        // Multi-machine storage (keeps data for all machines)
        val machineStatusMap = mutableMapOf<String, String>()
        val machineVibrationMap = mutableMapOf<String, String>()
        val machineTimerMap = mutableMapOf<String, Int>()
        val machineNameMap = mutableMapOf<String, String>()

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

            context.stopService(Intent(context, WashingMonitorService::class.java))

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancelAll()
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(NOTIFICATION_ID + 1)
        }
    }

    // Data class for multi-machine timer storage
    data class MachineTimerData(
        var timerRunning: Boolean = false,
        var remainingSeconds: Int = 120,
        var startTime: Long = 0,
        var totalSeconds: Int = 120,
        var stopTime: Long = 0,
        var machineRunning: Boolean = false,
        var vibrationValue: Int = 0,
        var cycleCompleteNotified: Boolean = false,
        var timerRunnable: Runnable? = null
    )

    fun forceStop(context: Context) {
        isUserLoggedIn = false
        dialogShownForCurrentCycle = false
        currentTimerSeconds = 120
        isTimerRunning = false
        isMachineRunning = false
        currentMachineStatus = "⚪ IDLE"
        currentVibrationStatus = "⚪ NO VIBRATION"

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
        Log.d("WashingMonitor", "Service created for machine: $currentMachineId")

        database = FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Listen to ALL machines
        listenToAllMachines()

        // Keep original listeners
        listenToVibration()
        listenToStatus()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            stopForegroundReceiver,
            IntentFilter("STOP_FOREGROUND")
        )
    }

    // Listen to all machines for multi-machine support
    private fun listenToAllMachines() {
        database.child("washingMachines").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isUserLoggedIn) return

                for (machineSnapshot in snapshot.children) {
                    val machineId = machineSnapshot.key ?: continue
                    val status = machineSnapshot.child("status").getValue(String::class.java) ?: "idle"
                    val vibration = machineSnapshot.child("currentVibration").child("value").getValue(Int::class.java) ?: 0
                    val timerValue = machineSnapshot.child("timer").getValue(Int::class.java) ?: 120
                    val machineName = machineSnapshot.child("name").getValue(String::class.java) ?: machineId

                    // Update stored data for this machine
                    machineStatusMap[machineId] = status
                    machineVibrationMap[machineId] = if (vibration == 1) "🔴 VIBRATION DETECTED!" else "⚪ NO VIBRATION"
                    machineTimerMap[machineId] = timerValue
                    machineNameMap[machineId] = machineName

                    // Process timer logic for this machine independently
                    processMachineTimer(machineId, status, vibration, timerValue)

                    // Update notification for this specific machine
                    updateNotificationForMachine(machineId)

                    // Send broadcast for this specific machine
                    val intent = Intent(ACTION_MACHINE_DATA_UPDATED)
                    intent.putExtra("MACHINE_ID", machineId)
                    LocalBroadcastManager.getInstance(this@WashingMonitorService).sendBroadcast(intent)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("WashingMonitor", "Error listening to machines: ${error.message}")
            }
        })
    }

    // Update notification for a specific machine
    private fun updateNotificationForMachine(machineId: String) {
        val status = machineStatusMap[machineId] ?: "idle"
        val timerValue = machineTimerMap[machineId] ?: 120
        val machineName = machineNameMap[machineId] ?: machineId

        val statusText = when (status) {
            "running" -> "🟢 WASHING IN PROGRESS"
            "stopped" -> "⏱️ Timer: ${formatTime(timerValue)}"
            "finished" -> "✅ CYCLE COMPLETE!"
            else -> "⚪ IDLE"
        }

        // Use machine-specific notification ID
        val notificationId = NOTIFICATION_ID + abs(machineId.hashCode()) % 1000

        val intent = Intent(this, WashingMachineActivity::class.java).apply {
            putExtra("MACHINE_ID", machineId)
            putExtra("MACHINE_NAME", machineName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, machineId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🧺 $machineName")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(status == "stopped" || status == "running")
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }

    // Process each machine's timer independently
    private fun processMachineTimer(machineId: String, status: String, vibration: Int, timerSeconds: Int) {
        val timerData = machineTimers.getOrPut(machineId) { MachineTimerData() }

        when (status) {
            "running" -> {
                timerData.machineRunning = true
                timerData.timerRunning = false
                timerData.stopTime = 0
                timerData.cycleCompleteNotified = false
                Log.d("WashingMonitor", "Machine $machineId is RUNNING")
            }
            "stopped" -> {
                if (timerData.machineRunning && !timerData.timerRunning && vibration == 0) {
                    Log.d("WashingMonitor", "Machine $machineId STOPPED - Starting timer")
                    timerData.timerRunning = true
                    timerData.startTime = System.currentTimeMillis()
                    timerData.totalSeconds = timerSeconds
                    timerData.remainingSeconds = timerSeconds
                    startMachineTimer(machineId, timerData)
                }
            }
            "finished" -> {
                if (!timerData.cycleCompleteNotified) {
                    timerData.cycleCompleteNotified = true
                    Log.d("WashingMonitor", "Machine $machineId FINISHED - Showing alert")
                    showAlertForMachine(machineId)
                }
                timerData.machineRunning = false
                timerData.timerRunning = false
            }
            "idle" -> {
                timerData.machineRunning = false
                timerData.timerRunning = false
                timerData.stopTime = 0
                timerData.cycleCompleteNotified = false
                Log.d("WashingMonitor", "Machine $machineId is IDLE")
            }
        }
    }

    // Start timer for a specific machine
    private fun startMachineTimer(machineId: String, timerData: MachineTimerData) {
        timerData.timerRunnable?.let { handler.removeCallbacks(it) }

        timerData.timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - timerData.startTime) / 1000
                val remaining = timerData.totalSeconds - elapsed.toInt()

                if (remaining <= 0) {
                    Log.d("WashingMonitor", "Machine $machineId timer FINISHED!")
                    timerData.timerRunning = false
                    database.child("washingMachines").child(machineId).child("status").setValue("finished")
                    machineStatusMap[machineId] = "finished"
                    machineTimerMap[machineId] = 0
                    updateNotificationForMachine(machineId)

                    val intent = Intent(ACTION_MACHINE_DATA_UPDATED)
                    intent.putExtra("MACHINE_ID", machineId)
                    LocalBroadcastManager.getInstance(this@WashingMonitorService).sendBroadcast(intent)
                } else {
                    timerData.remainingSeconds = remaining
                    machineTimerMap[machineId] = remaining
                    updateNotificationForMachine(machineId)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(timerData.timerRunnable!!)
    }

    // Show alert for a specific machine
    private fun showAlertForMachine(machineId: String) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        val intent = Intent(ACTION_CYCLE_COMPLETE)
        intent.putExtra("MACHINE_ID", machineId)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        val machineName = machineNameMap[machineId] ?: machineId
        val notificationIntent = Intent(this, WashingMachineActivity::class.java).apply {
            putExtra("MACHINE_ID", machineId)
            putExtra("MACHINE_NAME", machineName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, machineId.hashCode(), notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "${CHANNEL_ID}_alert")
            .setContentTitle("🧺 Laundry Done!")
            .setContentText("$machineName - Your washing cycle is complete!")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 100 + abs(machineId.hashCode()) % 1000, notification)
    }

    private fun resetMachineTimer(machineId: String) {
        val timerData = machineTimers[machineId]
        if (timerData != null) {
            // Stop the running timer
            timerData.timerRunnable?.let { handler.removeCallbacks(it) }
            timerData.timerRunning = false
            timerData.machineRunning = false
            timerData.remainingSeconds = 120
            timerData.totalSeconds = 120
            timerData.cycleCompleteNotified = false
            timerData.stopTime = 0
        }

        // Update maps
        machineTimerMap[machineId] = 120
        machineStatusMap[machineId] = "idle"

        // Update Firebase
        val updates = mapOf<String, Any>(
            "status" to "idle",
            "intensity" to 0,
            "timer" to 120,
            "lastUpdate" to System.currentTimeMillis()
        )
        database.child("washingMachines").child(machineId).updateChildren(updates)

        // Update notification
        updateNotificationForMachine(machineId)

        // Send broadcast to update UI
        val intent = Intent(ACTION_MACHINE_DATA_UPDATED)
        intent.putExtra("MACHINE_ID", machineId)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        Log.d("WashingMonitor", "Machine $machineId reset - timer stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isUserLoggedIn) {
            Log.d("WashingMonitor", "User not logged in - stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            "ACTION_RESET_MACHINE" -> {
                val machineId = intent.getStringExtra("MACHINE_ID") ?: return START_STICKY
                resetMachineTimer(machineId)
            }
            "ACTION_SET_TIMER" -> {
                val seconds = intent.getIntExtra("TIMER_SECONDS", 120)
                val machineId = intent.getStringExtra("MACHINE_ID") ?: currentMachineId
                // Update the timer for this specific machine
                val timerData = machineTimers.getOrPut(machineId) { MachineTimerData() }
                timerData.totalSeconds = seconds
                timerData.remainingSeconds = seconds
                machineTimerMap[machineId] = seconds
                updateNotificationForMachine(machineId)
                Log.d("WashingMonitor", "Timer set to $seconds seconds for machine $machineId")
            }
        }
        return START_NOT_STICKY
    }

    private fun saveTimerToFirebase(seconds: Int) {
        val timerRef = database.child("washingMachines").child(currentMachineId).child("timer")
        timerRef.setValue(seconds)
            .addOnSuccessListener {
                Log.d("WashingMonitor", "Timer saved to Firebase: $seconds seconds for machine $currentMachineId")
            }
            .addOnFailureListener {
                Log.e("WashingMonitor", "Failed to save timer: ${it.message}")
            }
    }

    private fun listenToVibration() {
        database.child("washingMachines").child(currentMachineId).child("currentVibration").child("value")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isUserLoggedIn) return
                    val newValue = snapshot.getValue(Int::class.java) ?: 0
                    vibrationValue = newValue
                    Log.d("WashingMonitor", "Vibration for $currentMachineId: $newValue")

                    machineVibrationMap[currentMachineId] = if (newValue == 1) "🔴 VIBRATION DETECTED!" else "⚪ NO VIBRATION"
                    currentVibrationStatus = machineVibrationMap[currentMachineId] ?: "⚪ NO VIBRATION"

                    // Get current status from Firebase
                    val currentStatus = machineStatusMap[currentMachineId] ?: "idle"

                    if (newValue == 1) {
                        // VIBRATION DETECTED - Machine is running
                        dialogShownForCurrentCycle = false
                        cycleCompleteNotified = false

                        // ALWAYS stop timer when vibration is detected
                        if (timerRunning) {
                            stopTimer()
                        }
                        // Ensure timer cannot run
                        timerRunning = false
                        isTimerRunning = false

                        machineRunning = true
                        isMachineRunning = true
                        currentMachineStatus = "🟢 WASHING IN PROGRESS"

                        Log.d("WashingMonitor", "Vibration detected - Timer STOPPED, machine RUNNING")

                    } else {
                        // NO VIBRATION
                        currentVibrationStatus = "⚪ NO VIBRATION"

                        // Only start timer if: machine was running AND timer not running AND status is "stopped"
                        if (machineRunning && !timerRunning && newValue == 0 && currentStatus == "stopped") {
                            currentMachineStatus = "🟠 MACHINE PAUSED"
                            startTimer()
                            Log.d("WashingMonitor", "No vibration & status stopped - Timer STARTED")
                        } else if (machineRunning && newValue == 0 && timerRunning) {
                            currentMachineStatus = "🟠 MACHINE PAUSED"
                            Log.d("WashingMonitor", "No vibration - Timer already running")
                        } else if (currentStatus == "running") {
                            // Safety: if status says running but we're here, ensure timer is stopped
                            if (timerRunning) {
                                stopTimer()
                                Log.d("WashingMonitor", "Safety: Status is running but timer was running - forced stop")
                            }
                        }
                    }
                    updateNotificationForMachine(currentMachineId)
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun listenToStatus() {
        database.child("washingMachines").child(currentMachineId).child("status")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isUserLoggedIn) return
                    val status = snapshot.getValue(String::class.java) ?: "idle"
                    machineStatusMap[currentMachineId] = status
                    Log.d("WashingMonitor", "Status for $currentMachineId: $status")

                    currentMachineStatus = when (status) {
                        "running" -> "🟢 WASHING IN PROGRESS"
                        "finished" -> "✅ CYCLE COMPLETE!"
                        "stopped" -> "🟠 MACHINE PAUSED"
                        else -> "⚪ IDLE"
                    }

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

                            totalTimerSeconds = 120
                            remainingSeconds = 120
                            currentTimerSeconds = 120
                            Log.d("WashingMonitor", "Timer reset to 2 minutes due to logout")
                        }
                    }
                    updateNotificationForMachine(currentMachineId)
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

        Log.d("WashingMonitor", "⏱️ Timer STARTED in service for machine $currentMachineId")

        timerRunnable = object : Runnable {
            override fun run() {
                if (!timerRunning) return
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                remainingSeconds = (totalTimerSeconds - elapsed).toInt()
                currentTimerSeconds = remainingSeconds

                Log.d("WashingMonitor", "⏱️ Service timer for $currentMachineId: $remainingSeconds seconds left")

                if (vibrationValue == 1) {
                    stopTimer()
                    machineRunning = true
                    isMachineRunning = true
                    currentMachineStatus = "🟢 WASHING IN PROGRESS"
                    updateNotificationForMachine(currentMachineId)
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
                    updateNotificationForMachine(currentMachineId)
                } else {
                    updateNotificationForMachine(currentMachineId)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(timerRunnable!!)
        updateNotificationForMachine(currentMachineId)
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
        database.child("washingMachines").child(currentMachineId).updateChildren(updates)
        updateNotificationForMachine(currentMachineId)
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
        Log.d("WashingMonitor", "Cycle complete for machine $currentMachineId")

        if (!isUserLoggedIn) {
            Log.d("WashingMonitor", "User not logged in - skipping notification")
            return
        }

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        val broadcastIntent = Intent(ACTION_CYCLE_COMPLETE)
        broadcastIntent.putExtra("MACHINE_ID", currentMachineId)
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

        if (shouldShowFullScreenNotification()) {
            val machineName = machineNameMap[currentMachineId] ?: currentMachineId
            val intent = Intent(this, WashingMachineActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_CYCLE_COMPLETE_DIALOG", true)
                putExtra("MACHINE_ID", currentMachineId)
                putExtra("MACHINE_NAME", machineName)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, currentMachineId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, "${CHANNEL_ID}_alert")
                .setContentTitle("🧺 Laundry Done!")
                .setContentText("$machineName - Your washing cycle is complete!")
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
        database.child("washingMachines").child(currentMachineId).updateChildren(
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
        // Keep for compatibility, but we use updateNotificationForMachine now
        updateNotificationForMachine(currentMachineId)
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
        stopForeground(true)
        stopTimer()
        handler.removeCallbacksAndMessages(null)

        // Clear multi-machine timers
        machineTimers.clear()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(NOTIFICATION_ID + 1)

        Log.d("WashingMonitor", "Service destroyed for machine $currentMachineId")
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
            updateNotificationForMachine(currentMachineId)
        }
    }
}