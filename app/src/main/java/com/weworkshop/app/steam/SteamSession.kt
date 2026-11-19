package com.weworkshop.app.steam

import com.weworkshop.app.App
import com.weworkshop.app.R
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

/**
 * Single long-lived connection to the Steam network.
 *
 * Login uses Steam's modern auth flow (password + Steam Guard). Only the refresh token is
 * persisted (in app-private storage); the password is never stored.
 */
object SteamSession {
    private const val TAG = "SteamSession"

    /** Wallpaper Engine's Steam app id. */
    const val WE_APP_ID = 431960

    sealed interface State {
        data object Connecting : State
        data class NeedLogin(val error: String? = null) : State
        data object Authenticating : State
        data class LoggedIn(val accountName: String) : State
    }

    sealed interface AuthPrompt {
        /** Steam Guard code from the mobile authenticator ([email] == null) or from e‑mail. */
        data class Code(
            val email: String?,
            val previousIncorrect: Boolean,
            val reply: CompletableFuture<String>,
        ) : AuthPrompt

        /** Ask whether the user wants to approve the login in the Steam mobile app. */
        data class Confirm(val reply: CompletableFuture<Boolean>) : AuthPrompt
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<AuthPrompt?>(null)
    val prompt: StateFlow<AuthPrompt?> = _prompt.asStateFlow()

    lateinit var client: SteamClient
        private set
    lateinit var unified: SteamUnifiedMessages
        private set
    private lateinit var manager: CallbackManager
    private lateinit var steamUser: SteamUser
    private lateinit var prefs: SharedPreferences

    @Volatile
    var licenses: List<License> = emptyList()
        private set

    /** Increments on every successful logon; lets long jobs detect that their connection was replaced. */
    @Volatile
    var sessionId: Int = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false
    @Volatile private var connected = false
    @Volatile private var pendingCredentials: Pair<String, String>? = null
    @Volatile private var currentAccount: String? = null

    private const val KEY_ACCOUNT = "account"
    private const val KEY_TOKEN = "refresh_token"
    private const val KEY_GUARD = "guard_data"

