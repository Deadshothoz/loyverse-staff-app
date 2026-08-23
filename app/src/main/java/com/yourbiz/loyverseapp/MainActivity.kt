package com.yourbiz.loyverseapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * First screen the app shows. Its only job is to make sure we have a
 * Loyverse API token saved, then hand off to HomeActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tokenInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("loyverse_prefs", Context.MODE_PRIVATE)
        tokenInput = findViewById(R.id.tokenInput)

        findViewById<Button>(R.id.saveTokenButton).setOnClickListener { saveToken() }

        val savedToken = prefs.getString("api_token", null)
        if (!savedToken.isNullOrBlank()) {
            goToHome()
        }
    }

    private fun saveToken() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Please paste your token first", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("api_token", token).apply()
        goToHome()
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
