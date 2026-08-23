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
        val trackStock: Boolean
    )

    /**
     * Fetches ALL items + their variants (following pagination), then
     * cross-references with ALL inventory levels to get current stock
     * per variant/store.
     *
     * Items are included regardless of their "Track stock" setting:
     *  - If tracked, storeId + currentStock come from the inventory endpoint.
     *  - If NOT tracked, there's no inventory_levels entry, so we fall back
     *    to the store_id listed in the variant's own "stores" array (used
     *    for per-store pricing/availability) and report currentStock as 0,
     *    since there's no real stock number to show yet.
     */
    fun fetchItemsWithStock(): List<Variant> {
        val stockMap = HashMap<String, Double>()
        val storeMap = HashMap<String, String>()

        // Page through the entire inventory list
        var cursor: String? = null
        do {
            val url = if (cursor == null) "$baseUrl/inventory?limit=250"
                      else "$baseUrl/inventory?limit=250&cursor=$cursor"
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

        // Page through the entire items list
        val results = ArrayList<Variant>()
        cursor = null
        do {
            val url = if (cursor == null) "$baseUrl/items?limit=250"
                      else "$baseUrl/items?limit=250&cursor=$cursor"
            val itemsJson = get(url)
            val items = itemsJson.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val itemId = item.getString("id")
                val itemName = item.optString("item_name", "Unnamed item")
                val trackStock = item.optBoolean("track_stock", false)
                val variants = item.optJSONArray("variants") ?: JSONArray()
                for (v in 0 until variants.length()) {
                    val variant = variants.getJSONObject(v)
                    val variantId = variant.getString("variant_id")
                    val barcode = variant.optString("barcode", "")

                    var storeId = storeMap[variantId]
                    var stock = stockMap[variantId] ?: 0.0

                    if (storeId.isNullOrEmpty()) {
                        // Not tracked (or no inventory entry yet) - fall back
                        // to the store_id from the variant's own per-store
                        // pricing list, so we still know which store to
                        // write to once tracking gets turned on.
                        val stores = variant.optJSONArray("stores")
                        val fallbackStoreId = if (stores != null && stores.length() > 0) {
                            stores.getJSONObject(0).optString("store_id", "")
                        } else ""
                        if (fallbackStoreId.isEmpty()) continue // truly no store info at all
                        storeId = fallbackStoreId
                        stock = 0.0
                    }

                    results.add(Variant(variantId, itemId, itemName, storeId, stock, barcode, trackStock))
                }
            }
            cursor = itemsJson.optString("cursor", "").ifEmpty { null }
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
        val body = JSONObject()
        body.put("id", itemId)
        body.put("track_stock", trackStock)
        post("$baseUrl/items", body)
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
