package com.example.manglaba

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

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


        listViewHistory = findViewById(R.id.listViewHistory)
        tvTotalCycles = findViewById(R.id.tvTotalCycles)
        tvLastCycle = findViewById(R.id.tvLastCycle)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)


        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener {
            finish()
        }


        database = FirebaseDatabase.getInstance("https://manglaba-16795-default-rtdb.asia-southeast1.firebasedatabase.app/").reference


        adapter = HistoryAdapter(historyList)
        listViewHistory.adapter = adapter


        loadHistory()
    }

    private fun loadHistory() {
        progressBar.visibility = View.VISIBLE


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


                    historyList.sortByDescending { it.timestamp }


                    tvTotalCycles.text = count.toString()
                    if (lastTime > 0) {
                        tvLastCycle.text = formatDateTime(lastTime)
                    } else {
                        tvLastCycle.text = "No cycles yet"
                    }


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


    data class HistoryItem(
        val id: String,
        val title: String,
        val message: String,
        val timestamp: Long,
        val read: Boolean
    )


    inner class HistoryAdapter(private val items: List<HistoryItem>) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {

            val view: View
            val viewHolder: ViewHolder

            if (convertView == null) {

                view = layoutInflater.inflate(R.layout.list_item_history, parent, false)
                viewHolder = ViewHolder()
                viewHolder.tvCycleNumber = view.findViewById(R.id.tvCycleNumber)
                viewHolder.tvDateTime = view.findViewById(R.id.tvDateTime)
                viewHolder.tvDuration = view.findViewById(R.id.tvDuration)
                viewHolder.tvStatus = view.findViewById(R.id.tvStatus)
                view.tag = viewHolder
            } else {

                view = convertView
                viewHolder = view.tag as ViewHolder
            }


            val item = items[position]


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