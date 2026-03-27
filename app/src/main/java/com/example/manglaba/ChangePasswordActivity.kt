package com.example.manglaba

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

class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private lateinit var etCurrentPassword: EditText
    private lateinit var etNewPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnChangePassword: Button
    private lateinit var btnBack: Button
    private lateinit var tvUserEmail: TextView

    private var userEmail: String? = null
    private var userId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_change_password)

        tvUserEmail = findViewById(R.id.tvUserEmail)
        etCurrentPassword = findViewById(R.id.etCurrentPassword)
        etNewPassword = findViewById(R.id.etNewPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnChangePassword = findViewById(R.id.btnChangePassword)
        btnBack = findViewById(R.id.btnBack)

        userEmail = intent.getStringExtra("USER_EMAIL")
        tvUserEmail.text = userEmail

        val firebaseUrl = "https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/"
        database = FirebaseDatabase.getInstance(firebaseUrl).reference

        getUserInfo()

        btnChangePassword.setOnClickListener {
            changePassword()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun getUserInfo() {
        database.child("users").orderByChild("email").equalTo(userEmail)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (userSnapshot in snapshot.children) {
                        userId = userSnapshot.key
                        break
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun changePassword() {
        val currentPassword = etCurrentPassword.text.toString().trim()
        val newPassword = etNewPassword.text.toString().trim()
        val confirmPassword = etConfirmPassword.text.toString().trim()

        if (currentPassword.isEmpty()) {
            etCurrentPassword.error = "Enter current password"
            return
        }
        if (newPassword.isEmpty()) {
            etNewPassword.error = "Enter new password"
            return
        }
        if (newPassword.length < 6) {
            etNewPassword.error = "Password must be at least 6 characters"
            return
        }
        if (newPassword != confirmPassword) {
            etConfirmPassword.error = "Passwords do not match"
            return
        }

        database.child("users").orderByChild("email").equalTo(userEmail)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var passwordValid = false
                    for (userSnapshot in snapshot.children) {
                        val storedPassword = userSnapshot.child("password").getValue(String::class.java) ?: ""
                        if (storedPassword == currentPassword) {
                            passwordValid = true
                            break
                        }
                    }

                    if (passwordValid) {
                        userId?.let {
                            database.child("users").child(it).child("password").setValue(newPassword)
                                .addOnSuccessListener {
                                    Toast.makeText(this@ChangePasswordActivity, "Password changed successfully!", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                                .addOnFailureListener {
                                    Toast.makeText(this@ChangePasswordActivity, "Failed to change password", Toast.LENGTH_SHORT).show()
                                }
                        }
                    } else {
                        Toast.makeText(this@ChangePasswordActivity, "Current password is incorrect", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}