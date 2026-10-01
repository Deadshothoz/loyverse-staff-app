package com.yourbiz.loyverseapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * Lists every composite item in Loyverse (e.g. a box made of 24 bars),
 * with what it's made of and how many could be made from the stock on
 * hand. Tap one to edit it, "+" to turn another item into a composite.
 */
class CompositesActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var compositesListView: ListView
    private lateinit var loadingOverlay: View

    private var shown: List<LoyverseApi.Variant> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_composites)

        searchInput = findViewById(R.id.searchInput)
        statusText = findViewById(R.id.statusText)
        compositesListView = findViewById(R.id.compositesListView)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.addCompositeButton).setOnClickListener {
            startActivity(Intent(this, CompositeEditorActivity::class.java))
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })

        compositesListView.setOnItemClickListener { _, _, position, _ ->
            val variant = shown.getOrNull(position) ?: return@setOnItemClickListener
            val intent = Intent(this, CompositeEditorActivity::class.java)
            intent.putExtra(CompositeEditorActivity.EXTRA_ITEM_ID, variant.itemId)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Runs on first open AND when coming back from the editor.
        render()
        loadData(showOverlay = ItemCache.variants.isEmpty())
    }

    /** Refreshes the catalog (only what changed since the last sync). */
    private fun loadData(showOverlay: Boolean) {
        if (showOverlay) loadingOverlay.visibility = View.VISIBLE
        executor.execute {
            try {
                val token = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
                    .getString("api_token", "") ?: ""
                ItemCache.refresh(LoyverseApi(token))
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    render()
                    statusText.text = "Couldn't load items: ${e.message}"
                }
            }
        }
    }

    private fun render() {
        val query = searchInput.text.toString().trim()
        val composites = ItemCache.variants
            .filter { it.isComposite }
            .distinctBy { it.itemId }
        shown = composites
            .filter { query.isEmpty() || it.itemName.contains(query, ignoreCase = true) }
            .sortedBy { it.itemName.lowercase() }

        statusText.text = when {
            ItemCache.variants.isEmpty() -> "Loading..."
            composites.isEmpty() -> "No composite items yet. Tap + to make one."
            shown.isEmpty() -> "No composite items match \"$query\"."
            else -> "${shown.size} composite item(s)"
        }
        compositesListView.adapter = CompositesAdapter()
    }

    private inner class CompositesAdapter : BaseAdapter() {
        private val byId = ItemCache.variants.associateBy { it.variantId }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Same row look as Check Stock and Pools.
            val view = layoutInflater.inflate(R.layout.row_check_stock_item, parent, false)
            val item = shown[position]

            view.findViewById<TextView>(R.id.itemNameText).text = item.itemName
            view.findViewById<TextView>(R.id.categoryText).text = componentSummary(item, byId)

            val canMake = canMake(item, byId)
            view.findViewById<TextView>(R.id.stockText).text =
                if (canMake == null) "Can make: -" else "Can make: ${PoolStore.formatQty(canMake)}"

            val badge = view.findViewById<TextView>(R.id.statusBadgeText)
            when {
                canMake == null -> { badge.text = ""; badge.setTextColor(Color.GRAY) }
                canMake <= 0.0 -> { badge.text = "CAN'T MAKE"; badge.setTextColor(Color.parseColor("#C62828")) }
                else -> { badge.text = "${item.components.size} PART(S)"; badge.setTextColor(Color.parseColor("#2E7D32")) }
            }
            return view
        }
    }

    companion object {
        /** e.g. "Choco bar ×24, Gift card ×1" */
        fun componentSummary(
            item: LoyverseApi.Variant,
            byId: Map<String, LoyverseApi.Variant>
        ): String {
            if (item.components.isEmpty()) return "No components"
            return item.components.joinToString(", ") { c ->
                val name = byId[c.variantId]?.itemName ?: "Unknown item"
                "$name ×${PoolStore.formatQty(c.quantity)}"
            }
        }

        /**
         * How many of this composite the current component stock could make:
         * the smallest of (stock ÷ quantity needed) across the components
         * that track stock. Null if none of the components track stock.
         */
        fun canMake(
            item: LoyverseApi.Variant,
            byId: Map<String, LoyverseApi.Variant>
        ): Double? {
            var best: Double? = null
            for (c in item.components) {
                val part = byId[c.variantId] ?: continue
                if (!part.trackStock || c.quantity <= 0.0) continue
                val possible = floor(part.currentStock.coerceAtLeast(0.0) / c.quantity)
                best = if (best == null) possible else minOf(best, possible)
            }
            return best
        }
    }
}
