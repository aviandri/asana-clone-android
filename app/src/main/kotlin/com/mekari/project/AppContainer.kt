package com.mekari.project

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mekari.project.core.LiveApi
import com.mekari.project.core.ServerSettings
import com.mekari.project.core.TokenStore
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.feature.AppSession
import com.mekari.project.feature.FeaturePrefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mekari_project")

class MekariApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as MekariApplication).container

/** App-only settings (SPEC-ANDROID §5): notifications and the completion sound. */
data class AppPrefs(val notificationsEnabled: Boolean = true, val completionSound: Boolean = false)

/**
 * The manual DI root (no framework, SPEC-ANDROID §2). Storage is read ONCE at launch, then served from
 * memory, and every write goes through to DataStore asynchronously (core's stores are synchronous).
 */
class AppContainer(private val context: Context) {
    /** Where every holder action runs: Main.immediate (holders are main-confined) and never cancelled by a
     *  screen going away, so a PATCH is never abandoned halfway. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val stored: Preferences by lazy { runBlocking { context.dataStore.data.first() } }

    private val _prefs by lazy {
        MutableStateFlow(
            AppPrefs(
                notificationsEnabled = stored[Keys.NOTIFICATIONS] ?: true,
                completionSound = stored[Keys.SOUND] ?: false
            )
        )
    }
    val prefs: StateFlow<AppPrefs> get() = _prefs.asStateFlow()

    val featurePrefs: FeaturePrefs by lazy {
        object : FeaturePrefs {
            override var celebrationsEnabled: Boolean = stored[Keys.CELEBRATIONS] ?: true
                set(value) {
                    field = value
                    write { it[Keys.CELEBRATIONS] = value }
                }
        }
    }

    fun setNotifications(on: Boolean) {
        _prefs.update { it.copy(notificationsEnabled = on) }
        write { it[Keys.NOTIFICATIONS] = on }
    }

    fun setCompletionSound(on: Boolean) {
        _prefs.update { it.copy(completionSound = on) }
        write { it[Keys.SOUND] = on }
    }

    private var currentSession: AppSession? = null

    /** The one session. With the fake-API launch flag it runs on FakeApi + demo data (tests, device QA). */
    fun session(fakeApi: Boolean, signedIn: Boolean = true, fake: FakeApi? = null): AppSession {
        currentSession?.let { return it }
        val session = if (fakeApi) {
            val api = fake ?: FakeApi(signedInAs = if (signedIn) "alice@example.com" else null)
            AppSession.uiTest(signedIn, appScope, api)
        } else {
            AppSession(serverSettings, tokenStore, { url, tokens -> LiveApi(url, tokens) }, featurePrefs, appScope)
        }
        currentSession = session
        appScope.launch { session.start() }
        return session
    }

    private val serverSettings: ServerSettings by lazy {
        object : ServerSettings {
            override var serverUrl: String? = stored[Keys.SERVER]
                set(value) {
                    field = value
                    write { prefs -> if (value == null) prefs.remove(Keys.SERVER) else prefs[Keys.SERVER] = value }
                }
        }
    }

    private val tokenStore: TokenStore by lazy {
        object : TokenStore {
            private var token: String? = stored[Keys.TOKEN]?.let { SessionCipher.decrypt(it) }

            override fun load(): String? = token

            override fun save(token: String) {
                this.token = token
                val sealed = SessionCipher.encrypt(token)
                write { it[Keys.TOKEN] = sealed }
            }

            override fun clear() {
                token = null
                write { it.remove(Keys.TOKEN) }
            }
        }
    }

    private fun write(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        appScope.launch(Dispatchers.IO) { context.dataStore.edit { change(it) } }
    }

    private object Keys {
        val SERVER = stringPreferencesKey("serverUrl")
        val TOKEN = stringPreferencesKey("session")
        val CELEBRATIONS = booleanPreferencesKey("celebrationsEnabled")
        val NOTIFICATIONS = booleanPreferencesKey("notificationsEnabled")
        val SOUND = booleanPreferencesKey("completionSound")
    }
}

/**
 * The session token, sealed with an AES-GCM key that never leaves the Android Keystore
 * (SPEC-ANDROID §3). A token that can't be decrypted (key lost, e.g. after a restore) reads as signed out.
 */
internal object SessionCipher {
    private const val ALIAS = "mekari.session"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
