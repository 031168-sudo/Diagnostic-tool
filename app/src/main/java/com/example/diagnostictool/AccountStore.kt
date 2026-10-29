package com.example.diagnostictool

import android.content.Context

class AccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("diagnostic_account", Context.MODE_PRIVATE)

    val accountId: String?
        get() = prefs.getString(KEY_ID, null)

    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    val isRegistered: Boolean
        get() = !token.isNullOrBlank()

    fun save(accountId: String, token: String) {
        prefs.edit().putString(KEY_ID, accountId).putString(KEY_TOKEN, token).apply()
    }

    companion object {
        private const val KEY_ID = "account_id"
        private const val KEY_TOKEN = "account_token"
    }
}
