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

    private val machineTimers = mutableMapOf<String, MachineTimerData>()
    private val machinesWithESP32 = mutableSetOf<String>()
    private var heartbeatRunnable: Runnable? = null
    private var firebaseListener: ValueEventListener? = null

    companion object {
        const val CHANNEL_ID = "washing_monitor"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CYCLE_COMPLETE = "CYCLE_COMPLETE"
        const val ACTION_MACHINE_DATA_UPDATED = "MACHINE_DATA_UPDATED"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

        var currentTimerSeconds = 120
        var isTimerRunning = false
        var isMachineRunning = false
        var currentVibrationStatus = "⚪ NO VIBRATION"
        var currentMachineStatus = "⚪ IDLE"
        var dialogShownForCurrentCycle = false
        var isUserLoggedIn = false
        var currentAlertNotificationId = NOTIFICATION_ID + 1
        var currentMachineId = "machine_001"

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

    data class MachineTimerData(
        var timerRunning: Boolean = false,
        var remainingSeconds: Int = 120,
        var startTime: Long = 0,
        var totalSeconds: Int = 120,
        var machineRunning: Boolean = false,
        var vibrationValue: Int = 0,
        var cycleCompleteNotified: Boolean = false,
        var timerRunnable: Runnable? = null,
        var hasESP32Confirmed: Boolean = false
    )

    fun forceStop(context: Context) {
        performFullCleanup()
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
        performFullCleanup()
        stopForeground(true)
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()


        handler.removeCallbacksAndMessages(null)
        machineTimers.clear()
        machinesWithESP32.clear()
        resetCompanion()
        Log.d("WashingMonitor", "Multi-machine service created")

        database = FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())


        database.child("washingMachines").child(currentMachineId)
            .child("appConnected")
            .onDisconnect()
            .setValue(false)


        database.child("washingMachines").child(currentMachineId)
            .updateChildren(mapOf(
                "status" to "idle",
                "appConnected" to false,
                "intensity" to 0,
                "timer" to 120,
                "lastUpdate" to System.currentTimeMillis()
            ))


        handler.postDelayed({

            setAppConnected(true)
            startHeartbeat()
        }, 1000)


        listenToAllMachines()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            stopForegroundReceiver,
            IntentFilter("STOP_FOREGROUND")
        )
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("WashingMonitor", "⚠️ App removed from background/recents!")
        performFullCleanup()
        stopForeground(true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }


    private fun performFullCleanup() {
        Log.d("WashingMonitor", "🧹 Performing full cleanup...")

        isUserLoggedIn = false
        stopHeartbeat()


        machineTimers.forEach { (_, timerData) ->
            timerData.timerRunnable?.let { handler.removeCallbacks(it) }
            timerData.timerRunning = false
            timerData.machineRunning = false
        }
        machineTimers.clear()
        handler.removeCallbacksAndMessages(null)


        database.child("washingMachines").child(currentMachineId)
            .updateChildren(mapOf(
                "status" to "idle",
                "appConnected" to false,
                "intensity" to 0,
                "timer" to 120,
                "lastUpdate" to System.currentTimeMillis()
            ))

        resetCompanion()
        dialogShownForCurrentCycle = false


        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(NOTIFICATION_ID + 1)

        Log.d("WashingMonitor", "✅ Full cleanup complete")
    }


    private fun setAppConnected(connected: Boolean) {
        database.child("washingMachines").child(currentMachineId)
            .child("appConnected")
            .setValue(connected)
            .addOnSuccessListener {
                Log.d("WashingMonitor", if (connected) "📱 App CONNECTED" else "📱 App DISCONNECTED")
            }
            .addOnFailureListener { e ->
                Log.e("WashingMonitor", "❌ Failed to set appConnected: ${e.message}")
            }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isUserLoggedIn) {
                    database.child("washingMachines").child(currentMachineId)
                        .child("appConnected")
                        .setValue(true)
                        .addOnFailureListener { e ->
                            Log.e("WashingMonitor", "❌ Heartbeat failed: ${e.message}")
                        }
                    handler.postDelayed(this, 3000)
                }
            }
        }
        handler.post(heartbeatRunnable!!)
        Log.d("WashingMonitor", "💓 App heartbeat started")
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
        Log.d("WashingMonitor", "💔 App heartbeat stopped")
    }


    private fun listenToAllMachines() {
        firebaseListener?.let { database.child("washingMachines").removeEventListener(it) }

        firebaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isUserLoggedIn) return

                for (machineSnapshot in snapshot.children) {
                    val machineId = machineSnapshot.key ?: continue
                    val status = machineSnapshot.child("status").getValue(String::class.java) ?: "idle"
                    val vibration = machineSnapshot.child("currentVibration").child("value").getValue(Int::class.java) ?: 0
                    val timerValue = machineSnapshot.child("timer").getValue(Int::class.java) ?: 120
                    val machineName = machineSnapshot.child("name").getValue(String::class.java) ?: machineId


                    machineStatusMap[machineId] = status
                    machineVibrationMap[machineId] = if (vibration == 1) "🔴 VIBRATION DETECTED!" else "⚪ NO VIBRATION"
                    machineTimerMap[machineId] = timerValue
                    machineNameMap[machineId] = machineName


                    val timerData = machineTimers.getOrPut(machineId) { MachineTimerData() }


                    if (vibration == 1 && !timerData.hasESP32Confirmed) {
                        machinesWithESP32.add(machineId)
                        timerData.hasESP32Confirmed = true
                        Log.d("WashingMonitor", "✅ Machine $machineId: ESP32 CONFIRMED (vibration detected)")
                    }


                    if (machinesWithESP32.contains(machineId)) {
                        processMachineTimer(machineId, status, vibration, timerValue)
                    } else {
                        machineTimerMap[machineId] = 120
                        machineStatusMap[machineId] = "idle"
                        Log.d("WashingMonitor", "❌ Machine $machineId: NO ESP32 - Ignoring status changes")
                    }


                    updateNotificationForMachine(machineId)


                    if (machineId == currentMachineId && machinesWithESP32.contains(machineId)) {
                        currentVibrationStatus = machineVibrationMap[machineId] ?: "⚪ NO VIBRATION"
                        currentTimerSeconds = machineTimerMap[machineId] ?: 120
                        currentMachineStatus = when (status) {
                            "running" -> "🟢 WASHING IN PROGRESS"
                            "finished" -> "✅ CYCLE COMPLETE!"
                            "stopped" -> "🟠 MACHINE PAUSED"
                            else -> "⚪ IDLE"
                        }
                        isMachineRunning = status == "running"
                        isTimerRunning = machineTimers[machineId]?.timerRunning == true
                    }

                    // Send broadcast for this specific machine
                    val intent = Intent(ACTION_MACHINE_DATA_UPDATED)
                    intent.putExtra("MACHINE_ID", machineId)
                    LocalBroadcastManager.getInstance(this@WashingMonitorService).sendBroadcast(intent)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("WashingMonitor", "Error listening to machines: ${error.message}")
            }
        }
        database.child("washingMachines").addValueEventListener(firebaseListener!!)
    }


    private fun updateNotificationForMachine(machineId: String) {
        if (!isUserLoggedIn) return

        val status = machineStatusMap[machineId] ?: "idle"
        val timerValue = machineTimerMap[machineId] ?: 120
        val machineName = machineNameMap[machineId] ?: machineId
        val hasESP32 = machinesWithESP32.contains(machineId)

        val statusText = when {
            !hasESP32 -> "🔌 NO ESP32 CONNECTED"
            status == "running" -> "🟢 WASHING IN PROGRESS"
            status == "stopped" -> "⏱️ Timer: ${formatTime(timerValue)}"
            status == "finished" -> "✅ CYCLE COMPLETE!"
            else -> "⚪ IDLE"
        }


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
            .setOngoing(hasESP32 && (status == "stopped" || status == "running"))
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }


    private fun processMachineTimer(machineId: String, status: String, vibration: Int, timerSeconds: Int) {
        if (!isUserLoggedIn || !machinesWithESP32.contains(machineId)) {
            return
        }

        val timerData = machineTimers.getOrPut(machineId) { MachineTimerData() }
        timerData.vibrationValue = vibration


        if (status == "idle") {
            if (timerData.timerRunning) {
                timerData.timerRunnable?.let { handler.removeCallbacks(it) }
                timerData.timerRunnable = null
                timerData.timerRunning = false
                Log.d("WashingMonitor", "Machine $machineId: IDLE - Timer FORCE STOPPED")
            }
            timerData.machineRunning = false
            timerData.cycleCompleteNotified = false
            timerData.totalSeconds = 120
            timerData.remainingSeconds = 120
            machineTimerMap[machineId] = 120
            return
        }

        when (status) {
            "running" -> {
                if (timerData.timerRunning) {
                    timerData.timerRunnable?.let { handler.removeCallbacks(it) }
                    timerData.timerRunnable = null
                    timerData.timerRunning = false
                    Log.d("WashingMonitor", "Machine $machineId: RUNNING - Timer STOPPED")
                }
                timerData.machineRunning = true
                timerData.cycleCompleteNotified = false
                timerData.remainingSeconds = 0
                machineTimerMap[machineId] = 0
                Log.d("WashingMonitor", "Machine $machineId: Timer set to 00:00 (vibration detected)")
            }
            "stopped" -> {

                if (!timerData.machineRunning && status == "stopped") {
                    Log.d("WashingMonitor", "Machine $machineId: Ignoring stopped - wasn't running")
                    return
                }

                timerData.machineRunning = true
                if (!timerData.timerRunning && timerData.vibrationValue == 0) {
                    Log.d("WashingMonitor", "Machine $machineId: STOPPED - Starting timer")
                    startMachineTimer(machineId, timerData, timerSeconds)
                } else if (timerData.vibrationValue == 1) {
                    if (timerData.timerRunning) {
                        timerData.timerRunnable?.let { handler.removeCallbacks(it) }
                        timerData.timerRunnable = null
                        timerData.timerRunning = false
                    }
                    timerData.remainingSeconds = 0
                    machineTimerMap[machineId] = 0
                }
            }
            "finished" -> {
                if (!timerData.cycleCompleteNotified) {
                    timerData.cycleCompleteNotified = true
                    Log.d("WashingMonitor", "Machine $machineId: FINISHED - Showing alert")
                    showAlertForMachine(machineId)
                }
                timerData.machineRunning = false
                if (timerData.timerRunning) {
                    timerData.timerRunnable?.let { handler.removeCallbacks(it) }
                    timerData.timerRunnable = null
                    timerData.timerRunning = false
                }
                timerData.remainingSeconds = 0
                machineTimerMap[machineId] = 0
            }
        }
    }


    private fun startMachineTimer(machineId: String, timerData: MachineTimerData, timerSeconds: Int) {
        if (timerData.timerRunning) {
            Log.d("WashingMonitor", "Machine $machineId: Timer already running, not starting another")
            return
        }

        if (timerData.vibrationValue == 1) {
            Log.d("WashingMonitor", "Machine $machineId: Cannot start timer - vibration is 1")
            timerData.remainingSeconds = 0
            machineTimerMap[machineId] = 0
            updateNotificationForMachine(machineId)
            return
        }

        timerData.timerRunning = true
        timerData.startTime = System.currentTimeMillis()
        timerData.totalSeconds = timerSeconds
        timerData.remainingSeconds = timerSeconds
        machineTimerMap[machineId] = timerSeconds

        Log.d("WashingMonitor", "⏱️ Timer STARTED for machine $machineId (${timerSeconds}s)")

        timerData.timerRunnable = object : Runnable {
            override fun run() {
                if (!isUserLoggedIn || !timerData.timerRunning) {
                    timerData.timerRunning = false
                    return
                }

                val elapsed = (System.currentTimeMillis() - timerData.startTime) / 1000
                val remaining = timerData.totalSeconds - elapsed.toInt()

                if (timerData.vibrationValue == 1) {
                    Log.d("WashingMonitor", "Machine $machineId: Vibration detected - Stopping timer")
                    timerData.timerRunning = false
                    timerData.machineRunning = true
                    timerData.remainingSeconds = 0
                    machineTimerMap[machineId] = 0
                    updateNotificationForMachine(machineId)
                    return
                }

                if (remaining <= 0) {
                    Log.d("WashingMonitor", "Machine $machineId: Timer FINISHED!")
                    timerData.timerRunning = false
                    timerData.machineRunning = false
                    if (!timerData.cycleCompleteNotified && isUserLoggedIn) {
                        timerData.cycleCompleteNotified = true
                        database.child("washingMachines").child(machineId).child("status").setValue("finished")
                        machineStatusMap[machineId] = "finished"

                        // ===== ADD HISTORY SAVING HERE =====
                        saveCycleHistory(machineId)
                        // ===================================

                        showAlertForMachine(machineId)
                    }
                    timerData.remainingSeconds = 0
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
        updateNotificationForMachine(machineId)

        val intent = Intent(ACTION_MACHINE_DATA_UPDATED)
        intent.putExtra("MACHINE_ID", machineId)
        LocalBroadcastManager.getInstance(this@WashingMonitorService).sendBroadcast(intent)
    }

    private fun saveCycleHistory(machineId: String) {
        val machineName = machineNameMap[machineId] ?: machineId

        // Save to "notifications" to match what HistoryActivity reads
        val historyData = mapOf(
            "title" to "Laundry Done!",
            "message" to "$machineName - Your washing cycle is complete!",
            "timestamp" to System.currentTimeMillis(),
            "read" to false
        )

        database.child("notifications").push().setValue(historyData)
            .addOnSuccessListener {
                Log.d("WashingMonitor", "✅ History saved to notifications for $machineName")
            }
            .addOnFailureListener { e ->
                Log.e("WashingMonitor", "Failed to save history: ${e.message}")
            }
    }


    private fun showAlertForMachine(machineId: String) {
        if (!isUserLoggedIn) {
            Log.d("WashingMonitor", "Alert blocked - user not logged in")
            return


        }

        saveCycleHistory(machineId)

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
            putExtra("SHOW_CYCLE_COMPLETE_DIALOG", true)
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
            timerData.timerRunnable?.let { handler.removeCallbacks(it) }
            timerData.timerRunnable = null
            timerData.timerRunning = false
            timerData.machineRunning = false
            timerData.remainingSeconds = 120
            timerData.totalSeconds = 120
            timerData.cycleCompleteNotified = false
            timerData.vibrationValue = 0
        }

        machineTimerMap[machineId] = 120
        machineStatusMap[machineId] = "idle"

        val updates = mapOf<String, Any>(
            "status" to "idle",
            "intensity" to 0,
            "timer" to 120,
            "lastUpdate" to System.currentTimeMillis()
        )
        database.child("washingMachines").child(machineId).updateChildren(updates)
        updateNotificationForMachine(machineId)

        val intent = Intent(ACTION_MACHINE_DATA_UPDATED)
        intent.putExtra("MACHINE_ID", machineId)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        Log.d("WashingMonitor", "Machine $machineId reset - timer stopped")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            performFullCleanup()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isUserLoggedIn) {
            Log.d("WashingMonitor", "User not logged in - stopping service")
            performFullCleanup()
            stopForeground(true)
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
                if (machinesWithESP32.contains(machineId)) {
                    val timerData = machineTimers.getOrPut(machineId) { MachineTimerData() }
                    timerData.totalSeconds = seconds
                    timerData.remainingSeconds = seconds
                    machineTimerMap[machineId] = seconds
                    updateNotificationForMachine(machineId)
                    Log.d("WashingMonitor", "Timer set to $seconds seconds for machine $machineId")
                }
            }
        }
        return START_STICKY
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
        Log.d("WashingMonitor", "onDestroy called")
        performFullCleanup()
        firebaseListener?.let { database.child("washingMachines").removeEventListener(it) }
        handler.removeCallbacksAndMessages(null)
        stopForeground(true)
        super.onDestroy()
        Log.d("WashingMonitor", "Service destroyed completely")
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
}