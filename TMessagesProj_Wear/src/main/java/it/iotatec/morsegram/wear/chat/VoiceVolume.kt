package it.iotatec.morsegram.wear.chat

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.rotary.RotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.PositionIndicatorAlignment
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.settings.SettingsPrefs
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.delay

/**
 * Corona rotante come volume, ma solo mentre un messaggio vocale sta suonando: è il momento in cui
 * serve, ed è l'unico in cui si può prendere la corona senza togliere niente di utile (lo
 * scorrimento col dito non passa mai per il canale della corona, quindi resta sempre attivo).
 *
 * **Unità di misura.** La corona arriva come [RotaryScrollEvent] in pixel, perché
 * `AndroidComposeView` moltiplica il valore grezzo dell'asse per
 * `ViewConfiguration.scaledVerticalScrollFactor` (e lo nega). Dividendo per lo stesso fattore si
 * torna in unità d'asse, che sono l'unità naturale della corona: così la soglia di un passo non è
 * un numero di pixel inventato, che cambierebbe con la densità dello schermo.
 *
 * **Verso.** Non è una costante indovinata: `-verticalScrollPixels` è esattamente il delta che
 * `rotaryScrollable` di Wear applicherebbe alla lista (compose.ui nega una volta, Wear rinega),
 * quindi il volume sale nello stesso senso di rotazione che scorre la cronologia in avanti. Se un
 * orologio invertisse la corona nel driver, scorrimento e volume si invertirebbero insieme.
 *
 * **Flusso e indici.** [AudioManager.STREAM_MUSIC] perché [VoicePlayer] usa `USAGE_MEDIA`. Si usa
 * solo [AudioManager.adjustStreamVolume]: con l'absolute volume AVRCP è il sistema a inoltrare
 * l'incremento alle cuffie, mentre un indice assoluto verrebbe rimappato su 0..127 e darebbe
 * salti. Indice ed escursione si rileggono dopo ogni passo perché Android ne tiene una coppia per
 * dispositivo di uscita; i cambi che arrivano da fuori (cuffie collegate a vocale avviato, tasti
 * dell'auricolare, pannello di sistema) non sono invece osservati: l'arco si riallinea al primo
 * scatto di corona, non prima.
 */
@Stable
class VoiceVolume internal constructor(
    private val audio: AudioManager?,
    private val view: View,
    /** Pixel per unità d'asse, cioè la costante che compose.ui ha già moltiplicato. */
    private val scrollFactor: Float,
) {
    /** Preferenza utente (vedi impostazioni): la corona può fare da volume. */
    private var enabled by mutableStateOf(true)

    /** Con l'esplorazione al tocco la corona è dello screen reader: non la prendiamo mai. */
    private var touchExploration by mutableStateOf(false)

    var min by mutableIntStateOf(0)
        private set
    var max by mutableIntStateOf(0)
        private set
    var level by mutableIntStateOf(0)
        private set

    /**
     * Vero quando un rialzo non ha avuto effetto pur essendoci margine: è il tetto del volume di
     * sicurezza, che il sistema impone con le cuffie finché l'utente non conferma il suo avviso.
     * Si alza una volta sola per riproduzione ([begin] lo riazzera).
     */
    var safetyLimitReached by mutableStateOf(false)
        private set

    /**
     * Percorso di uscita a volume fisso, o policy che vieta i cambi di volume: ogni scatto verrebbe
     * mangiato e la corona resterebbe morta per tutta la riproduzione, quindi non la prendiamo.
     */
    private var volumeFixed by mutableStateOf(false)

    /** Resto di rotazione non ancora arrivato a un passo intero, in unità d'asse. */
    private var pending = 0f

    /** Se falso la corona non viene presa e la cronologia scorre esattamente come sempre. */
    val canTake: Boolean
        get() = enabled && !touchExploration && audio != null && max > min && !volumeFixed

    /** Preferenza e stato dello screen reader: cambiano fuori dalla schermata, non durante un vocale. */
    internal fun refreshSettings(context: Context) {
        enabled = SettingsPrefs.crownVolume(context)
        touchExploration =
            context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        refresh()
    }

    /** Un vocale parte: nessun resto di rotazione del precedente, nessun avviso vecchio. */
    internal fun begin() {
        pending = 0f
        safetyLimitReached = false
        refresh()
    }

    /** Il vocale finisce, va in pausa o la schermata esce: la corona torna a scorrere. */
    internal fun end() {
        pending = 0f
        safetyLimitReached = false
    }

    private fun refresh() {
        val am = audio ?: return
        volumeFixed = am.isVolumeFixed
        min = am.getStreamMinVolume(STREAM)
        max = am.getStreamMaxVolume(STREAM)
        level = am.getStreamVolume(STREAM).coerceIn(min, max)
    }

    /**
     * Gestore della passata "pre" della corona. Restituisce `true` solo quando ha davvero preso
     * l'evento: chi chiama deve passargli la rotazione unicamente in modalità volume, così fuori da
     * quella modalità l'evento scende alla lista e lo scorrimento resta intatto.
     */
    fun onRotary(event: RotaryScrollEvent): Boolean {
        val am = audio ?: return false
        if (scrollFactor <= 0f) return false
        // in unità d'asse e nello stesso verso del delta che la lista riceverebbe (vedi commento di classe);
        // il tetto sul resto evita che una rotata veloce accodi passi da smaltire dopo
        pending = (pending - event.verticalScrollPixels / scrollFactor)
            .coerceIn(-MAX_PENDING_UNITS, MAX_PENDING_UNITS)
        if (abs(pending) < STEP_UNITS) return true
        val raise = pending > 0f
        pending -= STEP_UNITS * sign(pending)
        val before = level
        try {
            am.adjustStreamVolume(
                STREAM,
                if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                // niente FLAG_SHOW_UI: il pannello di Clockwork coprirebbe la chat e, essendo un layer
                // della finestra di sistema, rischierebbe di prendersi lui la corona dopo il primo scatto
                0,
            )
        } catch (e: SecurityException) {
            // una policy (p.es. Non disturbare senza accesso alle notifiche) può vietare il cambio:
            // l'evento resta consumato, perché la corona è volume finché si vede l'arco
            return true
        }
        refresh()
        when {
            level != before -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            raise && before < max -> safetyLimitReached = true
        }
        return true
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC

        /**
         * Un passo di volume ogni mezza unità d'asse. La corona dell'orologio è ad alta risoluzione
         * (la feature `android.hardware.rotaryencoder.lowres` non c'è) e la sua risoluzione dichiarata
         * è 0,791 unità/rad, cioè circa 5 unità per giro: vengono una decina di scatti per giro.
         */
        const val STEP_UNITS = 0.5f
        const val MAX_PENDING_UNITS = 1f
    }
}

