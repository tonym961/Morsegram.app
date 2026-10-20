package it.iotatec.morsegram.baseline

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Package dell'app telefono (release / nonMinifiedRelease / benchmarkRelease: nessun applicationIdSuffix). */
internal const val PKG = "it.iotatec.morsegram"

/**
 * Lista chat di DialogsActivity: una RecyclerListView (RecyclerView, senza id risorsa: le view upstream sono create da codice),
 * quindi la cerchiamo come primo nodo scrollable della finestra dell'app. Sulla schermata di login (device senza account)
 * non c'e' una lista verticale: torna null e i percorsi di scroll vengono saltati (il profilo copre comunque l'avvio).
 */
internal fun MacrobenchmarkScope.waitForChatList(timeoutMs: Long = 15_000): UiObject2? =
    device.wait(Until.findObject(By.pkg(PKG).scrollable(true)), timeoutMs)

/** Fling ripetuti su una lista (giu' e poi su): copre bind/draw delle celle e il prefetch delle immagini. */
internal fun MacrobenchmarkScope.flingList(list: UiObject2, times: Int = 3) {
    repeat(times) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    repeat(times) {
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}

/**
 * Apre la prima riga della lista chat (ChatActivity + ChatMessageCell), scorre i messaggi e torna indietro.
 * Se la prima riga non apre una chat (es. riga Archivio) il back torna comunque alla lista: nessun errore.
 */
internal fun MacrobenchmarkScope.openFirstChatAndScroll() {
    val list = waitForChatList() ?: return
    val first = list.children.firstOrNull() ?: return
    first.click()
    device.waitForIdle()
    val messages = device.wait(Until.findObject(By.pkg(PKG).scrollable(true)), 10_000)
    if (messages != null && messages != list) {
        // la lista messaggi parte dal fondo: prima verso l'alto (messaggi vecchi), poi di nuovo giu'
        repeat(3) {
            messages.fling(Direction.UP)
            device.waitForIdle()
        }
        repeat(3) {
            messages.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }
    device.pressBack()
    device.waitForIdle()
}
