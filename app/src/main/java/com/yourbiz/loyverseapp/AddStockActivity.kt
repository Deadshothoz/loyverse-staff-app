package com.yourbiz.loyverseapp

import android.app.AlertDialog
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
    private lateinit var pickerListView: ListView
    private lateinit var workingListView: ListView

    private var allVariants: List<LoyverseApi.Variant> = emptyList()
    private var pickerResults: List<LoyverseApi.Variant> = emptyList()

    private val workingItems = LinkedHashMap<String, LoyverseApi.Variant>()
    private val pendingChanges = HashMap<String, Double>()

    private val scanBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_stock)

        prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)

        searchInput = findViewById(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        pickerListView = findViewById(R.id.pickerListView)
        workingListView = findViewById(R.id.workingListView)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.confirmButton).setOnClickListener { askConfirmThenSync() }

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

        pickerListView.setOnItemClickListener { _, _, position, _ ->
            val variant = pickerResults.getOrNull(position) ?: return@setOnItemClickListener
            addToWorkingList(variant)
            searchInput.setText("")
        }

        refreshWorkingListView()
        loadCatalog()
    }

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
            pickerResults = emptyList()
            pickerListView.visibility = View.GONE
            pickerListView.adapter = null
            return
        }

        val exactBarcodeMatches = allVariants.filter {
            it.barcode.isNotEmpty() && it.barcode == trimmed
        }
        pickerResults = if (exactBarcodeMatches.isNotEmpty()) {
            exactBarcodeMatches
        } else {
            allVariants.filter { it.itemName.contains(trimmed, ignoreCase = true) }
        }

        if (pickerResults.isEmpty()) {
            pickerListView.visibility = View.GONE
            statusText.text = "No matching item found."
        } else {
            pickerListView.visibility = View.VISIBLE
            statusText.text = "Tap an item to add it to your list."
        }
        pickerListView.adapter = PickerAdapter()
    }

    private fun addToWorkingList(variant: LoyverseApi.Variant) {
        workingItems[variant.variantId] = variant
        refreshWorkingListView()
    }

    private fun refreshWorkingListView() {
        workingListView.adapter = WorkingAdapter()
    }

    private fun askConfirmThenSync() {
        if (workingItems.isEmpty()) {
            Toast.makeText(this, "No items added yet", Toast.LENGTH_SHORT).show()
            return
        }
        val itemsWithQty = workingItems.keys.count {
            (pendingChanges[it] ?: 0.0) != 0.0
        }
        if (itemsWithQty == 0) {
            Toast.makeText(this, "Enter a quantity for at least one item", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm stock update")
            .setMessage("You're about to update stock for $itemsWithQty item(s) in Loyverse. Continue?")
            .setPositiveButton("Confirm") { _, _ -> syncChanges() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncChanges() {
        val updates = ArrayList<Triple<String, String, Double>>()
        // Item IDs that need "Track stock" turned on before we can write
        // an inventory level for their variants.
        val itemIdsToEnableTracking = LinkedHashSet<String>()

        for ((variantId, variant) in workingItems) {
            val addQty = pendingChanges[variantId] ?: continue
            if (addQty == 0.0) continue

            val newStock = if (variant.trackStock) {
                // Already tracked - normal behavior, add on top of current stock.
                variant.currentStock + addQty
            } else {
                // Not tracked yet - it's starting from 0, so the entered
                // quantity IS the final stock, and we need to flip tracking
                // on for this item first.
                itemIdsToEnableTracking.add(variant.itemId)
                addQty
            }
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
                val api = LoyverseApi(token)

                // Step 1: turn on Track stock for any previously-untracked
                // items (one call per item, not per variant).
                for (itemId in itemIdsToEnableTracking) {
                    api.updateItemTrackStock(itemId, true)
                }

                // Step 2: write the actual stock levels.
                api.updateStockBatch(updates)

                runOnUiThread {
                    Toast.makeText(this, "Done! ${updates.size} item(s) updated.", Toast.LENGTH_LONG).show()
                    workingItems.clear()
                    pendingChanges.clear()
                    refreshWorkingListView()
                    loadCatalog()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Sync failed: ${e.message}"
                }
            }
        }
    }

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount() = pickerResults.size
        override fun getItem(position: Int) = pickerResults[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = layoutInflater.inflate(R.layout.row_picker_item, parent, false)
            val variant = pickerResults[position]
            view.findViewById<TextView>(R.id.pickerNameText).text = variant.itemName
            view.findViewById<TextView>(R.id.pickerStockText).text = if (variant.trackStock) {
                "Stock: ${variant.currentStock}"
            } else {
                "Stock: 0 (not tracked yet)"
            }
            return view
        }
    }

    private inner class WorkingAdapter : BaseAdapter() {
        private val items = workingItems.values.toList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = layoutInflater.inflate(R.layout.row_item, parent, false)
            val variant = items[position]

            val nameText = view.findViewById<TextView>(R.id.itemNameText)
            val stockText = view.findViewById<TextView>(R.id.currentStockText)
            val qtyInput = view.findViewById<EditText>(R.id.addQtyInput)

            nameText.text = variant.itemName
            stockText.text = if (variant.trackStock) {
                "Stock: ${variant.currentStock}"
            } else {
                "Stock: 0 (not tracked yet)"
            }
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
