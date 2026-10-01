package com.yourbiz.loyverseapp

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
        // Also installed here: after a crash Android may reopen the app
        // straight on Home, skipping the token screen.
        CrashReporter.install(this)
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
            startActivity(Intent(this, PoolsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.tileComposites).setOnClickListener {
            startActivity(Intent(this, CompositesActivity::class.java))
        }

        showLastCrashIfAny()
    }

    /** If the app crashed last time, show the error so it can be copied and sent. */
    private fun showLastCrashIfAny() {
        val crash = CrashReporter.takeLastCrash(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("The app crashed last time")
            .setMessage("Tap COPY and send this to whoever is fixing the app:\n\n$crash")
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash report", crash))
                Toast.makeText(this, "Copied - paste it into the chat", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Pull-to-refresh handler. ItemCache.refresh() asks Loyverse only for
     * what changed since the last sync when it can, and falls back to a
     * full fetch otherwise. Pools are reloaded from the Google Sheet too.
     * The spinner stays visible until it's done.
     */
    private fun refreshCatalog() {
        executor.execute {
            try {
                val prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("api_token", "") ?: ""
                ItemCache.refresh(LoyverseApi(token))
                // Pools too, if this device has been set up with the link. A
                // pools problem shouldn't make the catalog refresh look failed.
                var poolsFailed = false
                PoolStore.getUrl(this)?.let { url ->
                    try {
                        PoolStore.load(url)
                    } catch (e: Exception) {
                        poolsFailed = true
                    }
                }
                runOnUiThread {
                    swipeRefresh.isRefreshing = false
                    val msg = if (poolsFailed) "Catalog up to date (pools couldn't load)" else "Catalog up to date"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
