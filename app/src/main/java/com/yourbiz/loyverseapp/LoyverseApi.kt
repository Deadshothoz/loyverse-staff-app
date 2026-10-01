package com.yourbiz.loyverseapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Loyverse API client.
 * Docs: https://developer.loyverse.com/docs/
 */
class LoyverseApi(private val token: String) {

    private val baseUrl = "https://api.loyverse.com/v1.0"

    data class Variant(
        val variantId: String,
        val itemId: String,
        val itemName: String,
        val storeId: String,
        val currentStock: Double,
        val barcode: String,
        val trackStock: Boolean,
        val categoryId: String?,
        val lowStockThreshold: Double?,
        /** True if this item is a Loyverse composite (e.g. a box made of bars). */
        val isComposite: Boolean = false,
        /** What one unit of this composite item is made of. Empty if not composite. */
        val components: List<Component> = emptyList(),
        /** How many variants the parent item has. Items with variants can't be composite. */
        val variantCount: Int = 1
    )

    /** One ingredient of a composite item: [quantity] of variant [variantId]. */
    data class Component(
        val variantId: String,
        val quantity: Double
    )

    data class Category(
        val id: String,
        val name: String
    )

    /** Result of a fetch: full variant records for items that changed,
     *  plus raw stock levels (variantId -> storeId, stock) for every
     *  inventory level that changed. In a delta fetch these can differ -
     *  a sale changes a variant's stock without touching the item itself,
     *  so it shows up in stockLevels but not in variants. */
    data class FetchResult(
        val variants: List<Variant>,
        val stockLevels: Map<String, Pair<String, Double>>,
        /** Items deleted in Loyverse since the last sync (delta fetches only). */
        val deletedItemIds: Set<String> = emptySet()
    )

    /**
     * Full fetch: every item + variant, cross-referenced with every
     * inventory level. Kept for the screens' own first-load path.
     */
    fun fetchItemsWithStock(): List<Variant> = fetch(null).variants

    /**
     * Fetches items + their variants (following pagination), then
     * cross-references with inventory levels to get current stock per
     * variant/store.
     *
     * If [updatedAtMin] is provided (ISO-8601), only items and inventory
     * levels changed at or after that instant are returned - this is what
     * makes repeat refreshes fast. Pass null for a full fetch.
     *
     * Items are included regardless of their "Track stock" setting:
     *  - If tracked, storeId + currentStock come from the inventory endpoint.
     *  - If NOT tracked, there's no inventory_levels entry, so we fall back
     *    to the store_id listed in the variant's own "stores" array (used
     *    for per-store pricing/availability) and report currentStock as 0,
     *    since there's no real stock number to show yet.
     */
    fun fetch(updatedAtMin: String?): FetchResult {
        val filterParam = if (updatedAtMin != null) "&updated_at_min=$updatedAtMin" else ""
        val stockMap = HashMap<String, Double>()
        val storeMap = HashMap<String, String>()

        // Page through the inventory list (only changes, if filtered)
        var cursor: String? = null
        do {
            val url = if (cursor == null) "$baseUrl/inventory?limit=250$filterParam"
                      else "$baseUrl/inventory?limit=250&cursor=$cursor$filterParam"
            val inventoryJson = get(url)
            val invLevels = inventoryJson.optJSONArray("inventory_levels") ?: JSONArray()
            for (i in 0 until invLevels.length()) {
                val lvl = invLevels.getJSONObject(i)
                val vId = lvl.getString("variant_id")
                stockMap[vId] = lvl.optDouble("in_stock", 0.0)
                storeMap[vId] = lvl.optString("store_id", "")
            }
            cursor = inventoryJson.optString("cursor", "").ifEmpty { null }
        } while (cursor != null)

        // Page through the items list (only changes, if filtered). Delta
        // fetches also ask for deleted items, so the app can drop them.
        val itemsFilter = if (updatedAtMin != null) "$filterParam&show_deleted=true" else ""
        val results = ArrayList<Variant>()
        val deletedItemIds = HashSet<String>()
        cursor = null
        do {
            val url = if (cursor == null) "$baseUrl/items?limit=250$itemsFilter"
                      else "$baseUrl/items?limit=250&cursor=$cursor$itemsFilter"
            val itemsJson = get(url)
            val items = itemsJson.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val itemId = item.getString("id")
                if (!item.isNull("deleted_at") && item.optString("deleted_at").isNotEmpty()) {
                    deletedItemIds.add(itemId)
                    continue
                }
                val itemName = item.optString("item_name", "Unnamed item")
                val trackStock = item.optBoolean("track_stock", false)
                val categoryId = item.optString("category_id", "").ifEmpty { null }
                val isComposite = item.optBoolean("is_composite", false)
                val components = parseComponents(item.optJSONArray("components"))
                val variants = item.optJSONArray("variants") ?: JSONArray()
                for (v in 0 until variants.length()) {
                    val variant = variants.getJSONObject(v)
                    val variantId = variant.getString("variant_id")
                    val barcode = variant.optString("barcode", "")

                    // Low stock threshold lives per-store on the variant's
                    // own "stores" array, independent of the inventory
                    // endpoint - so we read it the same way regardless of
                    // whether the item is tracked or not. Single-store setup,
                    // so we just take the first (only) store entry.
                    val storesArray = variant.optJSONArray("stores")
                    val firstStoreEntry = if (storesArray != null && storesArray.length() > 0) {
                        storesArray.getJSONObject(0)
                    } else null
                    val lowStockThreshold = if (firstStoreEntry != null && !firstStoreEntry.isNull("low_stock")) {
                        firstStoreEntry.optDouble("low_stock")
                    } else null

                    val trackedStoreId = storeMap[variantId]
                    val finalStoreId: String
                    val finalStock: Double

                    if (!trackedStoreId.isNullOrEmpty()) {
                        finalStoreId = trackedStoreId
                        finalStock = stockMap[variantId] ?: 0.0
                    } else {
                        // Not tracked (or no inventory entry yet) - fall back
                        // to the store_id from the variant's own per-store
                        // pricing list, so we still know which store to
                        // write to once tracking gets turned on.
                        val fallbackStoreId = firstStoreEntry?.optString("store_id", "") ?: ""
                        if (fallbackStoreId.isEmpty()) continue // truly no store info at all
                        finalStoreId = fallbackStoreId
                        finalStock = 0.0
                    }

                    results.add(
                        Variant(
                            variantId, itemId, itemName, finalStoreId, finalStock,
                            barcode, trackStock, categoryId, lowStockThreshold,
                            isComposite, components, variants.length()
                        )
                    )
                }
            }
            cursor = itemsJson.optString("cursor", "").ifEmpty { null }
        } while (cursor != null)

