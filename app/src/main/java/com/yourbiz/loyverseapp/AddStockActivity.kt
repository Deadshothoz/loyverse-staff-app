package com.yourbiz.loyverseapp

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class AddStockActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultsListView: ListView

    private var allVariants: List<LoyverseApi.Variant> = emptyList()
    private var displayedResults: List<LoyverseApi.Variant> = emptyList()

    // variantId -> quantity to add. Persists across multiple searches so
    // staff can scan several different items before hitting Confirm.
    private val pendingChanges = HashMap<String, Double>()

    // Buffer used to catch scanner input even when the search box isn't
    // focused. Barcode scanners on Sunmi/iMin act like a fast keyboard
    // that ends with an Enter keystroke.
    private val scanBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_stock)

        prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)

        searchInput = findViewById(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        resultsListView = findViewById(R.id.resultsListView)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.confirmButton).setOnClickListener { confirmChanges() }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(searchInput.text.toString())
                true
            } else false
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                runSearch(s?.toString() ?: "")
            }
        })

        loadCatalog()
    }

    /**
     * Catches key input even when the search box doesn't have focus, so a
     * barcode scanner works anywhere on this screen without staff needing
     * to tap into the search box first.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!searchInput.hasFocus() && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                val scanned = scanBuffer.toString()
                scanBuffer.clear()
                if (scanned.isNotEmpty()) {
                    searchInput.setText(scanned)
                    runSearch(scanned)
                }
                return true
            }
            val c = event.unicodeChar
            if (c != 0) {
                scanBuffer.append(c.toChar())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun loadCatalog() {
        statusText.text = "Loading catalog..."
        executor.execute {
            try {
                val token = prefs.getString("api_token", "") ?: ""
                val variants = LoyverseApi(token).fetchItemsWithStock()
                ItemCache.variants = variants
                allVariants = variants
                runOnUiThread {
                    statusText.text = "Ready. Search or scan an item to begin."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Failed to load catalog: ${e.message}"
                }
            }
        }
    }

    private fun runSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            displayedResults = emptyList()
            resultsListView.visibility = View.GONE
            resultsListView.adapter = null
            return
        }

        // Exact barcode match takes priority (typical for scanner input)
        val exactBarcodeMatches = allVariants.filter {
            it.barcode.isNotEmpty() && it.barcode == trimmed
        }
        displayedResults = if (exactBarcodeMatches.isNotEmpty()) {
            exactBarcodeMatches
        } else {
            allVariants.filter { it.itemName.contains(trimmed, ignoreCase = true) }
        }

        if (displayedResults.isEmpty()) {
            resultsListView.visibility = View.GONE
            statusText.text = "No matching item found."
        } else {
            resultsListView.visibility = View.VISIBLE
            statusText.text = "${displayedResults.size} result(s)"
        }
        resultsListView.adapter = ResultsAdapter()
    }

    private fun confirmChanges() {
        if (pendingChanges.isEmpty()) {
            Toast.makeText(this, "No quantities entered", Toast.LENGTH_SHORT).show()
            return
        }

        val updates = ArrayList<Triple<String, String, Double>>()
        for ((variantId, addQty) in pendingChanges) {
            if (addQty == 0.0) continue
            val variant = allVariants.find { it.variantId == variantId } ?: continue
            val newStock = variant.currentStock + addQty
            updates.add(Triple(variant.variantId, variant.storeId, newStock))
        }

        if (updates.isEmpty()) {
            Toast.makeText(this, "No quantities entered", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = "Syncing ${updates.size} item(s)..."
        executor.execute {
            try {
                val token = prefs.getString("api_token", "") ?: ""
                LoyverseApi(token).updateStockBatch(updates)
                runOnUiThread {
                    Toast.makeText(this, "Done! ${updates.size} item(s) updated.", Toast.LENGTH_LONG).show()
                    pendingChanges.clear()
                    searchInput.setText("")
                    loadCatalog() // refresh stock numbers
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Sync failed: ${e.message}"
                }
            }
        }
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = displayedResults.size
        override fun getItem(position: Int) = displayedResults[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = layoutInflater.inflate(R.layout.row_item, parent, false)
            val variant = displayedResults[position]

            val nameText = view.findViewById<TextView>(R.id.itemNameText)
            val stockText = view.findViewById<TextView>(R.id.currentStockText)
            val qtyInput = view.findViewById<EditText>(R.id.addQtyInput)

            nameText.text = variant.itemName
            stockText.text = "Stock: ${variant.currentStock}"
            qtyInput.setText(pendingChanges[variant.variantId]?.toString() ?: "")

            qtyInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val value = s?.toString()?.toDoubleOrNull()
                    if (value == null || value == 0.0) {
                        pendingChanges.remove(variant.variantId)
                    } else {
                        pendingChanges[variant.variantId] = value
                    }
                }
            })

            return view
        }
    }
}
