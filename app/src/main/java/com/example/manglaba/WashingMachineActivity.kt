package com.example.manglaba

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class WashingMachineActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView  // ← Fixed: was tvViewModel
    private lateinit var tvVibration: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnSetTimer: Button
    private lateinit var btnReset: Button
    private lateinit var cardStatus: CardView

    private val handler = Handler(Looper.getMainLooper())
    private var totalTimerSeconds = 120  // ← Fixed: was val, now var
    private var uiRunnable: Runnable? = null  // ← Fixed: was val, now var

    // Broadcast receiver for cycle complete
    private val cycleCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WashingMonitorService.ACTION_CYCLE_COMPLETE) {
                Log.d("WashingMachine", "Received cycle complete broadcast!")  // ← Fixed Log syntax
                showFullScreenAlert()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_washing_machine)

        tvStatus = findViewById(R.id.tvStatus)
        tvVibration = findViewById(R.id.tvVibration)
        tvTimer = findViewById(R.id.tvTimer)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSetTimer = findViewById(R.id.btnSetTimer)
        btnReset = findViewById(R.id.btnReset)
        cardStatus = findViewById(R.id.cardStatus)

        // Register broadcast receiver - Fixed syntax
        LocalBroadcastManager.getInstance(this).registerReceiver(
            cycleCompleteReceiver,
            IntentFilter(WashingMonitorService.ACTION_CYCLE_COMPLETE)
        )

        // Start the background service - Fixed syntax
        startService(Intent(this, WashingMonitorService::class.java))

        btnSetTimer.setOnClickListener {
            showTimerPickerDialog()
        }

        btnRefresh.setOnClickListener {
            updateUIFromService()
            Toast.makeText(this, "Status refreshed", Toast.LENGTH_SHORT).show()
        }

        btnReset.setOnClickListener {
            val intent = Intent(this, WashingMonitorService::class.java).apply {
                action = "ACTION_RESET"
            }
            startService(intent)
            updateUIFromService()
            Toast.makeText(this, "All systems reset!", Toast.LENGTH_SHORT).show()
        }

        startUIUpdater()
    }

    override fun onBackPressed() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(uiRunnable!!)
        // Unregister receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(cycleCompleteReceiver)
    }

    private fun showFullScreenAlert() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cycle_complete, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
        val btnOk = dialogView.findViewById<Button>(R.id.btnDialogOk)

        tvTitle.text = "🧺 LAUNDRY DONE!"
        tvMessage.text = "Your washing cycle is complete! Time to take out your laundry."

        val dialog = android.app.AlertDialog.Builder(this, R.style.FullScreenDialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        btnOk.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

            val intent = Intent(this, WashingMonitorService::class.java).apply {
                action = "ACTION_SET_TIMER"
                putExtra("TIMER_SECONDS", totalTimerSeconds)
            }
            startService(intent)

            tvTimer.text = formatTime(totalTimerSeconds)
            Toast.makeText(this, "Timer set to $minutes minute(s)", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startUIUpdater() {
        uiRunnable = object : Runnable {
            override fun run() {
                updateUIFromService()
                handler.postDelayed(this, 500) // Changed from 1000 to 500ms
            }
        }
        handler.post(uiRunnable!!)
    }

    private fun updateUIFromService() {
        tvTimer.text = formatTime(WashingMonitorService.currentTimerSeconds)
        tvStatus.text = WashingMonitorService.currentMachineStatus
        tvVibration.text = WashingMonitorService.currentVibrationStatus

        when {
            WashingMonitorService.isTimerRunning -> {
                tvStatus.setTextColor(0xFFFF9800.toInt())
                cardStatus.setCardBackgroundColor(0xFFFFF3E0.toInt())
                when {
                    WashingMonitorService.currentTimerSeconds <= 10 -> tvTimer.setTextColor(0xFFF44336.toInt())
                    WashingMonitorService.currentTimerSeconds <= 30 -> tvTimer.setTextColor(0xFFFF9800.toInt())
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

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    override fun onResume() {
        super.onResume()
        updateUIFromService()
    }
}