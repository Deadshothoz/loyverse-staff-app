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

        findViewById<LinearLayout>(R.id.tileSetComposite).setOnClickListener {
            Toast.makeText(this, "Set Composite - coming soon", Toast.LENGTH_SHORT).show()
        }

        findViewById<LinearLayout>(R.id.tileAddVariant).setOnClickListener {
            Toast.makeText(this, "Add Variant - coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
