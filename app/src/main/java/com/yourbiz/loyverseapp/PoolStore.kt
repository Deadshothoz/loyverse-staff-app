package com.yourbiz.loyverseapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pools live in a Google Sheet (via a small Apps Script web app) so every
 * device sees the same list. A pool never touches Loyverse - it's just a
 * name, a minimum stock, and a list of variant IDs whose Loyverse stock
 * gets added together for display in Check Stock and Pools.
 */
object PoolStore {

    data class Pool(
        val id: String,
        val name: String,
        val minStock: Double,
        val variantIds: List<String>
    )

    private const val PREFS = "loyverse_prefs"
    private const val KEY_URL = "pools_url"

    @Volatile var pools: List<Pool> = emptyList()
        private set

    /** True once pools have been loaded successfully at least once. */
    @Volatile var loaded: Boolean = false
        private set

    fun getUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url.trim()).apply()
    }

    /** Which pool (if any) a variant belongs to. */
    fun poolForVariant(variantId: String): Pool? =
        pools.firstOrNull { variantId in it.variantIds }

    /** Combined Loyverse stock of every item in the pool. */
    fun totalStock(pool: Pool, variantsById: Map<String, LoyverseApi.Variant>): Double =
        pool.variantIds.sumOf { variantsById[it]?.currentStock ?: 0.0 }

    /** "out", "low" or "in" - same rules as a normal item with a minimum. */
    fun status(total: Double, minStock: Double): String = when {
        total <= 0.0 -> "out"
        total < minStock -> "low"
        else -> "in"
    }

    fun formatQty(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    /**
     * Popup asking for the Apps Script web app link. Each device asks once,
     * then remembers it. [onSaved] runs after a link is saved.
     */
    fun promptForUrl(activity: android.app.Activity, onSaved: () -> Unit) {
        val input = android.widget.EditText(activity)
        input.hint = "https://script.google.com/macros/s/.../exec"
        input.setText(getUrl(activity) ?: "")
        input.setSingleLine(true)
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(activity)
        container.setPadding(pad, pad / 2, pad, 0)
        container.addView(input)

        android.app.AlertDialog.Builder(activity)
            .setTitle("Pools link")
            .setMessage("Paste the Web app URL of your Pools Google Sheet script.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://")) {
                    saveUrl(activity, url)
                    loaded = false
                    onSaved()
                } else {
                    android.widget.Toast.makeText(
                        activity, "That doesn't look like a link", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Network calls. All run on the calling thread - use a background
    // --- executor, never the UI thread.

    fun load(url: String) {
        applyResponse(request(url, "GET", null))
    }

    fun save(url: String, pool: Pool) {
        val poolJson = JSONObject()
        poolJson.put("id", pool.id)
        poolJson.put("name", pool.name)
        poolJson.put("min_stock", pool.minStock)
        poolJson.put("variant_ids", JSONArray(pool.variantIds))
        val body = JSONObject()
        body.put("action", "save")
        body.put("pool", poolJson)
        applyResponse(request(url, "POST", body.toString()))
    }

    fun delete(url: String, poolId: String) {
        val body = JSONObject()
        body.put("action", "delete")
        body.put("id", poolId)
        applyResponse(request(url, "POST", body.toString()))
    }

    private fun applyResponse(json: JSONObject) {
        val arr = json.optJSONArray("pools") ?: JSONArray()
        val result = ArrayList<Pool>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val ids = p.optJSONArray("variant_ids") ?: JSONArray()
            val idList = (0 until ids.length()).map { ids.getString(it) }.filter { it.isNotBlank() }
            result.add(
                Pool(
                    id = p.getString("id"),
                    name = p.optString("name", "Unnamed pool"),
                    minStock = p.optDouble("min_stock", 0.0),
                    variantIds = idList
                )
            )
        }
        pools = result
        loaded = true
    }

    /**
     * Apps Script web apps answer with a redirect to a second Google URL
     * that holds the actual response, so redirects are followed by hand
     * (switching to GET, which is what Google expects).
     */
    private fun request(url: String, method: String, body: String?): JSONObject {
        var currentUrl = url
        var currentMethod = method
        var currentBody = body

        for (attempt in 0 until 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.requestMethod = currentMethod
            val bodyToSend = currentBody
            if (bodyToSend != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                conn.outputStream.use { it.write(bodyToSend.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                    ?: throw RuntimeException("Pools link redirected without a destination")
                conn.disconnect()
                currentUrl = next
                currentMethod = "GET"
                currentBody = null
                continue
            }

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RuntimeException("Pools sheet error ($code)")
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw RuntimeException(
                    "The pools link didn't return pool data. Check it's the Web app URL " +
                    "and that access is set to \"Anyone\"."
                )
            }
            if (!json.optBoolean("ok", false)) {
                throw RuntimeException(json.optString("error", "Pools sheet returned an error"))
            }
            return json
        }
        throw RuntimeException("Pools link redirected too many times")
    }
}
