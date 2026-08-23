package com.yourbiz.loyverseapp

/**
 * Very simple in-memory cache so we don't re-fetch the whole catalog
 * every time the user navigates between screens in the same session.
 */
object ItemCache {
    var variants: List<LoyverseApi.Variant> = emptyList()
    var lastLoadedAt: Long = 0L
}
