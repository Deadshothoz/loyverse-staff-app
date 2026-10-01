package com.yourbiz.loyverseapp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Very simple in-memory cache so we don't re-fetch the whole catalog
 * every time the user navigates between screens in the same session.
 */
object ItemCache {
    var variants: List<LoyverseApi.Variant> = emptyList()
    var categories: List<LoyverseApi.Category> = emptyList()
    var lastLoadedAt: Long = 0L

    /** How far back before the last sync we ask for changes. Covers the
     *  phone's clock being slightly off from Loyverse's servers, so a
     *  change made just before/around the last sync is never missed.
     *  Re-fetching a few extra changed items is harmless. */
    private const val SAFETY_MARGIN_MS = 5 * 60 * 1000L

    /**
     * Refreshes the cache. If we've synced before this session, asks
     * Loyverse only for what changed since then (fast) and merges it in.
     * Otherwise - or if the delta request fails for any reason - falls
     * back to a full fetch. Runs on the calling thread, so call it from a
     * background executor, never the UI thread. Synchronized so two
     * refreshes running at once (e.g. pull-to-refresh on Home while a
     * screen refreshes) queue up instead of overwriting each other.
     */
    @Synchronized
    fun refresh(api: LoyverseApi) {
        val startedAt = System.currentTimeMillis()
        val since = lastLoadedAtIso()

        var didDelta = false
        if (since != null && variants.isNotEmpty()) {
            try {
                mergeDelta(api.fetch(since))
                didDelta = true
            } catch (e: Exception) {
                // Fall through to a full fetch below.
            }
        }
        if (!didDelta) {
            variants = api.fetch(null).variants
        }

        categories = api.fetchCategories()
        lastLoadedAt = startedAt
    }

    private fun lastLoadedAtIso(): String? {
        if (lastLoadedAt == 0L) return null
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(lastLoadedAt - SAFETY_MARGIN_MS))
    }

    /**
     * Applies a delta fetch to the cached list:
     *  1. Any item that changed has ALL its variants replaced with the new
     *     ones - so a variant deleted from an item disappears too.
     *  2. Items deleted in Loyverse are dropped entirely.
     *  3. Any variant whose stock changed - but whose item didn't (e.g. a
     *     sale at the POS) - gets just its stock number updated.
     * Everything else is left untouched.
     */
    private fun mergeDelta(delta: LoyverseApi.FetchResult) {
        val old = variants.associateBy { it.variantId }
        val touchedItemIds = delta.variants.map { it.itemId }.toHashSet()
        touchedItemIds.addAll(delta.deletedItemIds)

        val byId = LinkedHashMap<String, LoyverseApi.Variant>()
        variants.filter { it.itemId !in touchedItemIds }.forEach { byId[it.variantId] = it }

        delta.variants.forEach { changed ->
            val existing = old[changed.variantId]
            byId[changed.variantId] =
                if (existing != null && changed.variantId !in delta.stockLevels) {
                    // Item details changed (name, barcode, threshold...) but
                    // its stock didn't, so the delta has no stock number for
                    // it - keep the stock we already know about.
                    changed.copy(currentStock = existing.currentStock, storeId = existing.storeId)
                } else {
                    changed
                }
        }

        for ((variantId, level) in delta.stockLevels) {
            val existing = byId[variantId] ?: continue
            val (storeId, stock) = level
            byId[variantId] = existing.copy(
                currentStock = stock,
                storeId = storeId.ifEmpty { existing.storeId }
            )
        }
        variants = byId.values.toList()
    }
}
