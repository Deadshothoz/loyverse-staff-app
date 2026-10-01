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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Makes an item composite (e.g. a box = 24 bars), edits its components, or
 * turns composite off again. Opened with no extra to start a new one, or
 * with EXTRA_ITEM_ID to edit an existing composite.
 *
 * Step 1: search/scan the item that becomes composite (the box).
 * Step 2: search/scan each component and type how many go into ONE box.
 * Saving writes to Loyverse - from then on Loyverse itself takes the
 * components' stock down whenever the box is sold.
 */
class CompositeEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
    }

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var editorTitle: TextView
    private lateinit var parentText: TextView
    private lateinit var parentDetailText: TextView
    private lateinit var searchLabel: TextView
    private lateinit var searchInput: EditText
    private lateinit var pickerListView: ListView
    private lateinit var statusText: TextView
    private lateinit var componentsLabel: TextView
    private lateinit var componentsListView: ListView
    private lateinit var saveButton: Button
    private lateinit var turnOffButton: TextView
    private lateinit var loadingOverlay: View
    private lateinit var loadingOverlayText: TextView

    /** The item being made composite (its only variant). */
    private var parent: LoyverseApi.Variant? = null
    /** Components as they were in Loyverse when chosen - empty for a new one. */
    private var originalComponents: Map<String, Double> = emptyMap()
    /** variantId -> quantity text typed in the box (kept as text while editing). */
    private val components = LinkedHashMap<String, String>()

    private var pickerResults: List<LoyverseApi.Variant> = emptyList()
    private val scanBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_composite_editor)

        editorTitle = findViewById(R.id.editorTitle)
        parentText = findViewById(R.id.parentText)
        parentDetailText = findViewById(R.id.parentDetailText)
        searchLabel = findViewById(R.id.searchLabel)
        searchInput = findViewById(R.id.searchInput)
        pickerListView = findViewById(R.id.pickerListView)
        statusText = findViewById(R.id.statusText)
        componentsLabel = findViewById(R.id.componentsLabel)
        componentsListView = findViewById(R.id.componentsListView)
        saveButton = findViewById(R.id.saveButton)
        turnOffButton = findViewById(R.id.turnOffButton)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingOverlayText = findViewById(R.id.loadingOverlayText)

        findViewById<TextView>(R.id.backButton).setOnClickListener { confirmLeave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmLeave() }
        })
        saveButton.setOnClickListener { askConfirmThenSave() }
        turnOffButton.setOnClickListener { askConfirmThenTurnOff() }
        parentText.setOnClickListener { changeParent() }

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
            if (parent == null) chooseParent(variant) else addComponent(variant)
        }

        refreshScreen()
        if (ItemCache.variants.isEmpty()) loadCatalogThenStart() else start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Scanner input only when no text box has focus - same as the
        // other screens, so typing a quantity still works normally.
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

    private fun api(): LoyverseApi {
        val token = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
            .getString("api_token", "") ?: ""
        return LoyverseApi(token)
    }

    private fun loadCatalogThenStart() {
        showOverlay("Loading catalog...")
        executor.execute {
            try {
                ItemCache.refresh(api())
                runOnUiThread { hideOverlay(); start() }
            } catch (e: Exception) {
                runOnUiThread {
                    hideOverlay()
                    statusText.text = "Couldn't load items: ${e.message}"
                }
            }
        }
    }

    /** Opens the item passed in (if any) once the catalog is available. */
    private fun start() {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { refreshScreen(); return }
        val variant = ItemCache.variants.firstOrNull { it.itemId == itemId }
        if (variant == null) {
            Toast.makeText(this, "That item no longer exists", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadParent(variant)
    }

    // --- Choosing the composite item ---

    /** Composite items that use this variant as one of their components. */
    private fun compositesUsing(variantId: String): List<LoyverseApi.Variant> =
        ItemCache.variants.filter { v -> v.isComposite && v.components.any { it.variantId == variantId } }
            .distinctBy { it.itemId }

    private fun chooseParent(variant: LoyverseApi.Variant) {
        if (variant.variantCount > 1) {
            Toast.makeText(this, "${variant.itemName} has variants - Loyverse doesn't allow items with variants to be composite", Toast.LENGTH_LONG).show()
            return
        }
        val usedIn = compositesUsing(variant.variantId)
        if (usedIn.isNotEmpty()) {
            Toast.makeText(this, "${variant.itemName} is a component of \"${usedIn.first().itemName}\" - a composite can't be inside another composite", Toast.LENGTH_LONG).show()
            return
        }
        loadParent(variant)
        // If an item that's already a component was in the list, take it out.
        components.remove(variant.variantId)
        clearSearch()
        refreshScreen()
    }

    private fun loadParent(variant: LoyverseApi.Variant) {
        parent = variant
        if (variant.isComposite) {
            // Already composite - start from what Loyverse has.
            originalComponents = variant.components.associate { it.variantId to it.quantity }
            components.clear()
            variant.components.forEach { components[it.variantId] = PoolStore.formatQty(it.quantity) }
        } else {
            originalComponents = emptyMap()
        }
        refreshScreen()
    }

    /** Tap the item name to pick a different one (new composites only). */
    private fun changeParent() {
        val p = parent ?: return
        if (intent.getStringExtra(EXTRA_ITEM_ID) != null) return
        if (p.isComposite) {
            // Its components were loaded from Loyverse - drop them too.
            components.clear()
        }
        parent = null
        originalComponents = emptyMap()
        refreshScreen()
    }

    // --- Components ---

    private fun addComponent(variant: LoyverseApi.Variant) {
        val p = parent ?: return
        when {
            variant.itemId == p.itemId -> {
                Toast.makeText(this, "An item can't be a component of itself", Toast.LENGTH_SHORT).show()
                return
            }
            variant.isComposite -> {
                Toast.makeText(this, "${variant.itemName} is itself composite - add its components instead", Toast.LENGTH_LONG).show()
                return
            }
            variant.variantId in components -> {
                Toast.makeText(this, "Already a component - change its quantity in the list", Toast.LENGTH_SHORT).show()
                return
            }
        }
        components[variant.variantId] = "1"
        clearSearch()
        statusText.text = "Added ${variant.itemName} - set how many go into one ${p.itemName}"
        refreshComponents()
    }

    private fun removeComponent(variantId: String) {
        components.remove(variantId)
        refreshComponents()
    }

    // --- Search ---

    private fun clearSearch() {
        searchInput.setText("")
        searchInput.clearFocus() // so the next barcode scan goes to the scanner, not a text box
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
            statusText.text = if (parent == null) "Tap the item that will be composite."
                              else "Tap an item to add it as a component."
        }
        pickerListView.adapter = PickerAdapter()
    }

    // --- Screen ---

    private fun isEditingExisting() = parent?.isComposite == true

    private fun refreshScreen() {
        val p = parent
        editorTitle.text = if (isEditingExisting()) "EDIT COMPOSITE" else "NEW COMPOSITE"
        turnOffButton.visibility = if (isEditingExisting()) View.VISIBLE else View.GONE

        if (p == null) {
            parentText.text = "Not chosen yet"
            parentDetailText.text = ""
            searchLabel.text = "Choose the item that will be composite (e.g. the box)"
        } else {
            parentText.text = p.itemName
            val canChange = intent.getStringExtra(EXTRA_ITEM_ID) == null
            parentDetailText.text = buildString {
                append(
                    when {
                        p.isComposite -> "Composite item"
                        p.trackStock -> "Stock: ${PoolStore.formatQty(p.currentStock)} - its own stock stops being tracked once it's composite"
                        else -> "Stock not tracked"
                    }
                )
                if (canChange) append(" · tap the name to choose another item")
            }
            searchLabel.text = "Add a component (e.g. the bar that goes in the box)"
        }
        refreshComponents()
    }

    private fun refreshComponents() {
        val byId = ItemCache.variants.associateBy { it.variantId }
        componentsLabel.text = "Components (${components.size}) - quantity per 1 composite item"
        componentsListView.adapter = ComponentsAdapter(byId)
    }

    private fun showOverlay(text: String) {
        loadingOverlayText.text = text
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        loadingOverlay.visibility = View.GONE
    }

    // --- Saving ---

    private fun parsedComponents(): Map<String, Double?> =
        components.mapValues { (_, text) -> text.trim().toDoubleOrNull() }

    private fun hasChanges(): Boolean {
        if (parent == null) return components.isNotEmpty()
        if (!isEditingExisting()) return true
        return parsedComponents() != originalComponents
    }

    private fun confirmLeave() {
        if (!hasChanges()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Discard changes?")
            .setMessage("Your changes to this composite item haven't been saved.")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    private fun askConfirmThenSave() {
        val p = parent
        if (p == null) {
            Toast.makeText(this, "Choose the item that will be composite first", Toast.LENGTH_SHORT).show()
            return
        }
        if (components.isEmpty()) {
            Toast.makeText(this, "Add at least one component", Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = parsedComponents()
        val byId = ItemCache.variants.associateBy { it.variantId }
        val bad = parsed.entries.firstOrNull { (_, qty) -> qty == null || qty <= 0.0 }
        if (bad != null) {
            val name = byId[bad.key]?.itemName ?: "a component"
            Toast.makeText(this, "Enter a quantity above 0 for $name", Toast.LENGTH_SHORT).show()
            return
        }
        if (isEditingExisting() && !hasChanges()) {
            Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show()
            return
        }

        val list = parsed.map { (id, qty) -> LoyverseApi.Component(id, qty ?: 0.0) }
        val message = buildString {
            append(if (isEditingExisting()) "Save changes to \"${p.itemName}\"?\n" else "Make \"${p.itemName}\" a composite item?\n")
            append("\nOne ${p.itemName} uses:")
            for (c in list) {
                append("\n• ${byId[c.variantId]?.itemName ?: "Unknown item"} ×${PoolStore.formatQty(c.quantity)}")
            }
            if (!p.isComposite && p.trackStock) {
                append("\n\n${p.itemName}'s own stock (${PoolStore.formatQty(p.currentStock)}) will stop being tracked - Loyverse will take stock from the components instead when it's sold.")
            } else if (!p.isComposite) {
                append("\n\nWhen it's sold, Loyverse will take stock from the components.")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (isEditingExisting()) "Confirm changes" else "Make composite")
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> save(p, list, "Saving composite...", "\"${p.itemName}\" saved") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askConfirmThenTurnOff() {
        val p = parent ?: return
        AlertDialog.Builder(this)
            .setTitle("Turn off composite")
            .setMessage("Make \"${p.itemName}\" a normal item again?\n\nSelling it will no longer take stock from its components. Its own stock won't be tracked until you add stock to it (Add Stock or Stock Count turns tracking on).")
            .setPositiveButton("Turn off") { _, _ ->
                save(p, emptyList(), "Turning off composite...", "\"${p.itemName}\" is a normal item again")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(
        p: LoyverseApi.Variant,
        list: List<LoyverseApi.Component>,
        overlayText: String,
        doneText: String
    ) {
        showOverlay(overlayText)
        executor.execute {
            try {
                val api = api()
                api.updateItemComposite(p.itemId, list)
                // Pull the change back in so every screen sees it right away.
                try { ItemCache.refresh(api) } catch (ignored: Exception) {}
                runOnUiThread {
                    Toast.makeText(this, doneText, Toast.LENGTH_SHORT).show()
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

    // --- Lists ---

    private fun stockLabel(variant: LoyverseApi.Variant): String = when {
        variant.isComposite -> "Composite item"
        variant.trackStock -> "Stock: ${PoolStore.formatQty(variant.currentStock)}"
        else -> "Stock: not tracked"
    }

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount() = pickerResults.size
        override fun getItem(position: Int) = pickerResults[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = layoutInflater.inflate(R.layout.row_picker_item, parent, false)
            val variant = pickerResults[position]
            view.findViewById<TextView>(R.id.pickerNameText).text = variant.itemName
            val note = when {
                variant.variantId in components -> " · already a component"
                this@CompositeEditorActivity.parent == null && variant.variantCount > 1 -> " · has variants"
                else -> ""
            }
            view.findViewById<TextView>(R.id.pickerStockText).text = stockLabel(variant) + note
            return view
        }
    }

    private inner class ComponentsAdapter(
        private val byId: Map<String, LoyverseApi.Variant>
    ) : BaseAdapter() {
        private val ids = components.keys.toList()

        override fun getCount() = ids.size
        override fun getItem(position: Int) = ids[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Same row as the stock screens: trash icon, name, qty box.
            val view = layoutInflater.inflate(R.layout.row_item, parent, false)
            val id = ids[position]
            val variant = byId[id]

            view.findViewById<ImageView>(R.id.deleteButton).setOnClickListener { removeComponent(id) }
            view.findViewById<TextView>(R.id.itemNameText).text =
                variant?.itemName ?: "Unknown item (deleted from Loyverse?)"
            view.findViewById<TextView>(R.id.currentStockText).text = when {
                variant == null -> "Tap the trash icon to remove it"
                variant.barcode.isNotEmpty() -> "${stockLabel(variant)} · ${variant.barcode}"
                else -> stockLabel(variant)
            }

            val qtyInput = view.findViewById<EditText>(R.id.addQtyInput)
            // Quantities can't be negative, so no minus sign on the keypad.
            qtyInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            qtyInput.hint = "qty"
            qtyInput.setText(components[id] ?: "")
            qtyInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (id in components) components[id] = s?.toString() ?: ""
                }
            })
            return view
        }
    }
}
