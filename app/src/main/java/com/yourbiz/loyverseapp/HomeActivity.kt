package com.yourbiz.loyverseapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<LinearLayout>(R.id.tileAddStock).setOnClickListener {
            startActivity(Intent(this, AddStockActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.tileReduceStock).setOnClickListener {
            startActivity(Intent(this, ReduceStockActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.tileCheckStock).setOnClickListener {
            startActivity(Intent(this, CheckStockActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.tileStockCount).setOnClickListener {
            Toast.makeText(this, "Stock Count - coming soon", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.tileManagePools).setOnClickListener {
            Toast.makeText(this, "Manage Pools - coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Home becomes visible every time you back out of any screen - so
        // this is the one central place to quietly refresh the cache,
        // meaning by the time you tap back into a screen, the numbers are
        // already current rather than waiting for that screen's own load.
        refreshCatalogInBackground()
    }

    private fun refreshCatalogInBackground() {
        executor.execute {
            try {
                val prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("api_token", "") ?: ""
                val api = LoyverseApi(token)
                val variants = api.fetchItemsWithStock()
                val categories = api.fetchCategories()
                ItemCache.variants = variants
                ItemCache.categories = categories
                ItemCache.lastLoadedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                // Silent failure - keep using whatever is cached. Any
                // screen the user opens next will surface its own error
                // if the catalog is genuinely unreachable.
            }
        }
    }
}