    fun init(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences("steam", Context.MODE_PRIVATE)

        client = SteamClient()
        manager = CallbackManager(client)
        steamUser = client.getHandler(SteamUser::class.java)!!
        unified = client.getHandler(SteamUnifiedMessages::class.java)!!

        manager.subscribe(ConnectedCallback::class.java) { onConnected() }
        manager.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) }
        manager.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) }
        manager.subscribe(LoggedOffCallback::class.java) { onLoggedOff(it) }
        manager.subscribe(LicenseListCallback::class.java) { onLicenseList(it) }

        thread(name = "steam-callbacks", isDaemon = true) {
            while (true) {
                try {
                    manager.runWaitCallbacks(1000L)
                } catch (t: Throwable) {
                    Log.e(TAG, "callback loop error", t)
                }
            }
        }
        connect()
    }

    val savedAccount: String? get() = prefs.getString(KEY_ACCOUNT, null)
    val hasSavedToken: Boolean get() = prefs.getString(KEY_TOKEN, null) != null

    private fun connect() {
        _state.value = State.Connecting
        scope.launch {
            try {
                client.connect()
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long = 3000) {
        scope.launch {
            delay(delayMs)
            if (!connected) connect()
        }
    }

    /** Called from the login screen. */
    fun login(username: String, password: String) {
        _state.value = State.Authenticating
        if (connected) {
            scope.launch { credentialAuth(username, password) }
        } else {
            pendingCredentials = username to password
            connect()
        }
    }

    fun logout() {
        prefs.edit().remove(KEY_TOKEN).apply()
        licenses = emptyList()
        currentAccount = null
        _state.value = State.NeedLogin()
        scope.launch {
            try {
                steamUser.logOff()
            } catch (_: Throwable) {
            }
        }
    }

    fun answerPrompt() {
        _prompt.value = null
    }

    suspend fun awaitLoggedIn(timeoutMs: Long = 60_000): State.LoggedIn = withTimeout(timeoutMs) {
        state.first { it is State.LoggedIn } as State.LoggedIn
    }

    // region callbacks

    private fun onConnected() {
        connected = true
        Log.i(TAG, "connected")
        val creds = pendingCredentials
        val token = prefs.getString(KEY_TOKEN, null)
        val account = prefs.getString(KEY_ACCOUNT, null)
        when {
            creds != null -> {
                pendingCredentials = null
                scope.launch { credentialAuth(creds.first, creds.second) }
            }

            token != null && account != null -> {
                _state.value = State.Authenticating
                logOnWithToken(account, token)
            }

            else -> _state.value = State.NeedLogin()
        }
    }

    private fun onDisconnected(cb: DisconnectedCallback) {
        connected = false
        Log.i(TAG, "disconnected (userInitiated=${cb.isUserInitiated})")
        if (_state.value is State.LoggedIn || _state.value is State.Authenticating) {
            _state.value = State.Connecting
        }
        scheduleReconnect(if (cb.isUserInitiated) 500 else 3000)
    }

    private fun onLoggedOn(cb: LoggedOnCallback) {
        if (cb.result == EResult.OK) {
            Log.i(TAG, "logged on as $currentAccount")
            // Wait for the license list before reporting LoggedIn; DepotDownloader needs it.
            return
        }
        Log.w(TAG, "logon failed: ${cb.result} / ${cb.extendedResult}")
        val tokenRejected = cb.result in setOf(
            EResult.InvalidPassword,
            EResult.AccessDenied,
            EResult.Expired,
            EResult.InvalidSignature,
            EResult.Revoked,
        )
        if (tokenRejected) {
            prefs.edit().remove(KEY_TOKEN).apply()
            _state.value = State.NeedLogin(App.str(R.string.err_token_expired))
        } else if (cb.result == EResult.TryAnotherCM || cb.result == EResult.ServiceUnavailable) {
            _state.value = State.Connecting
        } else {
            _state.value = State.NeedLogin(App.str(R.string.err_login_failed, cb.result.toString()))
        }
    }

    private fun onLoggedOff(cb: LoggedOffCallback) {
        Log.i(TAG, "logged off: ${cb.result}")
        if (prefs.getString(KEY_TOKEN, null) == null) {
            _state.value = State.NeedLogin()
        }
    }

    private fun onLicenseList(cb: LicenseListCallback) {
        if (cb.result != EResult.OK) {
            Log.w(TAG, "license list failed: ${cb.result}")
            return
        }
        licenses = cb.licenseList
        Log.i(TAG, "got ${licenses.size} licenses")
        sessionId++
        _state.value = State.LoggedIn(currentAccount ?: savedAccount ?: "")
    }

    // endregion

    private fun logOnWithToken(account: String, refreshToken: String) {
        currentAccount = account
        val details = LogOnDetails().apply {
            username = account
            accessToken = refreshToken
            shouldRememberPassword = true
            // Distinct login id so we never kick a desktop Steam client on the same network.
            loginID = 0x57455750
        }
        steamUser.logOn(details)
    }

    private suspend fun credentialAuth(username: String, password: String) {
        try {
            val details = AuthSessionDetails().apply {
                this.username = username
                this.password = password
                persistentSession = true
                deviceFriendlyName = "WE Workshop (${Build.MODEL})"
                guardData = prefs.getString(KEY_GUARD, null)
                authenticator = UiAuthenticator
            }
            val session = client.authentication.beginAuthSessionViaCredentials(details).await()
            val result = session.pollingWaitForResult().await()

            prefs.edit()
                .putString(KEY_ACCOUNT, result.accountName)
                .putString(KEY_TOKEN, result.refreshToken)
                .apply {
                    result.newGuardData?.let { putString(KEY_GUARD, it) }
                }
                .apply()

            logOnWithToken(result.accountName, result.refreshToken)
        } catch (t: Throwable) {
            Log.w(TAG, "credential auth failed", t)
            val cause = generateSequence(t) { it.cause }.firstOrNull { it is AuthenticationException } ?: t
            val msg = when {
                cause is AuthenticationException && cause.result == EResult.InvalidPassword -> App.str(R.string.err_bad_password)
                cause is AuthenticationException && cause.result == EResult.RateLimitExceeded -> App.str(R.string.err_rate_limited)
                cause is AuthenticationException -> App.str(R.string.err_auth_failed, cause.result.toString())
                t is CancellationException || t.cause is CancellationException -> App.str(R.string.err_login_cancelled)
                else -> App.str(R.string.err_login_failed, t.message ?: t.javaClass.simpleName)
            }
            _state.value = State.NeedLogin(msg)
        } finally {
            _prompt.value = null
        }
    }

    /** Bridges JavaSteam's Steam Guard callbacks to Compose dialogs. */
    private object UiAuthenticator : IAuthenticator {
        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            val f = CompletableFuture<String>()
            _prompt.value = AuthPrompt.Code(null, previousCodeWasIncorrect, f)
            return f
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            val f = CompletableFuture<String>()
            _prompt.value = AuthPrompt.Code(email ?: "", previousCodeWasIncorrect, f)
            return f
        }

        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            val f = CompletableFuture<Boolean>()
            _prompt.value = AuthPrompt.Confirm(f)
            return f
        }
    }
}
