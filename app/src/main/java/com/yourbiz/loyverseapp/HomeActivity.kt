package com.yourbiz.loyverseapp

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

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
            Toast.makeText(this, "Check Stock - coming soon", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.tileStockCount).setOnClickListener {
            Toast.makeText(this, "Stock Count - coming soon", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.tileManagePools).setOnClickListener {
            Toast.makeText(this, "Manage Pools - coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
