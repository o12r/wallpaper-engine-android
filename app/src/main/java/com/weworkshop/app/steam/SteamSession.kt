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
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /**
     * Steam Guard step of a password sign-in. The login completes either when the user approves
     * it in the Steam mobile app ([canApprove]) or after a correct code is submitted; the app
     * keeps polling the whole time, so both paths work at once.
     */
    data class AuthPrompt(
        /** Which code Steam accepts, or null when only app/e-mail approval is possible. */
        val codeType: CodeType?,
        /** E-mail domain hint for [CodeType.EMAIL]. */
        val email: String?,
        val canApprove: Boolean,
        val previousIncorrect: Boolean = false,
        val submitting: Boolean = false,
    )

    enum class CodeType { DEVICE, EMAIL }

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
    private val connectedFlow = MutableStateFlow(false)
    private val connected: Boolean get() = connectedFlow.value
    @Volatile private var currentAccount: String? = null

    /** The running password sign-in; it survives disconnects and the app going to the background. */
    @Volatile private var authJob: Job? = null
    private val authInProgress: Boolean get() = authJob?.isActive == true
    private val codes = Channel<String>(Channel.CONFLATED)

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
        if (!authInProgress) _state.value = State.Connecting
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
        if (authInProgress) return
        _state.value = State.Authenticating
        while (codes.tryReceive().isSuccess) { /* drop stale codes */ }
        authJob = scope.launch(start = CoroutineStart.LAZY) { credentialAuth(username, password) }.also { it.start() }
    }

    /** Submits a Steam Guard code typed into the prompt. */
    fun submitCode(code: String) {
        _prompt.value = _prompt.value?.copy(submitting = true, previousIncorrect = false)
        codes.trySend(code)
    }

    fun cancelLogin() {
        authJob?.cancel()
        authJob = null
        _prompt.value = null
        _state.value = if (connected) State.NeedLogin() else State.Connecting
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

    suspend fun awaitLoggedIn(timeoutMs: Long = 60_000): State.LoggedIn = withTimeout(timeoutMs) {
        state.first { it is State.LoggedIn } as State.LoggedIn
    }

    // region callbacks

    private fun onConnected() {
        connectedFlow.value = true
        Log.i(TAG, "connected")
        val token = prefs.getString(KEY_TOKEN, null)
        val account = prefs.getString(KEY_ACCOUNT, null)
        when {
            // The sign-in coroutine is waiting for this connection and carries on by itself.
            authInProgress -> _state.value = State.Authenticating

            token != null && account != null -> {
                _state.value = State.Authenticating
                logOnWithToken(account, token)
            }

            else -> _state.value = State.NeedLogin()
        }
    }

    private fun onDisconnected(cb: DisconnectedCallback) {
        connectedFlow.value = false
        Log.i(TAG, "disconnected (userInitiated=${cb.isUserInitiated})")
        if (!authInProgress && (_state.value is State.LoggedIn || _state.value is State.Authenticating)) {
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

    private suspend fun awaitConnected() {
        connectedFlow.first { it }
    }

    /**
     * Runs [block] against the CM, waiting out disconnects (e.g. while Android has frozen the app
     * in the background) instead of failing. Steam's own errors are rethrown.
     */
    private suspend fun <T> resilient(block: suspend () -> T): T {
        while (true) {
            awaitConnected()
            try {
                return withTimeout(20_000) { block() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "auth request timed out, retrying")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.authCause() != null) throw e
                Log.w(TAG, "auth request failed, retrying", e)
                delay(1000)
            }
        }
    }

    private fun Throwable.authCause(): AuthenticationException? =
        generateSequence(this) { it.cause }.firstOrNull { it is AuthenticationException } as AuthenticationException?

    private suspend fun credentialAuth(username: String, password: String) {
        try {
            val details = AuthSessionDetails().apply {
                this.username = username
                this.password = password
                persistentSession = true
                deviceFriendlyName = "WE Workshop (${Build.MODEL})"
                guardData = prefs.getString(KEY_GUARD, null)
            }
            val session = resilient { client.authentication.beginAuthSessionViaCredentials(details).await() }

            val allowed = session.allowedConfirmations
            val types = allowed.map { it.confirmationType }
            val codeType = when {
                EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode in types -> CodeType.DEVICE
                EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode in types -> CodeType.EMAIL
                else -> null
            }
            val canApprove = EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation in types ||
                EAuthSessionGuardType.k_EAuthSessionGuardType_EmailConfirmation in types
            if (codeType != null || canApprove) {
                val email = allowed.firstOrNull {
                    it.confirmationType == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                }?.associatedMessage
                _prompt.value = AuthPrompt(codeType, email, canApprove)
            }

            val result = coroutineScope {
                val codeJob = codeType?.let { type ->
                    val guardType = if (type == CodeType.DEVICE) EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                    else EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                    launch {
                        for (code in codes) {
                            try {
                                resilient { session.sendSteamGuardCode(code, guardType).await() }
                                // Accepted; the poll below picks up the tokens.
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val r = e.authCause()?.result ?: throw e
                                if (r != EResult.InvalidLoginAuthCode && r != EResult.TwoFactorCodeMismatch) throw e
                                _prompt.value = _prompt.value?.copy(previousIncorrect = true, submitting = false)
                            }
                        }
                    }
                }
                val interval = (session.pollingInterval * 1000).toLong().coerceIn(1000, 5000)
                var r: AuthPollResult? = null
                while (r == null) {
                    delay(interval)
                    r = resilient { session.pollAuthSessionStatus().await() }
                }
                codeJob?.cancel()
                r
            }

            prefs.edit()
                .putString(KEY_ACCOUNT, result.accountName)
                .putString(KEY_TOKEN, result.refreshToken)
                .apply {
                    result.newGuardData?.let { putString(KEY_GUARD, it) }
                }
                .apply()
            _prompt.value = null
            authJob = null
            // If the connection is down right now, onConnected() logs on with the saved token.
            if (connected) logOnWithToken(result.accountName, result.refreshToken)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancelLogin() already updated the state
        } catch (t: Throwable) {
            Log.w(TAG, "credential auth failed", t)
            val cause = t.authCause()
            val msg = when {
                cause?.result == EResult.InvalidPassword -> App.str(R.string.err_bad_password)
                cause?.result == EResult.RateLimitExceeded -> App.str(R.string.err_rate_limited)
                cause?.result == EResult.Expired || cause?.result == EResult.FileNotFound -> App.str(R.string.err_auth_expired)
                cause != null -> App.str(R.string.err_auth_failed, cause.result.toString())
                else -> App.str(R.string.err_login_failed, t.message ?: t.javaClass.simpleName)
            }
            authJob = null
            _state.value = State.NeedLogin(msg)
        } finally {
            _prompt.value = null
        }
    }
}
