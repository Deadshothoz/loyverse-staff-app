package com.yourbiz.loyverseapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.concurrent.Executors

/**
 * Lists every pool with its combined stock. Search by name at the top,
 * tap a pool to edit it, "+" to create a new one. Pools are read-only as
 * far as Loyverse is concerned - nothing here changes any stock.
 */
class PoolsActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var poolsListView: ListView
    private lateinit var loadingOverlay: View

    private var shownPools: List<PoolStore.Pool> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pools)

        searchInput = findViewById(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        poolsListView = findViewById(R.id.poolsListView)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.linkButton).setOnClickListener {
            PoolStore.promptForUrl(this) { loadData(showOverlay = true) }
        }
        findViewById<FloatingActionButton>(R.id.addPoolButton).setOnClickListener {
            if (PoolStore.getUrl(this) == null) {
                PoolStore.promptForUrl(this) { loadData(showOverlay = true) }
            } else {
                startActivity(Intent(this, PoolEditorActivity::class.java))
            }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })

        poolsListView.setOnItemClickListener { _, _, position, _ ->
            val pool = shownPools.getOrNull(position) ?: return@setOnItemClickListener
            val intent = Intent(this, PoolEditorActivity::class.java)
            intent.putExtra(PoolEditorActivity.EXTRA_POOL_ID, pool.id)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Runs on first open AND when coming back from the pool editor, so
        // the list always reflects the latest saved pools.
        if (PoolStore.getUrl(this) == null) {
            render()
            statusText.text = "Pools aren't set up on this device yet. Tap LINK to paste your pools link."
            PoolStore.promptForUrl(this) { loadData(showOverlay = true) }
            return
        }
        render()
        loadData(showOverlay = !PoolStore.loaded || ItemCache.variants.isEmpty())
    }

    /** Refreshes the catalog (only what changed) and the pools list. */
    private fun loadData(showOverlay: Boolean) {
        val url = PoolStore.getUrl(this) ?: return
        if (showOverlay) loadingOverlay.visibility = View.VISIBLE
        executor.execute {
            try {
                val token = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
                    .getString("api_token", "") ?: ""
                ItemCache.refresh(LoyverseApi(token))
                PoolStore.load(url)
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    render()
                    statusText.text = "Couldn't load pools: ${e.message}"
                }
            }
        }
    }

    private fun render() {
        val query = searchInput.text.toString().trim()
        shownPools = PoolStore.pools
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }

        statusText.text = when {
            PoolStore.pools.isEmpty() && PoolStore.loaded -> "No pools yet. Tap + to create one."
            shownPools.isEmpty() && query.isNotEmpty() -> "No pools match \"$query\"."
            else -> "${shownPools.size} pool(s)"
        }
        poolsListView.adapter = PoolsAdapter()
    }

    private inner class PoolsAdapter : BaseAdapter() {
        private val variantsById = ItemCache.variants.associateBy { it.variantId }

        override fun getCount() = shownPools.size
        override fun getItem(position: Int) = shownPools[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Same row look as Check Stock.
            val view = layoutInflater.inflate(R.layout.row_check_stock_item, parent, false)
            val pool = shownPools[position]
            val total = PoolStore.totalStock(pool, variantsById)

            view.findViewById<TextView>(R.id.itemNameText).text = pool.name
            view.findViewById<TextView>(R.id.categoryText).text =
                "${pool.variantIds.size} item(s) · Low stock at ${PoolStore.formatQty(pool.minStock)}"
            view.findViewById<TextView>(R.id.stockText).text = "Stock: ${PoolStore.formatQty(total)}"

            val badge = view.findViewById<TextView>(R.id.statusBadgeText)
            when (PoolStore.status(total, pool.minStock)) {
                "out" -> { badge.text = "OUT OF STOCK"; badge.setTextColor(Color.parseColor("#C62828")) }
                "low" -> { badge.text = "LOW STOCK"; badge.setTextColor(Color.parseColor("#F2A541")) }
                else -> { badge.text = "IN STOCK"; badge.setTextColor(Color.parseColor("#2E7D32")) }
            }
            return view
        }
    }
}
