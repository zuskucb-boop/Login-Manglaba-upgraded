package com.example.manglaba

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class CycleCompleteDialog(context: Context) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_cycle_complete)

        // Make dialog full screen
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Set window flags to appear over other apps
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        )

        // Set the dialog to be cancelable only by button click
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        val tvTitle = findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = findViewById<TextView>(R.id.tvDialogMessage)
        val btnOk = findViewById<Button>(R.id.btnDialogOk)

        tvTitle.text = "🧺 LAUNDRY DONE!"
        tvMessage.text = "Your washing cycle is complete! Time to take out your laundry."

        btnOk.setOnClickListener {
            dismiss()
        }
    }
}