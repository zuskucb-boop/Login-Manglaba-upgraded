package com.example.manglaba

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class LoginActivity : AppCompatActivity() {
    private lateinit var database: DatabaseReference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Initialize Firebase with your database URL
        database = FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)
        val tvBack = findViewById<TextView>(R.id.tvBack)
        val tvSignUp = findViewById<TextView>(R.id.tvSignUp)

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                Toast.makeText(this, "Please enter password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show loading
            btnLogin.isEnabled = false
            btnLogin.text = "Checking..."

            // Check if user exists in database
            checkUserCredentials(email, password) { isValid ->
                btnLogin.isEnabled = true
                btnLogin.text = "Login"

                if (isValid) {
                    // Login successful
                    Toast.makeText(this, "Login Successful!", Toast.LENGTH_SHORT).show()

                    // Save login record
                    val loginData = hashMapOf(
                        "email" to email,
                        "loginTime" to System.currentTimeMillis().toString()
                    )
                    database.child("logins").push().setValue(loginData)

                    // Go to HomeActivity
                    val intent = Intent(this, HomeActivity::class.java)
                    intent.putExtra("USERNAME", email)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Invalid email or password", Toast.LENGTH_LONG).show()
                }
            }
        }

        tvForgotPassword.setOnClickListener {
            val intent = Intent(this, ForgotPasswordActivity::class.java)
            startActivity(intent)
        }

        tvBack.setOnClickListener {
            val intent = Intent(this, LaundryReadyActivity::class.java)
            startActivity(intent)
            finish()
        }

        tvSignUp.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }
    }

    private fun checkUserCredentials(email: String, password: String, callback: (Boolean) -> Unit) {
        // Query Firebase for user with matching email
        val usersRef = database.child("users")

        // Search for user by email
        usersRef.orderByChild("email").equalTo(email)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var userFound = false

                    for (userSnapshot in snapshot.children) {
                        val storedEmail = userSnapshot.child("email").getValue(String::class.java)
                        val storedPassword = userSnapshot.child("password").getValue(String::class.java)

                        if (storedEmail == email && storedPassword == password) {
                            userFound = true
                            break
                        }
                    }

                    callback(userFound)
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@LoginActivity, "Database error: ${error.message}", Toast.LENGTH_SHORT).show()
                    callback(false)
                }
            })

    }

}
