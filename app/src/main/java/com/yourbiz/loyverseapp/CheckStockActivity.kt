package com.yourbiz.loyverseapp

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class CheckStockActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var statusText: TextView
    private lateinit var resultsListView: ListView
    private lateinit var loadingOverlay: View
    private lateinit var categorySpinner: Spinner
    private lateinit var stockAlertSpinner: Spinner

    /** One row per variant for now - pooling will collapse multiple
     *  variants into one row here once Manage Pools exists. */
    data class Row(
        val displayName: String,
        val categoryName: String, // "No category" if none
        val totalStock: Double,
        val status: String // "in", "low", "out"
    )

    private var allRows: List<Row> = emptyList()
    private var filteredRows: List<Row> = emptyList()

    private val stockAlertOptions = listOf("All items", "Low stock", "Out of stock")
    private var categoryOptions: List<String> = listOf("All items", "No category")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_stock)

        prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        resultsListView = findViewById(R.id.resultsListView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        categorySpinner = findViewById(R.id.categorySpinner)
        stockAlertSpinner = findViewById(R.id.stockAlertSpinner)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        stockAlertSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, stockAlertOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        stockAlertSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        loadCatalog()
    }

    private fun loadCatalog() {
        if (ItemCache.variants.isNotEmpty()) {
            buildRows(ItemCache.variants, ItemCache.categories)
            loadingOverlay.visibility = View.GONE
            statusText.text = "${filteredRows.size} item(s)"
            refreshCatalogInBackground()
            return
        }

        loadingOverlay.visibility = View.VISIBLE
        statusText.text = "Loading catalog..."
        executor.execute {
            try {
                val token = prefs.getString("api_token", "") ?: ""
                val api = LoyverseApi(token)
                val variants = api.fetchItemsWithStock()
                val categories = api.fetchCategories()
                ItemCache.variants = variants
                ItemCache.categories = categories
                ItemCache.lastLoadedAt = System.currentTimeMillis()
                runOnUiThread {
                    buildRows(variants, categories)
                    loadingOverlay.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Failed to load catalog: ${e.message}"
                    loadingOverlay.visibility = View.GONE
                }
            }
        }
    }

    private fun refreshCatalogInBackground() {
        executor.execute {
            try {
                val token = prefs.getString("api_token", "") ?: ""
                val api = LoyverseApi(token)
                val variants = api.fetchItemsWithStock()
                val categories = api.fetchCategories()
                ItemCache.variants = variants
                ItemCache.categories = categories
                ItemCache.lastLoadedAt = System.currentTimeMillis()
                runOnUiThread {
                    buildRows(variants, categories)
                }
            } catch (e: Exception) {
                // Silent failure - keep showing whatever is cached.
            }
        }
    }

    /**
     * Turns raw variants + categories into display rows, works out each
     * row's stock status, rebuilds the category filter list from what's
     * actually in the catalog, and re-applies whatever filters are
     * currently selected.
     */
    private fun buildRows(variants: List<LoyverseApi.Variant>, categories: List<LoyverseApi.Category>) {
        val categoryNameById = categories.associate { it.id to it.name }

        allRows = variants.map { variant ->
            val categoryName = variant.categoryId?.let { categoryNameById[it] } ?: "No category"
            val status = when {
                variant.currentStock <= 0.0 -> "out"
                variant.lowStockThreshold != null && variant.currentStock < variant.lowStockThreshold -> "low"
                else -> "in"
            }
            Row(variant.itemName, categoryName, variant.currentStock, status)
        }

        // Rebuild category dropdown from what actually exists in the catalog.
        val realCategoryNames = allRows
            .map { it.categoryName }
            .filter { it != "No category" }
            .distinct()
            .sorted()
        categoryOptions = listOf("All items", "No category") + realCategoryNames

        val previousCategorySelection = categorySpinner.selectedItem as? String
        categorySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, categoryOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val restoreIndex = categoryOptions.indexOf(previousCategorySelection).takeIf { it >= 0 } ?: 0
        categorySpinner.setSelection(restoreIndex)

        applyFilters()
    }

    private fun applyFilters() {
        val selectedCategory = categorySpinner.selectedItem as? String ?: "All items"
        val selectedStockAlert = stockAlertSpinner.selectedItem as? String ?: "All items"

        filteredRows = allRows.filter { row ->
            val matchesCategory = when (selectedCategory) {
                "All items" -> true
                else -> row.categoryName == selectedCategory
            }
            val matchesStockAlert = when (selectedStockAlert) {
                "All items" -> true
                "Low stock" -> row.status == "low" || row.status == "out"
                "Out of stock" -> row.status == "out"
                else -> true
            }
            matchesCategory && matchesStockAlert
        }

        statusText.text = "${filteredRows.size} item(s)"
        resultsListView.adapter = ResultsAdapter()
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = filteredRows.size
        override fun getItem(position: Int) = filteredRows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = layoutInflater.inflate(R.layout.row_check_stock_item, parent, false)
            val row = filteredRows[position]

            view.findViewById<TextView>(R.id.itemNameText).text = row.displayName
            view.findViewById<TextView>(R.id.categoryText).text = row.categoryName
            view.findViewById<TextView>(R.id.stockText).text = "Stock: ${row.totalStock}"

            val badge = view.findViewById<TextView>(R.id.statusBadgeText)
            when (row.status) {
                "out" -> {
                    badge.text = "OUT OF STOCK"
                    badge.setTextColor(Color.parseColor("#C62828"))
                }
                "low" -> {
                    badge.text = "LOW STOCK"
                    badge.setTextColor(Color.parseColor("#F2A541"))
                }
                else -> {
                    badge.text = "IN STOCK"
                    badge.setTextColor(Color.parseColor("#2E7D32"))
                }
            }

            return view
        }
    }
}
