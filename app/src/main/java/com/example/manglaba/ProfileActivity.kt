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

class ProfileActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private lateinit var tvUserName: TextView
    private lateinit var etFirstName: EditText
    private lateinit var etLastName: EditText
    private lateinit var etEmail: EditText
    private lateinit var btnSave: Button
    private lateinit var btnChangePassword: Button
    private lateinit var btnBack: Button

    private var userId: String? = null
    private var currentEmail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)


        tvUserName = findViewById(R.id.tvUserName)
        etFirstName = findViewById(R.id.etFirstName)
        etLastName = findViewById(R.id.etLastName)
        etEmail = findViewById(R.id.etEmail)
        btnSave = findViewById(R.id.btnSave)
        btnChangePassword = findViewById(R.id.btnChangePassword)
        btnBack = findViewById(R.id.btnBack)


        val userEmail = intent.getStringExtra("USER_EMAIL") ?: ""
        currentEmail = userEmail


        val firebaseUrl = "https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/"
        database = FirebaseDatabase.getInstance(firebaseUrl).reference


        loadUserProfile(userEmail)


        btnSave.setOnClickListener {
            saveUserProfile()
        }


        btnChangePassword.setOnClickListener {
            val intent = Intent(this, ChangePasswordActivity::class.java)
            intent.putExtra("USER_EMAIL", currentEmail)
            startActivity(intent)
        }


        btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadUserProfile(email: String) {

        database.child("users").orderByChild("email").equalTo(email)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        for (userSnapshot in snapshot.children) {
                            userId = userSnapshot.key
                            val firstName = userSnapshot.child("firstName").getValue(String::class.java) ?: ""
                            val lastName = userSnapshot.child("lastName").getValue(String::class.java) ?: ""
                            val userEmail = userSnapshot.child("email").getValue(String::class.java) ?: ""

                            tvUserName.text = "$firstName $lastName"
                            etFirstName.setText(firstName)
                            etLastName.setText(lastName)
                            etEmail.setText(userEmail)
                            break
                        }
                    } else {
                        Toast.makeText(this@ProfileActivity, "User not found", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@ProfileActivity, "Error loading profile: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun saveUserProfile() {
        val firstName = etFirstName.text.toString().trim()
        val lastName = etLastName.text.toString().trim()
        val email = etEmail.text.toString().trim()

        if (firstName.isEmpty()) {
            etFirstName.error = "First name required"
            return
        }
        if (lastName.isEmpty()) {
            etLastName.error = "Last name required"
            return
        }
        if (email.isEmpty()) {
            etEmail.error = "Email required"
            return
        }


        val updates = mapOf<String, Any>(
            "firstName" to firstName,
            "lastName" to lastName,
            "email" to email,
            "lastUpdated" to System.currentTimeMillis()
        )

        userId?.let {
            database.child("users").child(it).updateChildren(updates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                    tvUserName.text = "$firstName $lastName"
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to update profile: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}