        val stockLevels = stockMap.mapValues { (vId, stock) -> Pair(storeMap[vId] ?: "", stock) }
        return FetchResult(results, stockLevels, deletedItemIds)
    }

    private fun parseComponents(arr: JSONArray?): List<Component> {
        if (arr == null) return emptyList()
        val result = ArrayList<Component>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val variantId = c.optString("variant_id", "")
            if (variantId.isNotEmpty()) result.add(Component(variantId, c.optDouble("quantity", 1.0)))
        }
        return result
    }

    /**
     * Fetches all categories (id -> name), paginated the same way as
     * items/inventory. Used by Check Stock to build the category filter
     * and to show a readable category name per row instead of a raw ID.
     */
    fun fetchCategories(): List<Category> {
        val results = ArrayList<Category>()
        var cursor: String? = null
        do {
            val url = if (cursor == null) "$baseUrl/categories?limit=250"
                      else "$baseUrl/categories?limit=250&cursor=$cursor"
            val json = get(url)
            val categories = json.optJSONArray("categories") ?: JSONArray()
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                results.add(Category(cat.getString("id"), cat.optString("name", "Unnamed category")))
            }
            cursor = json.optString("cursor", "").ifEmpty { null }
        } while (cursor != null)
        return results
    }

    /**
     * Batch-updates stock levels. Loyverse requires the ABSOLUTE final stock
     * (stock_after), not a delta - so callers should pass the already-computed
     * final value here.
     */
    fun updateStockBatch(updates: List<Triple<String, String, Double>>) {
        // Triple = (variantId, storeId, newStockAfter)
        val levels = JSONArray()
        for ((variantId, storeId, stockAfter) in updates) {
            val obj = JSONObject()
            obj.put("variant_id", variantId)
            obj.put("store_id", storeId)
            obj.put("stock_after", stockAfter)
            levels.put(obj)
        }
        val body = JSONObject()
        body.put("inventory_levels", levels)
        post("$baseUrl/inventory", body)
    }

    /**
     * Turns "Track stock" ON for an item. Loyverse's /v1.0/items endpoint
     * only accepts GET and POST - updates go through POST with the item's
     * id included in the body (there is no PUT /items/{id}).
     *
     * Note: this is an item-level setting, so it applies to ALL of that
     * item's variants at once - only needs to be called once per item,
     * even if multiple variants of the same item are being updated.
     */
    fun updateItemTrackStock(itemId: String, trackStock: Boolean) {
        updateItem(itemId) { item ->
            if (item.optBoolean("track_stock", false) == trackStock) {
                false // already set - nothing to send
            } else {
                item.put("track_stock", trackStock)
                true
            }
        }
    }

    /**
     * Makes an item composite with the given components, or - if
     * [components] is empty - turns it back into a normal item. Loyverse
     * doesn't allow a composite item to track its own stock (its stock
     * comes from its components), so tracking is switched off for it.
     */
    fun updateItemComposite(itemId: String, components: List<Component>) {
        updateItem(itemId) { item ->
            val arr = JSONArray()
            for (c in components) {
                val obj = JSONObject()
                obj.put("variant_id", c.variantId)
                obj.put("quantity", c.quantity)
                arr.put(obj)
            }
            item.put("is_composite", components.isNotEmpty())
            item.put("components", arr)
            if (components.isNotEmpty()) {
                item.put("track_stock", false)
                item.put("use_production", false)
            }
            true
        }
    }

    /**
     * Loyverse's POST /items saves the WHOLE item, not just the fields
     * sent - sending only a couple of fields is rejected ("item_name must
     * be set"), and leaving out variants/prices/barcodes could wipe them.
     * So: fetch the item exactly as it is now, let [change] edit it, and
     * send everything back unchanged apart from that. [change] returns
     * false if there's nothing to send.
     */
    private fun updateItem(itemId: String, change: (JSONObject) -> Boolean) {
        val raw = get("$baseUrl/items/$itemId")
        val item = raw.optJSONObject("item") ?: raw
        if (!change(item)) return

        removeReadOnlyFields(item)
        val variants = item.optJSONArray("variants")
        if (variants != null) {
            for (i in 0 until variants.length()) {
                variants.optJSONObject(i)?.let { removeReadOnlyFields(it) }
            }
        }
        post("$baseUrl/items", item)
    }

    /** Timestamps are set by Loyverse itself and aren't part of an update. */
    private fun removeReadOnlyFields(obj: JSONObject) {
        obj.remove("created_at")
        obj.remove("updated_at")
        obj.remove("deleted_at")
    }

    private fun get(urlStr: String): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            throw RuntimeException("Loyverse API error ($code): $text")
        }
        return JSONObject(text)
    }

    private fun post(urlStr: String, body: JSONObject) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw RuntimeException("Loyverse API error ($code): $err")
        }
    }
}
