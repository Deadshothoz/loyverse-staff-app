package com.yourbiz.loyverseapp

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Create a new pool (no extra) or edit an existing one (EXTRA_POOL_ID).
 * Name, low-stock minimum and items are all editable. Saving only writes
 * to the pools Google Sheet - nothing in Loyverse is ever changed here.
 */
class PoolEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POOL_ID = "pool_id"
    }

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var nameInput: EditText
    private lateinit var minInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var pickerListView: ListView
    private lateinit var statusText: TextView
    private lateinit var membersLabel: TextView
    private lateinit var membersListView: ListView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingOverlayText: TextView

    /** The pool as it was when this screen opened - null when creating. */
    private var original: PoolStore.Pool? = null
    private val memberIds = LinkedHashSet<String>()

    private var pickerResults: List<LoyverseApi.Variant> = emptyList()
    private val scanBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pool_editor)

        nameInput = findViewById(R.id.nameInput)
        minInput = findViewById(R.id.minInput)
        searchInput = findViewById(R.id.searchInput)
        pickerListView = findViewById(R.id.pickerListView)
        statusText = findViewById(R.id.statusText)
        membersLabel = findViewById(R.id.membersLabel)
        membersListView = findViewById(R.id.membersListView)
        saveButton = findViewById(R.id.saveButton)
        deleteButton = findViewById(R.id.deleteButton)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingOverlayText = findViewById(R.id.loadingOverlayText)

        val poolId = intent.getStringExtra(EXTRA_POOL_ID)
        original = poolId?.let { id -> PoolStore.pools.firstOrNull { it.id == id } }
        if (poolId != null && original == null) {
            Toast.makeText(this, "That pool no longer exists", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val existing = original
        findViewById<TextView>(R.id.editorTitle).text = if (existing == null) "NEW POOL" else "EDIT POOL"
        if (existing != null) {
            nameInput.setText(existing.name)
            minInput.setText(PoolStore.formatQty(existing.minStock))
            memberIds.addAll(existing.variantIds)
            deleteButton.visibility = View.VISIBLE
        }

        findViewById<TextView>(R.id.backButton).setOnClickListener { confirmLeave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmLeave() }
        })
        saveButton.setOnClickListener { askConfirmThenSave() }
        deleteButton.setOnClickListener { askConfirmThenDelete() }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(searchInput.text.toString())
                true
            } else false
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { runSearch(s?.toString() ?: "") }
        })

        pickerListView.setOnItemClickListener { _, _, position, _ ->
            val variant = pickerResults.getOrNull(position) ?: return@setOnItemClickListener
            addMember(variant)
        }

        refreshMembers()
        ensureDataLoaded()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Scanner input only when no text box has focus - same as the
        // stock screens, so typing a name or minimum still works normally.
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

    /** Usually both are already loaded by the Pools screen; fetch if not. */
    private fun ensureDataLoaded() {
        val url = PoolStore.getUrl(this)
        val needCatalog = ItemCache.variants.isEmpty()
        val needPools = url != null && !PoolStore.loaded
        if (!needCatalog && !needPools) return

        showOverlay("Loading...")
        executor.execute {
            try {
                if (needCatalog) {
                    val token = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
                        .getString("api_token", "") ?: ""
                    ItemCache.refresh(LoyverseApi(token))
                }
                if (needPools && url != null) PoolStore.load(url)
                runOnUiThread { hideOverlay(); refreshMembers() }
            } catch (e: Exception) {
                runOnUiThread {
                    hideOverlay()
                    statusText.text = "Couldn't load: ${e.message}"
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
        val all = ItemCache.variants
        val exact = all.filter { it.barcode.isNotEmpty() && it.barcode == trimmed }
        pickerResults = if (exact.isNotEmpty()) exact
                        else all.filter { it.itemName.contains(trimmed, ignoreCase = true) }

        if (pickerResults.isEmpty()) {
            pickerListView.visibility = View.GONE
            statusText.text = "No matching item found."
        } else {
            pickerListView.visibility = View.VISIBLE
            statusText.text = "Tap an item to add it to this pool."
        }
        pickerListView.adapter = PickerAdapter()
    }

    /** A pool that already owns this variant, other than the one being edited. */
    private fun otherPoolOwning(variantId: String): PoolStore.Pool? =
        PoolStore.pools.firstOrNull { it.id != original?.id && variantId in it.variantIds }

    private fun addMember(variant: LoyverseApi.Variant) {
        if (variant.variantId in memberIds) {
            Toast.makeText(this, "Already in this pool", Toast.LENGTH_SHORT).show()
            return
        }
        val other = otherPoolOwning(variant.variantId)
        if (other != null) {
            // One item can only be in one pool, otherwise its stock would
            // be counted twice in Check Stock.
            Toast.makeText(this, "Already in pool \"${other.name}\" - remove it there first", Toast.LENGTH_LONG).show()
            return
        }
        memberIds.add(variant.variantId)
        searchInput.setText("")
        searchInput.clearFocus() // so the next barcode scan goes to the scanner, not a text box
        statusText.text = "Added ${variant.itemName}"
        refreshMembers()
    }

    private fun removeMember(variantId: String) {
        memberIds.remove(variantId)
        refreshMembers()
    }

    private fun refreshMembers() {
        val byId = ItemCache.variants.associateBy { it.variantId }
        val total = memberIds.sumOf { byId[it]?.currentStock ?: 0.0 }
        membersLabel.text = "Items in pool (${memberIds.size}) · Total stock: ${PoolStore.formatQty(total)}"
        membersListView.adapter = MembersAdapter(byId)
    }

    private fun showOverlay(text: String) {
        loadingOverlayText.text = text
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        loadingOverlay.visibility = View.GONE
    }

    // --- Saving ---

    private fun currentName() = nameInput.text.toString().trim()
    private fun currentMin(): Double? = minInput.text.toString().trim().toDoubleOrNull()

    private fun hasChanges(): Boolean {
        val o = original
        if (o == null) {
            return currentName().isNotEmpty() || minInput.text.isNotBlank() || memberIds.isNotEmpty()
        }
        return currentName() != o.name ||
            currentMin() != o.minStock ||
            memberIds.toSet() != o.variantIds.toSet()
    }

    private fun confirmLeave() {
        if (!hasChanges()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Discard changes?")
            .setMessage("Your changes to this pool haven't been saved.")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    private fun askConfirmThenSave() {
        val url = PoolStore.getUrl(this)
        if (url == null) {
            PoolStore.promptForUrl(this) { }
            return
        }

        val name = currentName()
        val min = currentMin()
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a pool name", Toast.LENGTH_SHORT).show()
            return
        }
        if (PoolStore.pools.any { it.id != original?.id && it.name.equals(name, ignoreCase = true) }) {
            Toast.makeText(this, "A pool called \"$name\" already exists", Toast.LENGTH_SHORT).show()
            return
        }
        if (min == null || min < 0) {
            Toast.makeText(this, "Enter the low stock number for this pool", Toast.LENGTH_SHORT).show()
            return
        }
        if (memberIds.isEmpty()) {
            Toast.makeText(this, "Add at least one item to the pool", Toast.LENGTH_SHORT).show()
            return
        }

        val o = original
        if (o != null && !hasChanges()) {
            Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show()
            return
        }

        val message = if (o == null) {
            "Create pool \"$name\"?\n\n" +
                "Low stock at: ${PoolStore.formatQty(min)}\n" +
                "Items: ${memberIds.size}"
        } else {
            val added = memberIds.count { it !in o.variantIds }
            val removed = o.variantIds.count { it !in memberIds }
            buildString {
                append("Save changes to \"${o.name}\"?\n")
                if (name != o.name) append("\nName: ${o.name} → $name")
                if (min != o.minStock) append("\nLow stock at: ${PoolStore.formatQty(o.minStock)} → ${PoolStore.formatQty(min)}")
                if (added > 0) append("\nItems added: $added")
                if (removed > 0) append("\nItems removed: $removed")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (o == null) "Create pool" else "Confirm changes")
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ ->
                val pool = PoolStore.Pool(
                    id = o?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    minStock = min,
                    variantIds = memberIds.toList()
                )
                save(url, pool)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(url: String, pool: PoolStore.Pool) {
        showOverlay("Saving pool...")
        executor.execute {
            try {
                PoolStore.save(url, pool)
                runOnUiThread {
                    Toast.makeText(this, "Pool \"${pool.name}\" saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideOverlay()
                    statusText.text = "Save failed: ${e.message}"
                }
            }
        }
    }

    private fun askConfirmThenDelete() {
        val o = original ?: return
        val url = PoolStore.getUrl(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete pool")
            .setMessage("Delete pool \"${o.name}\"?\n\nThe items themselves stay in Loyverse unchanged - they'll just show up individually in Check Stock again.")
            .setPositiveButton("Delete") { _, _ ->
                showOverlay("Deleting pool...")
                executor.execute {
                    try {
                        PoolStore.delete(url, o.id)
                        runOnUiThread {
                            Toast.makeText(this, "Pool deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            hideOverlay()
                            statusText.text = "Delete failed: ${e.message}"
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Lists ---

    private fun stockLabel(variant: LoyverseApi.Variant): String =
        if (variant.trackStock) "Stock: ${PoolStore.formatQty(variant.currentStock)}"
        else "Stock: not tracked"

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount() = pickerResults.size
        override fun getItem(position: Int) = pickerResults[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = layoutInflater.inflate(R.layout.row_picker_item, parent, false)
            val variant = pickerResults[position]
            view.findViewById<TextView>(R.id.pickerNameText).text = variant.itemName
            val note = when {
                variant.variantId in memberIds -> " · already in this pool"
                else -> otherPoolOwning(variant.variantId)?.let { " · in pool \"${it.name}\"" } ?: ""
            }
            view.findViewById<TextView>(R.id.pickerStockText).text = stockLabel(variant) + note
            return view
        }
    }

    private inner class MembersAdapter(
        private val byId: Map<String, LoyverseApi.Variant>
    ) : BaseAdapter() {
        private val ids = memberIds.toList()

        override fun getCount() = ids.size
        override fun getItem(position: Int) = ids[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Reuses the stock screens' row (trash icon on the left), with
            // the quantity box hidden since pools have no quantity to enter.
            val view = layoutInflater.inflate(R.layout.row_item, parent, false)
            val id = ids[position]
            val variant = byId[id]

            view.findViewById<EditText>(R.id.addQtyInput).visibility = View.GONE
            view.findViewById<ImageView>(R.id.deleteButton).setOnClickListener { removeMember(id) }
            view.findViewById<TextView>(R.id.itemNameText).text =
                variant?.itemName ?: "Unknown item (deleted from Loyverse?)"
            view.findViewById<TextView>(R.id.currentStockText).text = when {
                variant == null -> "Tap the trash icon to remove it from the pool"
                variant.barcode.isNotEmpty() -> "${stockLabel(variant)} · ${variant.barcode}"
                else -> stockLabel(variant)
            }
            return view
        }
    }
}
