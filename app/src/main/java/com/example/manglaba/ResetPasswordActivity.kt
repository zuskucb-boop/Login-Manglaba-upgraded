package com.example.manglaba

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ResetPasswordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reset_password)

        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        val btnReset = findViewById<Button>(R.id.btnReset)

        // Get token from the link that opened this activity
        val token = intent.getStringExtra("token") ?: ""

        btnReset.setOnClickListener {
            val newPassword = etNewPassword.text.toString()
            val confirmPassword = etConfirmPassword.text.toString()

            when {
                newPassword.isEmpty() -> {
                    Toast.makeText(this, "Enter new password", Toast.LENGTH_SHORT).show()
                }
                newPassword.length < 6 -> {
                    Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                }
                newPassword != confirmPassword -> {
                    Toast.makeText(this, "Passwords don't match", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // In a real app: send new password + token to backend
                    Toast.makeText(this, "Password updated successfully!", Toast.LENGTH_LONG).show()

                    // Go back to login
                    finish()
                }
            }
        }
    }
}