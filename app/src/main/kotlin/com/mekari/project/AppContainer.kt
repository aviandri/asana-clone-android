package com.mekari.project

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.mekari.project.core.DeepLink
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
import kotlinx.coroutines.channels.Channel
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

    override fun onCreate() {
        super.onCreate()
        InboxNotifications.createChannel(this)
    }
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

    /**
     * One blocking read at cold start, on purpose: :core's TokenStore and ServerSettings are synchronous
     * (OkHttp interceptors can't suspend), and AppSession.start() needs both before the first frame can
     * decide between sign-in and My Tasks. It's one small preferences file plus one Keystore unwrap.
     */
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
        if (liveSession) updateBackgroundCheck(signedIn = currentSession?.state?.value?.me != null)
    }

    /** Whether to ask for the notification permission (once per install, after the first sign-in). */
    fun shouldAskNotificationPermission(): Boolean = !askedNotifications

    fun markNotificationPermissionAsked() {
        askedNotifications = true
        write { it[Keys.ASKED_NOTIFICATIONS] = true }
    }

    private var askedNotifications: Boolean = false
        get() = field || (stored[Keys.ASKED_NOTIFICATIONS] ?: false)

    fun setCompletionSound(on: Boolean) {
        _prefs.update { it.copy(completionSound = on) }
        write { it[Keys.SOUND] = on }
    }

    /** A mekariproject:// link (or a notification tap) waiting for the signed-in shell to open it. */
    val pendingLink = MutableStateFlow<DeepLink?>(null)

    /** The launcher's quick-add shortcut, waiting for the shell. */
    val pendingQuickAdd = MutableStateFlow(false)

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
        liveSession = !fakeApi
        appScope.launch { session.start() }
        followForeground(session)
        // Background inbox checks: live sessions only (the fake API never schedules WorkManager).
        if (!fakeApi) {
            appScope.launch {
                session.state.collect { state -> updateBackgroundCheck(signedIn = state.me != null) }
            }
        }
        return session
    }

    private var liveSession = false

    private fun updateBackgroundCheck(signedIn: Boolean) {
        if (signedIn && _prefs.value.notificationsEnabled) {
            InboxNotifications.schedule(context)
        } else {
            InboxNotifications.cancel(context)
        }
    }

    /**
     * The background check (InboxCheckWorker): the stored session against the server, its current workspace's
     * unread items, diffed against the ids seen last time. Null when there's no session or the call fails.
     */
    suspend fun checkInbox(): List<com.mekari.project.feature.LocalNotice>? {
        val server = serverSettings.serverUrl ?: return null
        if (tokenStore.load() == null) return null
        return try {
            val api = LiveApi(server, tokenStore)
            val workspace = api.me().currentWorkspace ?: return null
            api.setWorkspace(workspace.id)
            val page = api.notifications(unreadOnly = true)
            val previous = context.dataStore.data.first()[Keys.SEEN]
            val (notices, seen) = diffInbox(previous, page)
            write { it[Keys.SEEN] = seen }
            notices
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The inbox poller runs only while the app is in the foreground (ProcessLifecycleOwner: rotation isn't a
     * stop), and coming back refreshes My Tasks, projects and the inbox. A new workspace scope starts its
     * own poller if we're in the foreground; its first load is the UI's.
     */
    private fun followForeground(session: AppSession) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                val scope = session.scope.value ?: return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        scope.poller.start()
                        appScope.launch { scope.refresh() }
                    }
                    Lifecycle.Event.ON_STOP -> scope.poller.stop()
                    else -> Unit
                }
            }
        )
        appScope.launch {
            session.scope.collect { scope ->
                // The first load is the UI's (MainShell); here only the foreground poller.
                if (scope != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) scope.poller.start()
            }
        }
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
        val initial = stored[Keys.TOKEN]?.let { sealed ->
            SessionCipher.decrypt(sealed) ?: run {
                // Unreadable (key lost after a restore, or corrupt): start signed out, and drop both.
                SessionCipher.deleteKey()
                write { it.remove(Keys.TOKEN) }
                null
            }
        }
        PersistentTokenStore(initial) { token ->
            // A Keystore failure keeps the token for this run only (in memory), rather than failing sign-in.
            val sealed = token?.let { SessionCipher.encrypt(it) }
            write { if (sealed != null) it[Keys.TOKEN] = sealed else it.remove(Keys.TOKEN) }
        }
    }

    /** Every DataStore change, in the order asked for, applied by one coroutine (see [write]). */
    private val writes = Channel<(MutablePreferences) -> Unit>(Channel.UNLIMITED).also { queue ->
        appScope.launch { for (change in queue) context.dataStore.edit { change(it) } }
    }

    /**
     * Enqueues a change; changes reach DataStore strictly in call order, from any thread (the token's
     * clearIf runs on OkHttp's thread, a sign-in's save on main). DataStore does its own I/O off main.
     */
    private fun write(change: (MutablePreferences) -> Unit) {
        writes.trySend(change)
    }

    private object Keys {
        val SERVER = stringPreferencesKey("serverUrl")
        val TOKEN = stringPreferencesKey("session")
        val CELEBRATIONS = booleanPreferencesKey("celebrationsEnabled")
        val NOTIFICATIONS = booleanPreferencesKey("notificationsEnabled")
        val SOUND = booleanPreferencesKey("completionSound")
        val SEEN = stringSetPreferencesKey("seenUnreadNotifications")
        val ASKED_NOTIFICATIONS = booleanPreferencesKey("askedNotificationPermission")
    }
}

/**
 * The session token, sealed with an AES-GCM key that never leaves the Android Keystore
 * (SPEC-ANDROID §3). A token that can't be decrypted (key lost, e.g. after a restore) reads as signed out.
 */
internal object SessionCipher {
    private const val ALIAS = "mekari.session"
    private const val TAG = "SessionCipher"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Null if the Keystore fails (logged by type only; never the token). */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(sealed, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't seal the session token: ${e.javaClass.simpleName}")
        null
    }

    /** Null if it can't be opened (wrong key, corrupt blob, Keystore failure). */
    fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't open the session token: ${e.javaClass.simpleName}")
        null
    }

    /** Drops the key (after an unreadable token), so the next sign-in starts with a fresh one. */
    fun deleteKey() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't delete the session key: ${e.javaClass.simpleName}")
        }
    }

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
