package com.yourbiz.loyverseapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * First screen the app shows. Its only job is to collect the two things
 * the app needs - the Loyverse API token and (optionally) the pools link -
 * then hand off to HomeActivity. Both are saved on the device, so this
 * screen only appears on a fresh install.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tokenInput: EditText
    private lateinit var poolsUrlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this) // catch any crash from here on, on any screen
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
        tokenInput = findViewById(R.id.tokenInput)
        poolsUrlInput = findViewById(R.id.poolsUrlInput)
        poolsUrlInput.setText(PoolStore.getUrl(this) ?: "")

        findViewById<Button>(R.id.saveTokenButton).setOnClickListener { save() }

        val savedToken = prefs.getString("api_token", null)
        if (!savedToken.isNullOrBlank()) {
            goToHome()
        }
    }

    private fun save() {
        val token = tokenInput.text.toString().trim()
        val poolsUrl = poolsUrlInput.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Please paste your token first", Toast.LENGTH_SHORT).show()
            return
        }
        // The pools link can be left empty and added later from Manage
        // Pools -> LINK. If something is typed, it has to look like a link.
        if (poolsUrl.isNotEmpty() && !poolsUrl.startsWith("https://")) {
            Toast.makeText(this, "The pools link should start with https://", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("api_token", token).apply()
        if (poolsUrl.isNotEmpty()) PoolStore.saveUrl(this, poolsUrl)
        goToHome()
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
