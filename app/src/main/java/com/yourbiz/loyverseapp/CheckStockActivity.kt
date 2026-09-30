package com.yourbiz.loyverseapp

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Read-only stock overview.
 *  - Items that aren't in any pool show as their own row.
 *  - Items that ARE in a pool are hidden; the pool shows instead, with the
 *    combined stock of all its items and its own low-stock number.
 *  - "All items" shows everything (tracked or not). "Low stock" and
 *    "Out of stock" only include items/pools that have a minimum set.
 */
class CheckStockActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var statusText: TextView
    private lateinit var resultsListView: ListView
    private lateinit var loadingOverlay: View
    private lateinit var categorySpinner: Spinner
    private lateinit var stockAlertSpinner: Spinner

    data class Row(
        val displayName: String,
        val subtitle: String,
        val categoryName: String,   // "No category" if none; unused for pools
        val stockText: String,
        /** "out", "low", "in" (has a minimum and is above it),
         *  "none" (no minimum set) or "untracked". */
        val status: String,
        val isPool: Boolean
    )

    private var allRows: List<Row> = emptyList()
    private var filteredRows: List<Row> = emptyList()

    /** Set when pools couldn't be loaded, so the user knows why pooled
     *  items are showing individually. */
    private var poolsNote: String? = null

    private val stockAlertOptions = listOf("All items", "Low stock", "Out of stock")
    private var categoryOptions: List<String> = listOf("All items", "No category")

    private companion object {
        const val CATEGORY_ALL = "All items"
        const val CATEGORY_NONE = "No category"
        const val CATEGORY_POOLS = "Pools"
    }

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

        loadData()
    }

    private fun loadData() {
        val haveCache = ItemCache.variants.isNotEmpty()
        val poolsUrl = PoolStore.getUrl(this)
        if (haveCache && (poolsUrl == null || PoolStore.loaded)) {
            // Show what we already have instantly, then quietly refresh.
            buildRows()
            loadingOverlay.visibility = View.GONE
        } else {
            loadingOverlay.visibility = View.VISIBLE
            statusText.text = "Loading catalog..."
        }

        executor.execute {
            var catalogError: String? = null
            try {
                val token = prefs.getString("api_token", "") ?: ""
                // Only downloads what changed since the last sync (full
                // download only the very first time this session).
                ItemCache.refresh(LoyverseApi(token))
            } catch (e: Exception) {
                catalogError = e.message
            }

            var poolError: String? = null
            if (poolsUrl != null) {
                try {
                    PoolStore.load(poolsUrl)
                } catch (e: Exception) {
                    poolError = e.message
                }
            }

            runOnUiThread {
                loadingOverlay.visibility = View.GONE
                poolsNote = when {
                    poolsUrl == null -> null
                    poolError != null && !PoolStore.loaded ->
                        "Pools couldn't load, so pooled items are shown one by one ($poolError)"
                    else -> null
                }
                if (ItemCache.variants.isEmpty() && catalogError != null) {
                    statusText.text = "Failed to load catalog: $catalogError"
                } else {
                    buildRows()
                }
            }
        }
    }

    /**
     * Turns the cached catalog + pools into display rows, rebuilds the
     * category filter from what actually exists, and re-applies whatever
     * filters are currently selected.
     */
    private fun buildRows() {
        val variants = ItemCache.variants
        val categoryNameById = ItemCache.categories.associate { it.id to it.name }
        val variantsById = variants.associateBy { it.variantId }
        val pools = PoolStore.pools
        val pooledIds = pools.flatMap { it.variantIds }.toHashSet()

        val poolRows = pools.map { pool ->
            val total = PoolStore.totalStock(pool, variantsById)
            Row(
                displayName = pool.name,
                subtitle = "Pool · ${pool.variantIds.size} item(s) · Low stock at ${PoolStore.formatQty(pool.minStock)}",
                categoryName = CATEGORY_POOLS,
                stockText = "Stock: ${PoolStore.formatQty(total)}",
                status = PoolStore.status(total, pool.minStock),
                isPool = true
            )
        }

        val itemRows = variants
            .filter { it.variantId !in pooledIds } // pooled items show via their pool instead
            .map { variant ->
                val categoryName = variant.categoryId?.let { categoryNameById[it] } ?: CATEGORY_NONE
                // Loyverse only raises a stock alert once a low-stock number is
                // set for the item, so without one we never flag it - even at 0.
                val threshold = variant.lowStockThreshold
                val status = when {
                    !variant.trackStock -> "untracked"
                    threshold == null -> "none"
                    variant.currentStock <= 0.0 -> "out"
                    variant.currentStock < threshold -> "low"
                    else -> "in"
                }
                Row(
                    displayName = variant.itemName,
                    subtitle = categoryName,
                    categoryName = categoryName,
                    stockText = if (variant.trackStock) "Stock: ${PoolStore.formatQty(variant.currentStock)}"
                                else "Not tracked",
                    status = status,
                    isPool = false
                )
            }

        allRows = (poolRows + itemRows).sortedBy { it.displayName.lowercase() } // Loyverse-style A-Z

        // Rebuild category dropdown from what actually exists.
        val realCategoryNames = itemRows
            .map { it.categoryName }
            .filter { it != CATEGORY_NONE }
            .distinct()
            .sorted()
        categoryOptions = listOf(CATEGORY_ALL, CATEGORY_NONE) +
            (if (poolRows.isNotEmpty()) listOf(CATEGORY_POOLS) else emptyList()) +
            realCategoryNames

        val previousCategorySelection = categorySpinner.selectedItem as? String
        categorySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, categoryOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val restoreIndex = categoryOptions.indexOf(previousCategorySelection).takeIf { it >= 0 } ?: 0
        categorySpinner.setSelection(restoreIndex)

        applyFilters()
    }

    private fun applyFilters() {
        val selectedCategory = categorySpinner.selectedItem as? String ?: CATEGORY_ALL
        val selectedStockAlert = stockAlertSpinner.selectedItem as? String ?: "All items"

        filteredRows = allRows.filter { row ->
            val matchesCategory = when (selectedCategory) {
                CATEGORY_ALL -> true
                CATEGORY_POOLS -> row.isPool
                else -> !row.isPool && row.categoryName == selectedCategory
            }
            val matchesStockAlert = when (selectedStockAlert) {
                "Low stock" -> row.status == "low" || row.status == "out"
                "Out of stock" -> row.status == "out"
                else -> true // "All items": everything, tracked or not
            }
            matchesCategory && matchesStockAlert
        }

        val count = "${filteredRows.size} item(s)"
        statusText.text = poolsNote?.let { "$count\n$it" } ?: count
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
            val subtitle = view.findViewById<TextView>(R.id.categoryText)
            subtitle.text = row.subtitle
            if (row.isPool) subtitle.setTextColor(Color.parseColor("#43A047"))
            view.findViewById<TextView>(R.id.stockText).text = row.stockText

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
                "in" -> {
                    badge.text = "IN STOCK"
                    badge.setTextColor(Color.parseColor("#2E7D32"))
                }
                else -> {
                    // No minimum set, or not tracked: no alert badge.
                    badge.text = ""
                }
            }

            return view
        }
    }
}
