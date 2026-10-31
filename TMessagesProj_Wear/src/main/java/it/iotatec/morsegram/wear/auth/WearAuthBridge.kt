package it.iotatec.morsegram.wear.auth

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lato orologio del ponte di accesso col telefono Morsegram. Il lato telefono è
 * `it.iotatec.morsegram.wear.MorsegramWearAuth` (+ `MorsegramWearAuthService`), nostro anch'esso:
 * il protocollo qui sotto è rispecchiato byte per byte.
 *
 * ```
 * watch -> phone  /mg-wear-auth/offer   sessionId(16) || watchPub(256)              watchPub = g^y mod p
 * phone -> watch  /mg-wear-auth/answer  sessionId(16) || noncePhone(16) || phonePub(256)
 * phone -> watch  /mg-wear-auth/ready   sessionId(16)
 * watch -> phone  /mg-wear-auth/qr      sessionId(16) || iv(12) || AES-256-GCM(key, iv, tag 128,
 *                                           AAD "mg-wear-auth/qr", token di login grezzo)
 * phone -> watch  /mg-wear-auth/result  sessionId(16) || iv(12) || AES-256-GCM(key, iv, tag 128,
 *                                           AAD "mg-wear-auth/result",
 *                                           byte stato || messaggio(utf8, fino alla fine))
 *                                           stato: 1 = accettato, 0 = rifiutato dal server,
 *                                                  2 = il telefono ha smesso di aspettare
 * phone -> watch  /mg-wear-auth/declined  sessionId(16)  (chiuso prima che esistesse una chiave)
 * watch -> phone  /mg-wear-auth/cancel    sessionId(16)
 * ```
 * con p = RFC 3526 gruppo 14 (2048 bit), g = 2, ss = encode256(phonePub^y mod p),
 * key = SHA-256(ss || sessionId || noncePhone), sas = SHA-256(ss || watchPub) → 4 emoji da confrontare.
 *
 * **Chi tiene il segreto è l'orologio, non il telefono.** Il token è quello del percorso QR
 * (`requestQrCodeAuthentication` → `tg://login?token=…`), chiesto dall'orologio con il **nostro**
 * api_id; il telefono lo consuma con `auth.acceptLoginToken`, la stessa chiamata che upstream esegue
 * quando si inquadra un QR, aperta a tutti i client. Il giro opposto (il telefono minta un token web
 * e l'orologio lo presenta a `checkAuthenticationWebToken`) è riservato alle app ufficiali e il
 * server lo liquida con `webauth_token_expired`: vedi `MORSEGRAM.md`, sezione del 30/10/2026.
 *
 * Il token vive pochi secondi e TDLib lo rinnova da sé, quindi **non** viene spedito in anticipo:
 * il telefono chiede `/ready` al tocco finale e solo allora l'orologio prende il link più fresco.
 *
 * Trasporto: Wearable Data Layer MessageClient (stesso package + stessa firma dell'app telefono).
 * Il prefisso `/mg-` è nostro: `/tg-wear-auth/` resta a `org.telegram.messenger.WearAuthListenerService`
 * di upstream, che non tocchiamo.
 */
class WearAuthBridge(context: Context) {

    /** Esito del ponte, dal primo byte del payload di `/result`. */
    sealed interface Outcome {
        /** `auth.acceptLoginToken` è andata a buon fine: TDLib porterà a Ready (o WaitPassword con 2FA). */
        data object Accepted : Outcome

        /** Il server ha rifiutato: [message] è il suo testo, da mostrare così com'è. */
        data class Refused(val message: String) : Outcome

        /**
         * Il telefono ha smesso di aspettare prima che il nostro token arrivasse (il suo
         * `QR_WAIT_MS` è scaduto). Non è un rifiuto di Telegram: va detto come un tempo scaduto,
         * altrimenti si accusa il server di qualcosa che non ha fatto.
         */
        data object PhoneGaveUp : Outcome

        /**
         * Il telefono ha chiuso la richiesta **prima** che ci fosse una chiave condivisa: foglio
         * scartato dall'utente, nessun account attivo, o nessuna finestra su cui aprirlo. Arriva su
         * `/declined` in chiaro — a quel punto non c'è niente da cifrare — e senza di esso
         * l'orologio resterebbe tre minuti ad aspettare per poi dare la colpa alla versione
         * dell'app sul telefono.
         */
        data object PhoneDeclined : Outcome
    }

    /** Nessun telefono raggiungibile via Data Layer (nessun nodo connesso, API non disponibile, invio fallito). */
    class NoPhoneException(message: String) : Exception(message)

    /** Il telefono non ha risposto entro il tempo massimo. */
    class TimeoutException(message: String) : Exception(message)

    /** Il telefono ha risposto con dati non validi (chiave fuori range, cifratura non verificata, formato errato). */
    class ProtocolException(message: String) : Exception(message)

    /** Il telefono ha chiesto il token ma TDLib non ne ha ancora uno (percorso QR non partito). */
    class NoTokenException(message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)

    @Volatile private var offeredNodes: List<String> = emptyList()
    @Volatile private var peerNode: String? = null
    @Volatile private var completed = false
    /** Serve a [sendCancel], che gira dopo la fine di [run] e non vede più la `Session`. */
    @Volatile private var sessionId: ByteArray? = null

    /** Vero se l'offer è stata consegnata ad almeno un telefono e l'esito non è ancora arrivato. */
    val needsCancel: Boolean get() = offeredNodes.isNotEmpty() && !completed

    /**
     * Esegue tutto l'handshake e sospende finché il telefono non riporta un esito (o fallisce). Lavora
     * su [Dispatchers.Default] (modPow a 2048 bit). Cancellare la coroutine rimuove il listener;
     * chi cancella deve poi chiamare [sendCancel] per chiudere il foglio sul telefono.
     *
     * [freshToken] viene invocata **solo** quando il telefono è pronto ad accettare, e deve tornare
     * il token di login del momento (null se non c'è: diventa [NoTokenException]).
     */
    suspend fun run(
        onOfferSent: suspend (nodes: Int) -> Unit,
        onEmojis: suspend (emojis: List<String>) -> Unit,
        onAccepting: suspend () -> Unit,
        freshToken: suspend () -> ByteArray?,
    ): Outcome = withContext(Dispatchers.Default) {
        val session = Session()
        sessionId = session.sessionId
        val nodes = try {
            nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes: ${e.message}")
            throw NoPhoneException("wearable api: ${e.message}")
        }
        if (nodes.isEmpty()) throw NoPhoneException("no connected nodes")

        val inbox = Channel<MessageEvent>(Channel.UNLIMITED)
        val listener = MessageClient.OnMessageReceivedListener { event -> inbox.trySend(event) }
        messageClient.addListener(listener).await()
        try {
            val offer = session.offer()
            val delivered = nodes.filter { node ->
                runCatching { messageClient.sendMessage(node.id, PATH_OFFER, offer).await() }
                    .onFailure { Log.w(TAG, "offer to ${node.id} (${node.displayName}) failed: ${it.message}") }
                    .isSuccess
            }
            if (delivered.isEmpty()) throw NoPhoneException("offer not delivered")
            offeredNodes = delivered.map { it.id }
            Log.d(TAG, "offer delivered to ${delivered.size} node(s), session ${session.sessionId.hex()}")
            onOfferSent(delivered.size)
            awaitOutcome(session, inbox, onEmojis, onAccepting, freshToken)
        } finally {
            messageClient.removeListener(listener)
            inbox.close()
        }
    }

    /**
     * Consuma answer, ready e result dalla coda dei messaggi; ignora quelli di altre sessioni.
     * L'attesa si accorcia a ogni passo: 3 min per aprire il telefono, 3 min per confrontare le
     * emoji, poi solo il giro di rete della chiamata al server.
     */
    private suspend fun awaitOutcome(
        session: Session,
        inbox: Channel<MessageEvent>,
        onEmojis: suspend (emojis: List<String>) -> Unit,
        onAccepting: suspend () -> Unit,
        freshToken: suspend () -> ByteArray?,
    ): Outcome {
        var timeoutMs = ANSWER_TIMEOUT_MS
        while (true) {
            val event = withTimeoutOrNull(timeoutMs) { inbox.receive() }
                ?: throw TimeoutException("no reply from phone")
            when (event.path) {
                PATH_ANSWER -> {
                    val emojis = session.onAnswer(event.data) ?: continue
                    peerNode = event.sourceNodeId
                    timeoutMs = READY_TIMEOUT_MS
                    Log.d(TAG, "answer from ${event.sourceNodeId}; sas emojis=$emojis")
                    onEmojis(emojis)
                }
                PATH_READY -> {
                    if (!session.isOurs(event.data)) continue
                    // Solo il nodo che ha firmato l'answer puo' chiedere il token, e il token va a
                    // lui: il sessionId non e' segreto, quindi un /ready di un altro nodo non deve
                    // ne' innescare l'invio ne' spostarne la destinazione.
                    val node = peerNode ?: continue
                    if (event.sourceNodeId != node) {
                        Log.w(TAG, "ready from ${event.sourceNodeId}, atteso da $node: ignorato")
                        continue
                    }
                    onAccepting()
                    val token = freshToken() ?: throw NoTokenException("no login token yet")
                    val wire = session.sealToken(token)
                    runCatching { messageClient.sendMessage(node, PATH_QR, wire).await() }
                        .onFailure { throw NoPhoneException("qr not delivered: ${it.message}") }
                    Log.d(TAG, "login token sent to $node (${token.size} bytes)")
                    timeoutMs = RESULT_TIMEOUT_MS
                }
                PATH_RESULT -> {
                    val outcome = session.openResult(event.data) ?: continue
                    completed = true
                    Log.d(TAG, "result from phone: $outcome")
                    return outcome
                }
                PATH_DECLINED -> {
                    // In chiaro: arriva da un telefono che non ha ancora una chiave. Non e'
                    // autenticato, quindi al massimo un nodo che conosce il sessionId puo' farci
                    // annullare — un disturbo, non un accesso: si rimedia con "Riprova".
                    if (!session.isOurSessionId(event.data)) continue
                    completed = true
                    Log.d(TAG, "declined from ${event.sourceNodeId}")
                    return Outcome.PhoneDeclined
                }
            }
        }
    }

    /**
     * Avvisa il telefono che l'orologio ha abbandonato la sessione (chiude il foglio di conferma).
     * Porta il sessionId: un cancel in ritardo — p.es. quello del "Riprova", spedito mentre la
     * nuova offerta è già arrivata — altrimenti chiuderebbe la sessione sbagliata.
     */
    suspend fun sendCancel() {
        val id = sessionId ?: return
        val targets = peerNode?.let { listOf(it) } ?: offeredNodes
        offeredNodes = emptyList()
        for (node in targets) {
            runCatching { messageClient.sendMessage(node, PATH_CANCEL, id).await() }
                .onFailure { Log.w(TAG, "cancel to $node failed: ${it.message}") }
        }
    }

    /** Stato crittografico di una sessione: esponente privato y, chiave condivisa dopo l'answer. */
    private class Session {
        val sessionId = ByteArray(SESSION_ID_LEN).also { random.nextBytes(it) }
        private val y = BigInteger(DH_BITS, random)
        val watchPub: ByteArray
        private var sharedKey: ByteArray? = null

        init {
            val gy = DH_G.modPow(y, DH_P)
            check(isValidPub(gy)) { "our pubkey invalid (extremely unlikely)" }
            watchPub = encode256(gy)
        }

        fun offer(): ByteArray = sessionId + watchPub

        /** answer = sessionId(16) || noncePhone(16) || phonePub(256). Null = non per questa sessione. */
        fun onAnswer(data: ByteArray?): List<String>? {
            if (data == null || data.size != SESSION_ID_LEN + NONCE_LEN + DH_PUBKEY_LEN) return null
            if (!MessageDigest.isEqual(sessionId, data.copyOfRange(0, SESSION_ID_LEN))) return null
            if (sharedKey != null) return null // answer duplicata
            val noncePhone = data.copyOfRange(SESSION_ID_LEN, SESSION_ID_LEN + NONCE_LEN)
            val phonePubBytes = data.copyOfRange(SESSION_ID_LEN + NONCE_LEN, data.size)
            val phonePub = BigInteger(1, phonePubBytes)
            if (!isValidPub(phonePub)) throw ProtocolException("peer pubkey out of range")
            val ss = encode256(phonePub.modPow(y, DH_P))
            sharedKey = sha256(ss, sessionId, noncePhone)
            val sas = sha256(ss, watchPub)
            return emojify(sas, 4)
        }

        /** Solo il sessionId: vale per `/declined`, che può arrivare prima che ci sia una chiave. */
        fun isOurSessionId(data: ByteArray?): Boolean =
            data != null && data.size == SESSION_ID_LEN && MessageDigest.isEqual(sessionId, data)

        /** ready = sessionId(16), in chiaro: non porta segreti, serve solo a dire "chiedi ora". */
        fun isOurs(data: ByteArray?): Boolean = isOurSessionId(data) && sharedKey != null

        /** qr = sessionId(16) || iv(12) || AES-GCM(token grezzo), AAD = [AAD_QR]. */
        fun sealToken(token: ByteArray): ByteArray {
            val key = sharedKey ?: throw ProtocolException("ready before answer")
            val iv = ByteArray(AES_GCM_IV_LEN).also { random.nextBytes(it) }
            return sessionId + iv + aeadEncrypt(key, iv, AAD_QR, token)
        }

        /** result = sessionId(16) || iv(12) || AES-GCM(byte stato || messaggio). Null = non per questa sessione. */
        fun openResult(data: ByteArray?): Outcome? {
            if (data == null || data.size < SESSION_ID_LEN + AES_GCM_IV_LEN + AES_GCM_TAG_LEN + 1) return null
            if (!MessageDigest.isEqual(sessionId, data.copyOfRange(0, SESSION_ID_LEN))) return null
            val key = sharedKey ?: throw ProtocolException("result before answer")
            val iv = data.copyOfRange(SESSION_ID_LEN, SESSION_ID_LEN + AES_GCM_IV_LEN)
            val ct = data.copyOfRange(SESSION_ID_LEN + AES_GCM_IV_LEN, data.size)
            val plain = try {
                aeadDecrypt(key, iv, AAD_RESULT, ct)
            } catch (e: Exception) {
                throw ProtocolException("result decrypt failed: ${e.message}")
            }
            if (plain.isEmpty()) throw ProtocolException("empty result")
            return when (plain[0]) {
                RESULT_OK -> Outcome.Accepted
                RESULT_GAVE_UP -> Outcome.PhoneGaveUp
                // RESULT_REFUSED e qualunque valore ignoto: un telefono piu' nuovo che inventasse
                // un quarto stato verrebbe trattato come rifiuto, che e' il lato prudente.
                else -> Outcome.Refused(
                    String(plain, 1, plain.size - 1, Charsets.UTF_8).ifBlank { "?" }
                )
            }
        }
    }

    companion object {
        private const val TAG = "WearAuthBridge"

        const val PATH_OFFER = "/mg-wear-auth/offer"
        const val PATH_ANSWER = "/mg-wear-auth/answer"
        const val PATH_READY = "/mg-wear-auth/ready"
        const val PATH_QR = "/mg-wear-auth/qr"
        const val PATH_RESULT = "/mg-wear-auth/result"
        const val PATH_DECLINED = "/mg-wear-auth/declined"
        const val PATH_CANCEL = "/mg-wear-auth/cancel"

        private const val SESSION_ID_LEN = 16
        private const val NONCE_LEN = 16
        private const val DH_PUBKEY_LEN = 256
        private const val DH_BITS = 2048
        private const val AES_GCM_IV_LEN = 12
        private const val AES_GCM_TAG_LEN = 16

        /** Primo byte del payload di `/result`; gli stessi valori di `MorsegramWearAuth.RESULT_*`. */
        private const val RESULT_OK: Byte = 1
        private const val RESULT_GAVE_UP: Byte = 2

        /**
         * Separazione di direzione per AES-GCM. I due messaggi cifrati viaggiano sotto la **stessa**
         * chiave: senza un AAD diverso per verso, un `/qr` rimandato indietro sul percorso `/result`
         * si decifrerebbe senza errori e il suo primo byte (il primo byte del token) finirebbe
         * interpretato come stato — 1 darebbe un falso "accettato". Con l'AAD la verifica del tag
         * fallisce e il messaggio viene scartato. Stesse costanti in `MorsegramWearAuth`.
         */
        private val AAD_QR = "mg-wear-auth/qr".toByteArray(Charsets.US_ASCII)
        private val AAD_RESULT = "mg-wear-auth/result".toByteArray(Charsets.US_ASCII)

        private const val ANSWER_TIMEOUT_MS = 3 * 60_000L
        private const val READY_TIMEOUT_MS = 3 * 60_000L
        private const val RESULT_TIMEOUT_MS = 60_000L

        private val random = SecureRandom()

        /** RFC 3526, gruppo 14 (2048 bit) — identico a MorsegramWearAuth.DH_P. */
        private val DH_P = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF",
            16,
        )
        private val DH_G: BigInteger = BigInteger.valueOf(2)

        private fun isValidPub(v: BigInteger): Boolean =
            v > BigInteger.ONE && v < DH_P.subtract(BigInteger.ONE)

        /** 256 byte big-endian, senza il byte di segno di `toByteArray`, con zeri a sinistra se corto. */
        private fun encode256(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == DH_PUBKEY_LEN -> raw
                raw.size == DH_PUBKEY_LEN + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
                raw.size < DH_PUBKEY_LEN -> ByteArray(DH_PUBKEY_LEN).also { raw.copyInto(it, DH_PUBKEY_LEN - raw.size) }
                else -> error("unexpected DH value size ${raw.size}")
            }
        }

        private fun sha256(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in parts) md.update(p)
            return md.digest()
        }

        private fun aeadEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_LEN * 8, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(plaintext)
        }

        private fun aeadDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_LEN * 8, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }

        private fun emojify(sas: ByteArray, count: Int): List<String> {
            val table = EMOJIS
            return List(count) { i -> table[(bytesToLong(sas, i * 8) % table.size).toInt()] }
        }

        /** Come MorsegramWearAuth.bytesToLong: primo byte mascherato con 0x7F → sempre non negativo. */
        private fun bytesToLong(a: ByteArray, off: Int): Long =
            ((a[off].toLong() and 0x7F) shl 56) or
                ((a[off + 1].toLong() and 0xFF) shl 48) or
                ((a[off + 2].toLong() and 0xFF) shl 40) or
                ((a[off + 3].toLong() and 0xFF) shl 32) or
                ((a[off + 4].toLong() and 0xFF) shl 24) or
                ((a[off + 5].toLong() and 0xFF) shl 16) or
                ((a[off + 6].toLong() and 0xFF) shl 8) or
                (a[off + 7].toLong() and 0xFF)

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        /**
         * Tabella SAS: copia verbatim di `WearAuthSheet.getEmojis()` di upstream, duplicati inclusi
         * (la lunghezza dell'array entra nel modulo). Le due copie nostre — questa e
         * `MorsegramWearAuth.EMOJIS` — devono coincidere riga per riga, altrimenti telefono e
         * orologio mostrano emoji diverse e l'utente annulla un accesso legittimo.
         */
        private val EMOJIS: Array<String> = arrayOf(
            "👋", "👍", "👎", "👌", "👊", "🤟", "🫵", "👏", "🤝", "✍", "💪", "👀", "👅", "🥶",
            "🤡", "💀", "👽", "😈", "😎", "🤠", "🤩", "😍", "🤯", "🦄", "🐶", "🐷", "🐔", "🐥",
            "🦊", "🐙", "🐸", "🐳", "🦉", "🦆", "🐢", "🦖", "🐵", "🐝", "🦁", "🐧", "🦋", "🐬",
            "🦀", "🐌", "🦠", "🐠", "🌵", "💐", "💐", "🎄", "🍄", "🍔", "🍕", "☕", "🍩", "🍪",
            "🎂", "🍫", "🍭", "🍎", "🥥", "🍒", "🌶", "🥒", "🥦", "🍇", "🍋", "🍓", "🍌", "🍍",
            "🍆", "🌽", "🍺", "🍷", "🍾", "🍦", "🍰", "🍞", "🍖", "🌭", "🧊", "🍳", "⭐", "☁",
            "🚀", "🎈", "💎", "💡", "🔑", "❄", "🔎", "👠", "👕", "👗", "👖", "👙", "👜", "👓",
            "🎀", "💄", "💍", "♠", "❤", "♦", "♣", "🌈", "🌊", "🎃", "👻", "🎁", "🔮", "🎥",
            "💿", "💻", "📡", "🔉", "⏳", "🔒", "🚗", "🔱", "🔗", "🎲", "🎮", "⚽", "🎳", "🏁",
            "🏆", "🎸", "💣", "🚽", "🎹", "🎤", "🎨", "🔫", "💊", "💰", "📦", "📅", "📚", "❗",
            "❓", "💯", "💦", "💤", "🌍", "🏝", "🚂", "🛢", "🛹", "🚢", "✈", "🛎", "🧳", "🌖",
            "🌞", "🔥", "🏓", "🎰", "🧸", "🪩", "🎭", "👑", "🎩", "🧢", "🔈", "🔋", "🕯", "✏",
            "💼", "📌", "✂", "🗑", "🛡", "⚙", "🧲", "🪏", "⚖", "🧪", "🚪", "🫧", "🛒", "🪑",
            "🗿", "🏁", "🏴‍☠", "📊", "🥁", "🎧", "🎵", "🧩", "⛳", "🥇", "🥈", "🥈", "🌪", "⛺",
            "🧭", "🫆", "🧠", "💋"
        )
    }
}
