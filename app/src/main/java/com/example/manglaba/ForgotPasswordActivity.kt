package com.example.manglaba

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ForgotPasswordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        val tvBack = findViewById<TextView>(R.id.tvBack)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val btnSend = findViewById<Button>(R.id.btnSend)

        tvBack.setOnClickListener {
            finish()
        }

        btnSend.setOnClickListener {
            val email = etEmail.text.toString()

            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Reset link sent to $email", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ForegroundTracker.currentActivity = "ForgotPasswordActivity"
    }

    override fun onPause() {
        super.onPause()
        if (ForegroundTracker.currentActivity == "ForgotPasswordActivity") {
            ForegroundTracker.currentActivity = null
        }
    }
}