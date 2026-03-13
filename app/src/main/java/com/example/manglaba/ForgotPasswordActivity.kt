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

        // Back button - return to Login screen
        tvBack.setOnClickListener {
            finish() // This closes this screen and goes back
        }

        // Send button - ALL THIS CODE MUST BE INSIDE onCreate
        btnSend.setOnClickListener {
            val email = etEmail.text.toString()

            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
            } else {
                // Here you would normally send the request
                Toast.makeText(this, "Reset link sent to $email", Toast.LENGTH_LONG).show()
                finish() // Go back after sending
            }
        }
    }
}