package com.yourbiz.loyverseapp

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Stock Count: staff scan items, type in how many they physically counted,
 * and on Confirm that number OVERWRITES the stock in Loyverse (unlike Add /
 * Reduce Stock, which adjust the existing number up or down).
 *
 * Key differences from Add Stock:
 *  - The entered number is the final stock, not an amount to add.
 *  - 0 is a valid count ("I looked, there are none"). A blank field means
 *    "not counted yet" and that item is left alone.
 *  - Items whose count matches what Loyverse already has aren't re-sent.
 *  - Counting an untracked item turns Track stock on for it first, same
 *    as Add Stock does.
 *
 * Pooled items (per-generation counting) will be added here once Manage
 * Pools exists - for now every item is counted on its own.
 */
class StockCountActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var pickerListView: ListView
    private lateinit var workingListView: ListView
    private lateinit var loadingOverlay: View
    private lateinit var confirmButton: Button

    private var allVariants: List<LoyverseApi.Variant> = emptyList()
    private var pickerResults: List<LoyverseApi.Variant> = emptyList()

    private val workingItems = LinkedHashMap<String, LoyverseApi.Variant>()
    /** variantId -> counted quantity. Absent = not counted yet. */
    private val counts = HashMap<String, Double>()

    private val scanBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_count)

        prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)

        searchInput = findViewById(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        pickerListView = findViewById(R.id.pickerListView)
        workingListView = findViewById(R.id.workingListView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        confirmButton = findViewById(R.id.confirmButton)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        confirmButton.setOnClickListener { askConfirmThenSync() }

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
        // Treat key events as scanner input only when no text field has
        // focus, so typing into a count box still works normally.
        val focusedIsTextField = currentFocus is EditText
        if (!focusedIsTextField && event.action == KeyEvent.ACTION_DOWN) {
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
        if (ItemCache.variants.isNotEmpty()) {
            allVariants = ItemCache.variants
            setReady(true)
            statusText.text = "Ready. Scan or search an item to count."
            return
        }

        setReady(false)
        statusText.text = "Loading catalog..."
        executor.execute {
            try {
                val token = prefs.getString("api_token", "") ?: ""
                ItemCache.refresh(LoyverseApi(token))
                runOnUiThread {
                    allVariants = ItemCache.variants
                    setReady(true)
                    statusText.text = "Ready. Scan or search an item to count."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setReady(true)
                    statusText.text = "Failed to load catalog: ${e.message}"
                }
            }
        }
    }

    private fun setReady(ready: Boolean) {
        loadingOverlay.visibility = if (ready) View.GONE else View.VISIBLE
        searchInput.isEnabled = ready
        confirmButton.isEnabled = ready
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
            statusText.text = "Tap an item to add it to your count."
        }
        pickerListView.adapter = PickerAdapter()
    }

    private fun addToWorkingList(variant: LoyverseApi.Variant) {
        if (workingItems.containsKey(variant.variantId)) {
            Toast.makeText(this, "${variant.itemName} is already in your count", Toast.LENGTH_SHORT).show()
            return
        }
        workingItems[variant.variantId] = variant
        refreshWorkingListView()
    }

    private fun removeFromWorkingList(variantId: String) {
        workingItems.remove(variantId)
        counts.remove(variantId)
        refreshWorkingListView()
    }

    private fun refreshWorkingListView() {
        workingListView.adapter = WorkingAdapter()
    }

    /** True if saving this count would actually change something in Loyverse. */
    private fun needsWrite(variant: LoyverseApi.Variant, counted: Double): Boolean =
        !variant.trackStock || counted != variant.currentStock

    private fun askConfirmThenSync() {
        if (workingItems.isEmpty()) {
            Toast.makeText(this, "No items added yet", Toast.LENGTH_SHORT).show()
            return
        }
        val counted = workingItems.values.filter { counts.containsKey(it.variantId) }
        if (counted.isEmpty()) {
            Toast.makeText(this, "Enter a count for at least one item", Toast.LENGTH_SHORT).show()
            return
        }

        val changed = counted.count { needsWrite(it, counts[it.variantId]!!) }
        val matched = counted.size - changed
        val notCounted = workingItems.size - counted.size

        if (changed == 0) {
            Toast.makeText(this, "All $matched count(s) already match Loyverse - nothing to update", Toast.LENGTH_LONG).show()
            return
        }

        val message = buildString {
            append("$changed item(s) will be set to your counted number in Loyverse.")
            if (matched > 0) append("\n$matched item(s) already match and will be left as-is.")
            if (notCounted > 0) append("\n$notCounted item(s) have no count entered and will be skipped.")
            append("\n\nContinue?")
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm stock count")
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> syncChanges() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncChanges() {
        val updates = ArrayList<Triple<String, String, Double>>()
        val itemIdsToEnableTracking = LinkedHashSet<String>()

        for ((variantId, variant) in workingItems) {
            val counted = counts[variantId] ?: continue
            if (!needsWrite(variant, counted)) continue
            if (!variant.trackStock) itemIdsToEnableTracking.add(variant.itemId)
            updates.add(Triple(variant.variantId, variant.storeId, counted))
        }

        if (updates.isEmpty()) return

        setReady(false)
        statusText.text = "Saving ${updates.size} count(s)..."
        executor.execute {
            try {
                val token = prefs.getString("api_token", "") ?: ""
                val api = LoyverseApi(token)

                // Step 1: turn on Track stock for previously-untracked items.
                for (itemId in itemIdsToEnableTracking) {
                    api.updateItemTrackStock(itemId, true)
                }

                // Step 2: overwrite stock with the counted numbers.
                api.updateStockBatch(updates)

                runOnUiThread {
                    Toast.makeText(this, "Done! ${updates.size} item(s) updated.", Toast.LENGTH_LONG).show()
                    applyUpdatesToCache(updates)
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setReady(true)
                    statusText.text = "Save failed: ${e.message}"
                }
            }
        }
    }

    private fun applyUpdatesToCache(updates: List<Triple<String, String, Double>>) {
        val newStockByVariant = updates.associate { it.first to it.third }
        ItemCache.variants = ItemCache.variants.map { variant ->
            val newStock = newStockByVariant[variant.variantId]
            if (newStock != null) variant.copy(currentStock = newStock, trackStock = true) else variant
        }
    }

    private fun formatQty(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    private fun stockLine(variant: LoyverseApi.Variant): String {
        val system = if (variant.trackStock) "In Loyverse: ${formatQty(variant.currentStock)}"
                     else "In Loyverse: not tracked yet"
        val counted = counts[variant.variantId] ?: return system
        if (!variant.trackStock) return "$system → counted ${formatQty(counted)}"
        val diff = counted - variant.currentStock
        val diffText = when {
            diff > 0 -> "+${formatQty(diff)}"
            diff < 0 -> "-${formatQty(-diff)}"
            else -> "matches"
        }
        return "$system → counted ${formatQty(counted)} ($diffText)"
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
                "Stock: ${formatQty(variant.currentStock)}"
            } else {
                "Stock: not tracked yet"
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
            val deleteButton = view.findViewById<ImageView>(R.id.deleteButton)

            deleteButton.setOnClickListener { removeFromWorkingList(variant.variantId) }

            nameText.text = variant.itemName
            stockText.text = stockLine(variant)

            // Counts can't be negative, so no minus sign on the keypad.
            qtyInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            qtyInput.hint = "count"
            qtyInput.setText(counts[variant.variantId]?.let { formatQty(it) } ?: "")

            qtyInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // Blank = not counted. 0 is a real count, so it's kept.
                    val value = s?.toString()?.trim()?.toDoubleOrNull()
                    if (value == null) counts.remove(variant.variantId)
                    else counts[variant.variantId] = value
                    stockText.text = stockLine(variant)
                }
            })

            return view
        }
    }
}
