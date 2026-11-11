package com.sonora.music.data.account

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class Account(val cookie: String, val name: String?, val photo: String?)

/** Your YouTube Music sign-in. The session cookie is kept only in this app's private storage. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Account?> = _state.asStateFlow()

    val cookie: String? get() = _state.value?.cookie

    private fun load(): Account? = prefs.getString("cookie", null)?.let {
        Account(it, prefs.getString("name", null), prefs.getString("photo", null))
    }

    fun signIn(cookie: String) {
        prefs.edit().putString("cookie", cookie).remove("name").remove("photo").apply()
        _state.value = Account(cookie, null, null)
    }

    fun setProfile(name: String?, photo: String?) {
        val current = _state.value ?: return
        prefs.edit().putString("name", name).putString("photo", photo).apply()
        _state.value = current.copy(name = name, photo = photo)
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _state.value = null
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }
}
