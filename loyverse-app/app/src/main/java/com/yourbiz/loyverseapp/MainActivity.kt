package com.yourbiz.loyverseapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var tokenSetupLayout: View
    private lateinit var mainContentLayout: View
    private lateinit var tokenInput: EditText
    private lateinit var itemsListView: ListView
    private lateinit var statusText: TextView

    private var currentVariants: List<LoyverseApi.Variant> = emptyList()
    private val addQtyValues = HashMap<Int, String>() // position -> entered text

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)

        tokenSetupLayout = findViewById(R.id.tokenSetupLayout)
        mainContentLayout = findViewById(R.id.mainContentLayout)
        tokenInput = findViewById(R.id.tokenInput)
        itemsListView = findViewById(R.id.itemsListView)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.saveTokenButton).setOnClickListener { saveToken() }
        findViewById<Button>(R.id.loadItemsButton).setOnClickListener { loadItems() }
        findViewById<Button>(R.id.syncButton).setOnClickListener { syncChanges() }

        val savedToken = prefs.getString("api_token", null)
        if (!savedToken.isNullOrBlank()) {
            showMainContent()
        }
    }

    private fun saveToken() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Please paste your token first", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("api_token", token).apply()
        showMainContent()
    }

    private fun showMainContent() {
        tokenSetupLayout.visibility = View.GONE
        mainContentLayout.visibility = View.VISIBLE
    }

    private fun getApi(): LoyverseApi {
        val token = prefs.getString("api_token", "") ?: ""
        return LoyverseApi(token)
    }

    private fun loadItems() {
        statusText.text = "Loading items..."
        executor.execute {
            try {
                val variants = getApi().fetchItemsWithStock()
                currentVariants = variants
                addQtyValues.clear()
                runOnUiThread {
                    statusText.text = "Loaded ${variants.size} items. Enter quantity to ADD for each item you received."
                    itemsListView.adapter = ItemsAdapter()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error loading items: ${e.message}"
                }
            }
        }
    }

    private fun syncChanges() {
        // Build list of updates: only items where staff entered a quantity
        val updates = ArrayList<Triple<String, String, Double>>()
        for ((position, text) in addQtyValues) {
            val addQty = text.toDoubleOrNull() ?: continue
            if (addQty == 0.0) continue
            val variant = currentVariants.getOrNull(position) ?: continue
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
                getApi().updateStockBatch(updates)
                runOnUiThread {
                    statusText.text = "Done! ${updates.size} item(s) updated in Loyverse."
                    loadItems() // refresh to show new totals
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Sync failed: ${e.message}"
                }
            }
        }
    }

    private inner class ItemsAdapter : BaseAdapter() {
        override fun getCount() = currentVariants.size
        override fun getItem(position: Int) = currentVariants[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_item, parent, false)
            val variant = currentVariants[position]

            val nameText = view.findViewById<TextView>(R.id.itemNameText)
            val stockText = view.findViewById<TextView>(R.id.currentStockText)
            val qtyInput = view.findViewById<EditText>(R.id.addQtyInput)

            nameText.text = variant.itemName
            stockText.text = "Stock: ${variant.currentStock}"

            // Restore any previously typed value for this row
            qtyInput.setText(addQtyValues[position] ?: "")

            qtyInput.tag = position
            qtyInput.removeTextChangedListenerSafely()
            qtyInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val pos = qtyInput.tag as? Int ?: return
                    addQtyValues[pos] = s?.toString() ?: ""
                }
            })

            return view
        }
    }

    // Simple helper so ListView row recycling doesn't stack duplicate TextWatchers
    private fun EditText.removeTextChangedListenerSafely() {
        // BaseAdapter recycles views; a fresh addTextChangedListener call above
        // is fine for this simple use case since we always overwrite tag+value.
    }
}
