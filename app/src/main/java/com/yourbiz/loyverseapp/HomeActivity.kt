package com.yourbiz.loyverseapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeResources(R.color.brand_primary)
        swipeRefresh.setOnRefreshListener { refreshCatalog() }

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
            startActivity(Intent(this, StockCountActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.tileManagePools).setOnClickListener {
            Toast.makeText(this, "Manage Pools - coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Pull-to-refresh handler. ItemCache.refresh() asks Loyverse only for
     * what changed since the last sync when it can, and falls back to a
     * full fetch otherwise. The spinner stays visible until it's done.
     */
    private fun refreshCatalog() {
        executor.execute {
            try {
                val prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("api_token", "") ?: ""
                ItemCache.refresh(LoyverseApi(token))
                runOnUiThread {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this, "Catalog up to date", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this, "Refresh failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