/**
 * Crea e tiene aggiornata la [VoiceVolume] della schermata. La modalità volume non è uno stato
 * nostro da azzerare a mano: dipende da [playerState], quindi quando il vocale finisce, viene messo
 * in pausa o la schermata esce (il player si ferma in `ON_PAUSE`) la corona torna a scorrere da sé.
 */
@Composable
fun rememberVoiceVolume(playerState: VoicePlayer.State): VoiceVolume {
    val context = LocalContext.current
    val view = LocalView.current
    val volume = remember(context, view) {
        VoiceVolume(
            audio = context.getSystemService(AudioManager::class.java),
            view = view,
            // API 26+, e il modulo parte da minSdk 30: niente bisogno del compat
            scrollFactor = ViewConfiguration.get(context).scaledVerticalScrollFactor,
        ).also { it.refreshSettings(context) }
    }
    // la preferenza può essere stata cambiata nelle impostazioni mentre la chat era sullo sfondo
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { volume.refreshSettings(context) }
    // un vocale nuovo riparte da zero; la fine di quello in corso chiude la modalità
    LaunchedEffect(playerState.isPlaying, playerState.messageId) {
        if (playerState.isPlaying) volume.begin() else volume.end()
    }
    return volume
}

/**
 * Arco del volume al posto dell'indicatore di posizione: è lo stesso indicatore curvo di Wear, nel
 * punto dove l'utente guarda già. La regola che resta da imparare è una sola: la corona è volume
 * esattamente mentre si vede questo arco.
 */
@Composable
fun VoiceVolumeIndicator(volume: VoiceVolume) {
    if (volume.max <= volume.min) return
    // allo screen reader si annuncia solo il valore fermo: uno annuncio per scatto inonderebbe la lettura
    var announced by remember { mutableIntStateOf(volume.level) }
    LaunchedEffect(volume.level) {
        delay(ANNOUNCE_DELAY_MS)
        announced = volume.level
    }
    val name = stringResource(R.string.chat_volume)
    val state = stringResource(R.string.chat_volume_level, announced - volume.min, volume.max - volume.min)
    PositionIndicator(
        value = { volume.level.toFloat() },
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = name
            stateDescription = state
        },
        range = volume.min.toFloat()..volume.max.toFloat(),
        // il default di questo overload è OppositeRsb, cioè il bordo sinistro su un orologio portato
        // normalmente: l'arco salterebbe dall'altra parte dello schermo rispetto all'indicatore di
        // scorrimento che sostituisce (quello usa End)
        position = PositionIndicatorAlignment.End,
    )
}

private const val ANNOUNCE_DELAY_MS = 400L
