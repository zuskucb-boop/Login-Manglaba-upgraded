package com.example.manglaba

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class MachineListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnAddMachine: Button
    private lateinit var tvEmpty: TextView
    private lateinit var database: DatabaseReference
    private val machineList = mutableListOf<MachineItem>()
    private lateinit var adapter: MachineAdapter
    private var userEmail: String = ""


    private val machineStatusListeners = mutableMapOf<String, ValueEventListener>()
    private val machineVibrationListeners = mutableMapOf<String, ValueEventListener>()
    private var machinesListener: ValueEventListener? = null
    private val vibrationStopHandlers = mutableMapOf<String, Runnable>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastUpdateTime = 0L
    private val UPDATE_THROTTLE_MS = 50L


    private val pendingStatusUpdates = mutableMapOf<String, String>()
    private var forceRefreshScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_machine_list)

        recyclerView = findViewById(R.id.recyclerViewMachines)
        btnAddMachine = findViewById(R.id.btnAddMachine)
        tvEmpty = findViewById(R.id.tvEmpty)

        userEmail = intent.getStringExtra("USER_EMAIL") ?: ""
        if (userEmail.isEmpty()) {
            val sharedPref = getSharedPreferences("MyAppPrefs", MODE_PRIVATE)
            userEmail = sharedPref.getString("USER_EMAIL", "") ?: ""
        }

        if (userEmail.isEmpty()) {
            Toast.makeText(this, "Please log in again", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = MachineAdapter(machineList,
            { machineId, machineName ->
                val intent = Intent(this, WashingMachineActivity::class.java)
                intent.putExtra("MACHINE_ID", machineId)
                intent.putExtra("MACHINE_NAME", machineName)
                startActivity(intent)
            },
            { machineId ->
                deleteMachine(machineId)
            }
        )
        recyclerView.adapter = adapter

        val firebaseUrl = "https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/"
        database = FirebaseDatabase.getInstance(firebaseUrl).reference

        startMachinesListener()
        startIndividualMachineListeners()

        btnAddMachine.setOnClickListener {
            val intent = Intent(this, AddMachineActivity::class.java)
            intent.putExtra("USER_EMAIL", userEmail)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
        if (machineStatusListeners.isEmpty()) {
            startIndividualMachineListeners()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAllListeners()
        mainHandler.removeCallbacksAndMessages(null)
        // Clear all vibration timers
        for (runnable in vibrationStopHandlers.values) {
            mainHandler.removeCallbacks(runnable)
        }
        vibrationStopHandlers.clear()
    }

    private fun refreshData() {
        database.child("washingMachines").orderByChild("owner").equalTo(userEmail)
            .get()
            .addOnSuccessListener { snapshot ->
                updateMachineList(snapshot)
            }
            .addOnFailureListener {
                Log.e("MachineList", "Manual refresh failed: ${it.message}")
            }
    }

    private fun scheduleForceRefresh() {
        if (forceRefreshScheduled) return
        forceRefreshScheduled = true
        mainHandler.postDelayed({
            forceRefreshScheduled = false
            Log.d("MachineList", "🔄 Scheduled force refresh")
            refreshData()
        }, 2000)
    }

    private fun startMachinesListener() {
        removeMachinesListener()

        machinesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                mainHandler.post {
                    updateMachineList(snapshot)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                mainHandler.post {
                    Toast.makeText(this@MachineListActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
                    Log.e("MachineList", "Error loading machines: ${error.message}")
                }
            }
        }

        database.child("washingMachines").orderByChild("owner").equalTo(userEmail)
            .addValueEventListener(machinesListener!!)
    }

    private fun startIndividualMachineListeners() {
        database.child("washingMachines").orderByChild("owner").equalTo(userEmail)
            .get()
            .addOnSuccessListener { snapshot ->
                for (machineSnapshot in snapshot.children) {
                    val machineId = machineSnapshot.key ?: continue
                    attachMachineStatusListener(machineId)
                    attachMachineVibrationListener(machineId)
                }
            }
    }

    private fun attachMachineStatusListener(machineId: String) {
        machineStatusListeners[machineId]?.let { oldListener ->
            database.child("washingMachines").child(machineId).child("status").removeEventListener(oldListener)
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newStatus = snapshot.getValue(String::class.java) ?: "idle"
                Log.d("MachineList", "📡 Machine $machineId status: $newStatus")
                mainHandler.post {
                    updateSingleMachineStatus(machineId, newStatus)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MachineList", "Status listener cancelled for $machineId: ${error.message}")
            }
        }

        database.child("washingMachines").child(machineId).child("status")
            .addValueEventListener(listener)
        machineStatusListeners[machineId] = listener
    }

    private fun attachMachineVibrationListener(machineId: String) {
        machineVibrationListeners[machineId]?.let { oldListener ->
            database.child("washingMachines").child(machineId).child("currentVibration").child("value").removeEventListener(oldListener)
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val vibrationValue = snapshot.getValue(Int::class.java) ?: 0
                Log.d("MachineList", "📳 Machine $machineId vibration: $vibrationValue")

                // Cancel any pending stop handler for this machine
                vibrationStopHandlers[machineId]?.let { runnable ->
                    mainHandler.removeCallbacks(runnable)
                }
                vibrationStopHandlers.remove(machineId)

                if (vibrationValue == 1) {
                    // Vibration detected - immediately set to RUNNING
                    Log.d("MachineList", "⚠️ Vibration detected for $machineId - forcing status to RUNNING")
                    mainHandler.post {
                        forceMachineStatus(machineId, "running")
                    }
                } else {
                    // No vibration - schedule status change to STOPPED after 2 seconds of no vibration
                    Log.d("MachineList", "⏳ No vibration for $machineId - scheduling STOPPED in 2 seconds")
                    val runnable = Runnable {
                        Log.d("MachineList", "⏰ Timer finished - setting $machineId to STOPPED")
                        mainHandler.post {
                            forceMachineStatus(machineId, "stopped")
                        }
                    }
                    vibrationStopHandlers[machineId] = runnable
                    mainHandler.postDelayed(runnable, 2000) // 2 seconds delay
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("MachineList", "Vibration listener cancelled for $machineId: ${error.message}")
            }
        }

        database.child("washingMachines").child(machineId).child("currentVibration").child("value")
            .addValueEventListener(listener)
        machineVibrationListeners[machineId] = listener
    }

    private fun forceMachineStatus(machineId: String, newStatus: String) {
        val index = machineList.indexOfFirst { it.id == machineId }
        if (index != -1) {
            val oldItem = machineList[index]
            if (oldItem.status != newStatus) {
                Log.d("MachineList", "💪 FORCE updating $machineId: ${oldItem.status} → $newStatus")
                machineList[index] = MachineItem(oldItem.id, oldItem.name, newStatus)
                adapter.notifyItemChanged(index)
                // Update Firebase to ensure consistency
                database.child("washingMachines").child(machineId).child("status").setValue(newStatus)
            }
        }
    }

    private fun updateSingleMachineStatus(machineId: String, newStatus: String) {
        val index = machineList.indexOfFirst { it.id == machineId }
        if (index != -1) {
            val oldItem = machineList[index]
            if (oldItem.status != newStatus) {
                Log.d("MachineList", "🔄 Updating $machineId: ${oldItem.status} → $newStatus")
                pendingStatusUpdates[machineId] = newStatus
                machineList[index] = MachineItem(oldItem.id, oldItem.name, newStatus)
                adapter.notifyItemChanged(index)
                mainHandler.postDelayed({
                    verifyAndFixStatus(machineId, newStatus)
                }, 500)
            }
        } else {
            refreshData()
        }
    }

    private fun verifyAndFixStatus(machineId: String, expectedStatus: String) {
        database.child("washingMachines").child(machineId).child("status").get()
            .addOnSuccessListener { snapshot ->
                val currentStatus = snapshot.getValue(String::class.java) ?: "idle"
                if (currentStatus != expectedStatus) {
                    Log.d("MachineList", "⚠️ Status mismatch for $machineId: Expected $expectedStatus, Got $currentStatus")
                    refreshData()
                }
                pendingStatusUpdates.remove(machineId)
            }
            .addOnFailureListener {
                Log.e("MachineList", "Verification failed for $machineId: ${it.message}")
            }
    }

    private fun updateMachineList(snapshot: DataSnapshot) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < UPDATE_THROTTLE_MS && !forceRefreshScheduled) {
            return
        }
        lastUpdateTime = now

        try {
            val newList = mutableListOf<MachineItem>()
            val currentMachineIds = machineList.map { it.id }.toSet()

            if (!snapshot.exists()) {
                tvEmpty.visibility = android.view.View.VISIBLE
                recyclerView.visibility = android.view.View.GONE
                machineList.clear()
                adapter.notifyDataSetChanged()
                return
            }

            var hasMachines = false
            for (machineSnapshot in snapshot.children) {
                val id = machineSnapshot.key ?: continue
                val name = machineSnapshot.child("name").getValue(String::class.java) ?: "Unknown"
                val status = machineSnapshot.child("status").getValue(String::class.java) ?: "idle"
                newList.add(MachineItem(id, name, status))
                hasMachines = true

                if (!currentMachineIds.contains(id)) {
                    attachMachineStatusListener(id)
                    attachMachineVibrationListener(id)
                }
            }

            val newMachineIds = newList.map { it.id }.toSet()
            val removedIds = currentMachineIds - newMachineIds
            for (removedId in removedIds) {
                machineStatusListeners[removedId]?.let { listener ->
                    database.child("washingMachines").child(removedId).child("status").removeEventListener(listener)
                }
                machineStatusListeners.remove(removedId)
                machineVibrationListeners[removedId]?.let { listener ->
                    database.child("washingMachines").child(removedId).child("currentVibration").child("value").removeEventListener(listener)
                }
                machineVibrationListeners.remove(removedId)
                pendingStatusUpdates.remove(removedId)
                vibrationStopHandlers[removedId]?.let { runnable ->
                    mainHandler.removeCallbacks(runnable)
                }
                vibrationStopHandlers.remove(removedId)
            }

            mainHandler.post {
                val finalList = newList.map { item ->
                    val pendingStatus = pendingStatusUpdates[item.id]
                    if (pendingStatus != null && pendingStatus != item.status) {
                        Log.d("MachineList", "📌 Using pending status for ${item.id}: $pendingStatus (was ${item.status})")
                        MachineItem(item.id, item.name, pendingStatus)
                    } else {
                        item
                    }
                }.toMutableList()

                machineList.clear()
                machineList.addAll(finalList)

                if (hasMachines) {
                    tvEmpty.visibility = android.view.View.GONE
                    recyclerView.visibility = android.view.View.VISIBLE
                    adapter.notifyDataSetChanged()
                } else {
                    tvEmpty.visibility = android.view.View.VISIBLE
                    recyclerView.visibility = android.view.View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e("MachineList", "Error updating machine list: ${e.message}")
        }
    }

    private fun removeMachinesListener() {
        machinesListener?.let {
            try {
                database.child("washingMachines").orderByChild("owner").equalTo(userEmail)
                    .removeEventListener(it)
            } catch (e: Exception) {
                Log.e("MachineList", "Error removing listener: ${e.message}")
            }
            machinesListener = null
        }
    }

    private fun removeAllListeners() {
        removeMachinesListener()
        for ((machineId, listener) in machineStatusListeners) {
            try {
                database.child("washingMachines").child(machineId).child("status").removeEventListener(listener)
            } catch (e: Exception) {
                Log.e("MachineList", "Error removing status listener for $machineId: ${e.message}")
            }
        }
        machineStatusListeners.clear()

        for ((machineId, listener) in machineVibrationListeners) {
            try {
                database.child("washingMachines").child(machineId).child("currentVibration").child("value").removeEventListener(listener)
            } catch (e: Exception) {
                Log.e("MachineList", "Error removing vibration listener for $machineId: ${e.message}")
            }
        }
        machineVibrationListeners.clear()

        for ((machineId, runnable) in vibrationStopHandlers) {
            mainHandler.removeCallbacks(runnable)
        }
        vibrationStopHandlers.clear()

        pendingStatusUpdates.clear()
    }

    private fun deleteMachine(machineId: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete Machine")
            .setMessage("Are you sure you want to delete this washing machine?")
            .setPositiveButton("Delete") { _, _ ->
                database.child("washingMachines").child(machineId).removeValue()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Machine deleted", Toast.LENGTH_SHORT).show()
                        val index = machineList.indexOfFirst { it.id == machineId }
                        if (index != -1) {
                            machineList.removeAt(index)
                            adapter.notifyItemRemoved(index)
                        }
                        machineStatusListeners[machineId]?.let { listener ->
                            database.child("washingMachines").child(machineId).child("status").removeEventListener(listener)
                        }
                        machineStatusListeners.remove(machineId)
                        machineVibrationListeners[machineId]?.let { listener ->
                            database.child("washingMachines").child(machineId).child("currentVibration").child("value").removeEventListener(listener)
                        }
                        machineVibrationListeners.remove(machineId)
                        vibrationStopHandlers[machineId]?.let { runnable ->
                            mainHandler.removeCallbacks(runnable)
                        }
                        vibrationStopHandlers.remove(machineId)
                        pendingStatusUpdates.remove(machineId)
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Delete failed: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    override fun onBackPressed() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    data class MachineItem(val id: String, val name: String, val status: String)

    inner class MachineAdapter(
        private val items: List<MachineItem>,
        private val onClick: (String, String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<MachineAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_machine, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, onClick, onDelete)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(holder, position, payloads)
            } else {
                val item = items[position]
                holder.updateStatus(item.status)
            }
        }

        inner class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            private val tvMachineName: TextView = itemView.findViewById(R.id.tvMachineName)
            private val tvMachineStatus: TextView = itemView.findViewById(R.id.tvMachineStatus)
            private val btnDelete: Button = itemView.findViewById(R.id.btnDeleteMachine)

            fun bind(item: MachineItem, onClick: (String, String) -> Unit, onDelete: (String) -> Unit) {
                tvMachineName.text = item.name
                updateStatus(item.status)

                itemView.setOnClickListener { onClick(item.id, item.name) }
                btnDelete.setOnClickListener { onDelete(item.id) }
            }

            fun updateStatus(status: String) {
                tvMachineStatus.text = when (status) {
                    "running" -> "🟢 Running"
                    "finished" -> "✅ Complete"
                    "stopped" -> "🟠 Paused"
                    else -> "⚪ Idle"
                }

                when (status) {
                    "running" -> tvMachineStatus.setTextColor(0xFF4CAF50.toInt())
                    "finished" -> tvMachineStatus.setTextColor(0xFF2196F3.toInt())
                    "stopped" -> tvMachineStatus.setTextColor(0xFFFF9800.toInt())
                    else -> tvMachineStatus.setTextColor(0xFF9E9E9E.toInt())
                }
            }
        }
    }
}