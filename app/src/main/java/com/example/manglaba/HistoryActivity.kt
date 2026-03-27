package com.example.manglaba

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var database: DatabaseReference
    private lateinit var listViewHistory: ListView
    private lateinit var tvTotalCycles: TextView
    private lateinit var tvLastCycle: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    private val historyList = mutableListOf<HistoryItem>()
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // Initialize views
        listViewHistory = findViewById(R.id.listViewHistory)
        tvTotalCycles = findViewById(R.id.tvTotalCycles)
        tvLastCycle = findViewById(R.id.tvLastCycle)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        // Setup toolbar back button
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Initialize Firebase
        database = FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

        // Setup adapter
        adapter = HistoryAdapter(historyList)
        listViewHistory.adapter = adapter

        // Load history
        loadHistory()
    }

    private fun loadHistory() {
        progressBar.visibility = View.VISIBLE

        // Get all notifications (completed cycles)
        database.child("notifications")
            .orderByChild("timestamp")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    historyList.clear()

                    var count = 0
                    var lastTime: Long = 0

                    for (notificationSnapshot in snapshot.children) {
                        val title = notificationSnapshot.child("title").getValue(String::class.java) ?: ""
                        val message = notificationSnapshot.child("message").getValue(String::class.java) ?: ""
                        val timestamp = notificationSnapshot.child("timestamp").getValue(Long::class.java) ?: 0
                        val read = notificationSnapshot.child("read").getValue(Boolean::class.java) ?: false

                        // Only show "Laundry Done!" notifications
                        if (title == "Laundry Done!" || message.contains("cycle is complete")) {
                            count++
                            if (timestamp > lastTime) {
                                lastTime = timestamp
                            }

                            val historyItem = HistoryItem(
                                id = notificationSnapshot.key ?: "",
                                title = title,
                                message = message,
                                timestamp = timestamp,
                                read = read
                            )
                            historyList.add(historyItem)
                        }
                    }

                    // Sort by timestamp (newest first)
                    historyList.sortByDescending { it.timestamp }

                    // Update statistics
                    tvTotalCycles.text = count.toString()
                    if (lastTime > 0) {
                        tvLastCycle.text = formatDateTime(lastTime)
                    } else {
                        tvLastCycle.text = "No cycles yet"
                    }

                    // Show empty state if no history
                    if (historyList.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        listViewHistory.visibility = View.GONE
                    } else {
                        tvEmpty.visibility = View.GONE
                        listViewHistory.visibility = View.VISIBLE
                        adapter.notifyDataSetChanged()
                    }

                    progressBar.visibility = View.GONE
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@HistoryActivity, "Error loading history: ${error.message}", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                }
            })
    }

    private fun formatDateTime(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("MMM dd, yyyy hh:mm a", java.util.Locale.getDefault())
        return format.format(date)
    }

    // Data class for history items
    data class HistoryItem(
        val id: String,
        val title: String,
        val message: String,
        val timestamp: Long,
        val read: Boolean
    )

    // Adapter for ListView
    inner class HistoryAdapter(private val items: List<HistoryItem>) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Create or reuse view
            val view: View
            val viewHolder: ViewHolder

            if (convertView == null) {
                // Inflate new view
                view = layoutInflater.inflate(R.layout.list_item_history, parent, false)
                viewHolder = ViewHolder()
                viewHolder.tvCycleNumber = view.findViewById(R.id.tvCycleNumber)
                viewHolder.tvDateTime = view.findViewById(R.id.tvDateTime)
                viewHolder.tvDuration = view.findViewById(R.id.tvDuration)
                viewHolder.tvStatus = view.findViewById(R.id.tvStatus)
                view.tag = viewHolder
            } else {
                // Reuse existing view
                view = convertView
                viewHolder = view.tag as ViewHolder
            }

            // Get the item
            val item = items[position]

            // Set data
            viewHolder.tvCycleNumber.text = "Cycle #${items.size - position}"
            viewHolder.tvDateTime.text = formatDateTime(item.timestamp)
            viewHolder.tvDuration.text = "Completed"
            viewHolder.tvStatus.text = "✅"

            return view
        }

        // ViewHolder pattern for better performance
        inner class ViewHolder {
            lateinit var tvCycleNumber: TextView
            lateinit var tvDateTime: TextView
            lateinit var tvDuration: TextView
            lateinit var tvStatus: TextView
        }
    }
}