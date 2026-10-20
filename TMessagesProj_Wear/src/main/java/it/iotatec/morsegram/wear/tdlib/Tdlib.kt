package it.iotatec.morsegram.wear.tdlib

import android.content.Context
import android.os.Build
import android.util.Log
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.Update
import it.iotatec.morsegram.wear.BuildConfig
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sessione TDLib unica per l'app Wear. Un solo client per processo; gli update vengono
 * ripubblicati su [updates] (replay 0) così ogni feature li osserva senza toccare il client.
 *
 * Uso: `Tdlib.start(context)` all'avvio dell'app; `Tdlib.client` per le richieste;
 * `Tdlib.req { getMe() }` per avere un `Result<T>` kotlin.
 */
object Tdlib {
    private const val TAG = "Tdlib"
    private const val PREFS = "tdlib"
    private const val KEY_TEST_DC = "use_test_dc"
    private const val UPDATES_BUFFER = 4096

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var currentClient: TdlClient? = null
    private val clientMutex = Mutex()
    private var collectJob: Job? = null
    private lateinit var appContext: Context

    private val _authState = MutableStateFlow<AuthorizationState?>(null)
    val authState: StateFlow<AuthorizationState?> = _authState.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState?>(null)
    val connectionState: StateFlow<ConnectionState?> = _connectionState.asStateFlow()

    // Buffer ampio: tryEmit scarta gli update quando il collector più lento è in ritardo (vedi createClient).
    private val _updates = MutableSharedFlow<Update>(extraBufferCapacity = UPDATES_BUFFER)
    /** Tutti gli update TDLib (dopo il primo collector). Filtrare con `filterIsInstance<UpdateX>()`. */
    val updates: SharedFlow<Update> = _updates.asSharedFlow()

    val client: TdlClient
        get() = currentClient ?: error("Tdlib.start() non ancora chiamato")

    /** Vero se la sessione corrente è sul DC di test (99966XYYYY). Persistito: sopravvive al riavvio. */
    var useTestDc: Boolean
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TEST_DC, BuildConfig.USE_TEST_DC)
        private set(value) { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEST_DC, value).apply() }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (currentClient != null) return
        scope.launch { clientMutex.withLock { if (currentClient == null) createClient() } }
    }

    /**
     * Ricrea il client con un altro DC (test/prod). Serve quando il token di login arriva dal
     * telefono con `isTest` diverso da quello della sessione corrente. Distrugge il database locale
     * (nessun account loggato in quel momento, quindi niente perdita di dati).
     */
    suspend fun restart(useTestDc: Boolean) {
        clientMutex.withLock {
            if (this.useTestDc == useTestDc && currentClient != null) return
            closeClientLocked()
            this.useTestDc = useTestDc
            dbDir(useTestDc).deleteRecursively()
            createClient()
        }
    }

    private suspend fun createClient() {
        val c = TdlClient.create()
        currentClient = c
        _authState.value = null
        collectJob?.cancel()
        collectJob = scope.launch {
            c.allUpdates.collect { u ->
                when (u) {
                    is dev.g000sha256.tdl.dto.UpdateAuthorizationState -> onAuthState(c, u.authorizationState)
                    is dev.g000sha256.tdl.dto.UpdateConnectionState -> _connectionState.value = u.state
                    else -> Unit
                }
                // mai bloccare la raccolta degli update: un collector lento non deve fermare TDLib.
                // I repository tollerano la perdita (le chat sconosciute vengono richieste con getChat).
                if (!_updates.tryEmit(u)) Log.w(TAG, "update scartato (buffer pieno): ${u.javaClass.simpleName}")
            }
        }
        // sveglia TDLib: la prima richiesta fa arrivare authorizationStateWaitTdlibParameters
        c.getAuthorizationState()
    }

    private suspend fun closeClientLocked() {
        val c = currentClient ?: return
        try { c.close() } catch (e: Exception) { Log.w(TAG, "close: ${e.message}") }
        collectJob?.cancel()
        currentClient = null
        _authState.value = null
    }

    private suspend fun onAuthState(c: TdlClient, state: AuthorizationState) {
        _authState.value = state
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> {
                val test = useTestDc
                val db = dbDir(test).apply { mkdirs() }
                val r = c.setTdlibParameters(
                    test,                                  // useTestDc
                    db.absolutePath,                       // databaseDirectory
                    File(db, "files").absolutePath,        // filesDirectory
                    ByteArray(0),                          // databaseEncryptionKey
                    true,                                  // useFileDatabase
                    true,                                  // useChatInfoDatabase
                    true,                                  // useMessageDatabase
                    false,                                 // useSecretChats
                    BuildConfig.APP_ID,
                    BuildConfig.APP_HASH,
                    Locale.getDefault().language.ifEmpty { "en" },
                    "${Build.MANUFACTURER} ${Build.MODEL} (Wear OS)",
                    "Android ${Build.VERSION.RELEASE}",
                    BuildConfig.VERSION_NAME,
                )
                if (r is TdlResult.Failure) Log.e(TAG, "setTdlibParameters: ${r.code} ${r.message}")
                applyDebugProxy(c)
            }
            is AuthorizationStateClosed -> {
                // dopo logOut TDLib chiude il client: ne serve uno nuovo per riloggarsi
                scope.launch { clientMutex.withLock { currentClient = null; createClient() } }
            }
            else -> Unit
        }
    }

    /**
     * Solo build DEBUG: proxy SOCKS5 per l'emulatore, la cui rete guest non esce su internet.
     * `adb shell setprop debug.morsegram.socks 10.0.2.2:1080` (10.0.2.2 = host; socks5-host.py sull'host).
     * In release la proprietà non viene nemmeno letta.
     */
    private suspend fun applyDebugProxy(c: TdlClient) {
        if (!BuildConfig.DEBUG) return
        val value = try {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.morsegram.socks")).inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { "" }
        if (value.isEmpty()) return
        val host = value.substringBeforeLast(':'); val port = value.substringAfterLast(':').toIntOrNull() ?: return
        val r = c.addProxy(dev.g000sha256.tdl.dto.Proxy(host, port, dev.g000sha256.tdl.dto.ProxyTypeSocks5("", "")), true, "debug-emulator")
        Log.w(TAG, "debug proxy socks5 $host:$port -> " + (if (r is TdlResult.Failure) "ERRORE ${r.code} ${r.message}" else "attivo"))
    }

    private fun dbDir(test: Boolean): File = File(appContext.filesDir, if (test) "tdlib-test" else "tdlib")

    /**
     * Esegue una richiesta e converte il risultato in `Result<T>`. La cancellazione della coroutine
     * chiamante NON viene incapsulata nel Result: si propaga, così `onFailure` non scambia uno
     * scope chiuso per un errore di rete (e i job cancellati terminano davvero).
     */
    suspend fun <T : Any> req(block: suspend TdlClient.() -> TdlResult<T>): Result<T> = try {
        when (val r = client.block()) {
            is TdlResult.Success -> Result.success(r.result)
            is TdlResult.Failure -> Result.failure(TdlException(r.code, r.message))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    inline fun <reified U : Update> updatesOf(): Flow<U> = kotlinx.coroutines.flow.flow {
        updates.collect { if (it is U) emit(it) }
    }
}

class TdlException(val code: Int, override val message: String) : Exception("$code: $message")
