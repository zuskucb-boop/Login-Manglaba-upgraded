package com.example.manglaba

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase

class AddMachineActivity : AppCompatActivity() {

    private lateinit var etMachineName: EditText
    private lateinit var etMachineId: EditText
    private lateinit var btnAddMachine: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_machine)

        etMachineName = findViewById(R.id.etMachineName)
        etMachineId = findViewById(R.id.etMachineId)
        btnAddMachine = findViewById(R.id.btnAddMachine)
        btnCancel = findViewById(R.id.btnCancel)



        val userEmail = intent.getStringExtra("USER_EMAIL") ?: ""

        if (userEmail.isEmpty()) {
            Toast.makeText(this, "Error: User email not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        btnAddMachine.setOnClickListener {
            val machineName = etMachineName.text.toString().trim()
            val machineId = etMachineId.text.toString().trim()

            if (machineName.isEmpty()) {
                etMachineName.error = "Enter machine name"
                return@setOnClickListener
            }
            if (machineId.isEmpty()) {
                etMachineId.error = "Enter Machine ID (from sticker on your ESP32)"
                return@setOnClickListener
            }

            addMachineToFirebase(machineId, machineName, userEmail)
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun addMachineToFirebase(machineId: String, machineName: String, userEmail: String) {
        val firebaseUrl = "https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/"
        val database = FirebaseDatabase.getInstance(firebaseUrl).reference

        val machineData = mapOf<String, Any>(
            "name" to machineName,
            "owner" to userEmail,
            "status" to "idle",
            "intensity" to 0,
            "currentVibration" to 0,
            "lastUpdate" to System.currentTimeMillis(),
            "timer" to 120
        )

        database.child("washingMachines").child(machineId).setValue(machineData)
            .addOnSuccessListener {
                Toast.makeText(this, "✅ Machine added successfully!", Toast.LENGTH_LONG).show()
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "❌ Failed to add machine: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}