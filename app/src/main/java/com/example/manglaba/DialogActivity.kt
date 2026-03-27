package com.example.manglaba

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class DialogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_cycle_complete)

        // Make the activity full screen and transparent
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        // Make the background transparent
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTitle = findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = findViewById<TextView>(R.id.tvDialogMessage)
        val btnOk = findViewById<Button>(R.id.btnDialogOk)

        tvTitle.text = "🧺 LAUNDRY DONE!"
        tvMessage.text = "Your washing cycle is complete! Time to take out your laundry."

        btnOk.setOnClickListener {
            finish()
        }
    }

    override fun onBackPressed() {
        // Do nothing, user must click OK
        // This prevents dismissing the dialog with back button
    }
}