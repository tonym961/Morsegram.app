/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ui;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.chat.SendButtonBlockedByTypingView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Modalità mancini: il pulsante di invio a sinistra, raggiungibile col pollice sinistro.
 *
 * <h3>Perché non si specchia tutta la barra</h3>
 * Lo studio di fattibilità ha contato, in {@code ChatActivityEnterView} (15.748 righe), <b>94 decisioni di lato in
 * 70 agganci</b> distinti: 30 gravity LEFT/RIGHT, 9 margini, 27 translationX/pivotX, 21 pezzi di geometria disegnata
 * a mano su {@code Canvas}, 2 la direzione del gesto, 5 il verso dei fumetti. La via nativa non esiste:
 * l'app dichiara {@code android:supportsRtl="false"} con {@code tools:replace} (AndroidManifest.xml:108 e :112),
 * {@code LayoutHelper.createFrame} scrive gravity e margini ASSOLUTI
 * ({@code createFrameRelatively}, l'unica variante che converte, in quel file compare 0 volte), il Canvas non ha
 * direzione di layout e il gesto è aritmetica su {@code MotionEvent.getX()}. La prova: {@code LocaleController.isRTL}
 * in quel file compare UNA volta sola (riga 8129, e riguarda la didascalia) — upstream non specchia la barra di
 * scrittura nemmeno per l'arabo. Specchiarla a mano costerebbe 75-94 righe marcate in un file che upstream cambia a
 * ogni drop (168 drop su 624, 15 su 15 degli ultimi 15), cioè da sola quanto tutto il perimetro del fork.
 *
 * <h3>Il disegno: la pillola prende una corsia ritagliata dal margine sinistro del campo</h3>
 * La pillola di invio NON va a 4dp dal bordo sinistro dello schermo, dove avrebbe dovuto scavalcare il pulsante
 * emoji, il selettore «invia come» e il menu bot: va <b>subito a sinistra del bordo sinistro del campo di testo</b>,
 * in una corsia di {@code larghezzaPillola + 2 * aria} che si ritaglia dal <i>margine sinistro del campo</i>. Con la
 * barra a 360dp, pillola 36dp e aria 6dp: emoji resta a {@code [3,47]}, la pillola sta a {@code [56,92]} e il campo
 * parte da 98 invece che da 50.
 *
 * <p>Tre conseguenze, tutte volute:
 * <ul>
 * <li><b>La colonna di sinistra non si tocca.</b> {@code emojiButton}, {@code senderSelectView},
 *     {@code botCommandsMenuButton}, {@code deleteRichDraftButton} restano esattamente dove li mette upstream.
 *     Niente contende il posto al pulsante emoji, e l'animazione del selettore mittente (:11193-11195, che deriva lo
 *     scorrimento di emoji e campo da {@code senderSelectView.getLayoutParams().leftMargin}) continua a fare la sua
 *     aritmetica sui valori di upstream. Resta corretto anche il fumetto del pacchetto emoji del gruppo
 *     ({@code ChatActivity:27573}, {@code setJointPx(0, getEmojiButton().getX() + dp(22))}), che col disegno
 *     precedente — la colonna intera traslata a destra — avrebbe puntato 46dp fuori bersaglio.</li>
 * <li><b>Il bordo destro del campo non si muove mai.</b> {@code messageEditTextContainer} non viene più traslato:
 *     la corsia riservata a destra ({@code richButton}, {@code aiButton}, {@code sendOutlineView},
 *     {@code suggestButton}, {@code doneButton} e lo slot stesso) non viene invasa. Lo spazio davvero libero a destra
 *     del testo non era il rightMargin da 44dp del contenitore — quello è la corsia riservata, non spazio libero — ma
 *     solo il rightMargin del campo, che con testo presente vale {@code dp(2)} ({@code updateFieldRight}, ramo
 *     {@code else}, :8791): qualunque traslazione a destra invadeva sempre.</li>
 * <li><b>Si sposta il margine, non la {@code translationX} del campo.</b> Spostare il bordo sinistro del campo via
 *     margine fa sì che {@code getLeft()}, {@code getX()}, {@code getLocationInWindow()} e
 *     {@code ViewPositionWatcher.computeCoordinatesInParent()} siano automaticamente corretti per tutti i
 *     consumatori, {@code SuggestEmojiView} (:760/:763, che legge {@code getEditField().getX()}) compreso, a zero
 *     righe upstream. {@code messageEditText.setTranslationX} invece ha SETTE alimentatori upstream
 *     ({@code MESSAGE_TEXT_TRANSLATION_X} :1189, {@code setBotWebViewButtonOffsetX} :4616, l'animazione del
 *     selettore mittente :11215-11295 con valori fino a −42,66dp, i tre reset della registrazione :7076, :7241,
 *     :9571, :9672, e {@code setHorizontalPadding} :14727): qualunque nostro valore sarebbe stato cancellato in
 *     silenzio alla prima apparizione del selettore «invia come» o alla fine di un vocale.</li>
 * </ul>
 *
 * <h3>Gli agganci</h3>
 * Quattro righe upstream in tutto, due file.
 * <ul>
 * <li>{@code checkSendButton} in coda ({@code ChatActivityEnterView}:8758), dove upstream fa già la stessa cosa con
 *     {@code suggestButton}: decide se la modalità si applica adesso.</li>
 * <li>{@link #rientraIlCampo} in {@code ChatActivityEnterView.onMeasure}, subito dopo la catena if/else che
 *     riscrive i leftMargin e <b>prima</b> di {@code super.onMeasure}. Upstream riscrive a ogni misurazione il
 *     leftMargin di {@code emojiButton}, {@code deleteRichDraftButton}, {@code messageEditText} e
 *     {@code richDraftPreview} in tre rami (menu bot, selettore mittente, normale): è un imbuto unico, sempre
 *     attraversato. Noi ci <b>sommiamo</b> al valore che upstream ha appena scritto, invece di sostituirlo, quindi i
 *     tre rami continuano a funzionare da soli e upstream non ci può spegnere in silenzio. L'ordine è la ragione
 *     principale dell'aggancio: deve girare DOPO le scritture di upstream e PRIMA della misurazione.</li>
 * <li>{@link #traslazioneSlot(View)} due volte in {@code MessageSendPreview} (:458 e :1523): togliere la nostra
 *     traslazione dalla x dell'ancora di invio. Vedi sotto.</li>
 * </ul>
 *
 * <h3>Le tre viste che si muovono, e nessuna costa una riga</h3>
 * <ul>
 * <li>{@code sendButtonContainer} (lo slot, BOTTOM|RIGHT) scivola a sinistra quanto basta perché il bordo destro
 *     della pillola finisca ad {@code aria} dal bordo sinistro del campo. La pillola è disegnata dal bordo destro del
 *     proprio box ({@code getMeasuredWidth() - dp(4)}) ma la sua larghezza NON si deduce dall'altezza: la dichiara
 *     lei stessa con {@code SendButton.width()}, che di norma vale il cerchio {@code altezza - dp(8)} e con i
 *     messaggi a pagamento diventa una pillola larga quanto il prezzo in stelle (vedi
 *     {@link #larghezzaPillola(View, int)}).</li>
 * <li>Il <b>leftMargin</b> di {@code messageEditText} e {@code richDraftPreview} guadagna il rientro
 *     {@code larghezzaPillola + 2 * aria}. Entrambi i campi sono pubblici, ma la riga upstream serve per l'ordine, e
 *     passandoli come argomenti <b>fallisce in compilazione</b> se upstream li rinomina o li rimuove, invece di
 *     spegnersi in silenzio.</li>
 * <li>La <b>larghezza dei LayoutParams</b> dello slot passa da {@code dp(100)} a {@code larghezzaPillola + dp(8)}
 *     mentre l'offset è attivo, e torna al valore di upstream quando non lo è. Serve perché il box dello slot da
 *     100dp arriva 60dp a sinistra della pillola e copre il pulsante emoji: innocuo di norma — a :15428
 *     {@code SendButton.onTouchEvent} restituisce {@code false} fuori dal rettangolo della pillola, lo slot non è
 *     cliccabile e il microfono è controtraslato, quindi il tocco cade sul pulsante emoji sottostante — <b>ma non con
 *     lo screen reader</b>, perché a :3524 upstream appende {@code setOnLongClickListener} allo slot e un
 *     {@code longClickable} ingoia tutto il suo box.
 *     <p>Restringere lo slot non sposta NIENTE di disegnato, ed è verificato riga per riga: tutti e sei i suoi figli
 *     sono {@code Gravity.RIGHT | Gravity.BOTTOM} ({@code audioVideoButtonContainer} :3256, {@code cancelBotButton}
 *     :3471, {@code sendButton} :3515, {@code sendButtonBlockedByTypingView} :3537, {@code slowModeButton} :3549,
 *     {@code expandStickersButton} :3992) e lo slot è a sua volta RIGHT nel padre, quindi i due bordi destri
 *     coincidono e la posizione assoluta di ogni figlio non dipende dalla larghezza dello slot. In particolare
 *     {@code sendButton} è misurato EXACTLY 100 e resta a {@code barra - 100}, qualunque sia la larghezza dello slot:
 *     per questo i fumetti «programma»/«senza suono» ({@code ChatActivity}:2616 e :2642), che si ancorano a
 *     {@code getSendButton()} — cioè a {@code sendButton}, non allo slot (:10311) — e correggono con
 *     {@code offsetCx() = dp(100-44)/2} cablato sui 100dp, restano <b>esatti</b>. Anche {@code onSizeChanged} dello
 *     slot si corregge da solo: {@code pivotX = w - dp(22)} è il centro della pillola sia con w=100 sia con w=44.
 *     Nessuna riga, in tutto l'albero, scrive i LayoutParams dello slot (verificato).</li>
 * </ul>
 *
 * <h3>Gli altri figli dello slot restano a destra</h3>
 * Gli <b>altri figli</b> (microfono, X del bot inline, espandi sticker, slow mode) ricevono la traslazione opposta al
 * <b>nostro</b> contributo alla translationX dello slot (non a quella che si legge dalla vista, che può contenere
 * anche il valore di un altro scrittore), così restano disegnati esattamente dove li mette upstream.
 * Senza questa controtraslazione la traslazione dello slot se li porterebbe dietro, perché
 * {@code audioVideoButtonContainer} è un FIGLIO di {@code sendButtonContainer} (:3256) e upstream lo spegne
 * soltanto alla FINE della dissolvenza (:8265): per tutti i fotogrammi della transizione si vedrebbe il
 * microfono sfumare attraversando la barra intera da destra a sinistra, e al ritorno lo stesso al contrario.
 * Costa zero righe upstream: lo slot è pubblico e si itera sui suoi figli; <b>nessuna riga upstream scrive la
 * translationX di questi figli</b> — in quel file la scrivono soltanto emojiButton, attachButton/attachLayout,
 * botButton, messageEditText, scheduledButton, senderSelectView, suggestButton, le viste della registrazione,
 * {@code audioVideoSendButton} (che è l'icona DENTRO il microfono, non un figlio dello slot) e
 * {@code sendButtonContainer} stesso.
 *
 * <p>Seguono lo slot soltanto la pillola ({@code SendButton}) e {@code SendButtonBlockedByTypingView} (il «ferma» che
 * upstream disegna SOPRA la pillola mentre un bot risponde, :3537): sono lo stesso comando e devono stare dove sta
 * la pillola. Gli altri figli sono occupanti <i>alternativi</i> dello slot, che upstream tiene a destra.
 *
 * <p>La translationX di {@code sendButtonContainer} ha un solo altro scrittore in tutto il file,
 * {@code setHorizontalPadding} (:14746), che è chiamato soltanto da {@code PeerStoriesView} (:7637) e che lì vale
 * circa -148dp a tastiera chiusa: per questo la traslazione è disattivata sulle storie, e i due scrittori non si
 * incontrano mai. La barra delle storie va riconosciuta a <b>ogni</b> passaggio e non solo quando si entra, perché
 * {@code isStories} è un campo pubblico che {@code PeerStoriesView} scrive DOPO il costruttore (:3619), mentre il
 * costruttore stesso chiama già {@code checkSendButton(false)} (:3570), cioè arriva qui con {@code isStories}
 * ancora falso. Per questo {@link #sendSlot} non registra nulla finché non c'è davvero qualcosa da spostare e
 * {@link #applica} ricontrolla il campo e, se è diventato vero, toglie di mezzo la barra per sempre.
 *
 * <h3>L'anteprima del tocco lungo sull'invio</h3>
 * {@code MessageSendPreview} prende la x dell'ancora di invio con {@code getLocationOnScreen}, che include la nostra
 * traslazione, e la usa per cinque cose che si dividono in due metà opposte.
 * <ul>
 * <li><b>Deve restare vera</b> per la pillola copia: {@code sendButtonInitialPosition[0] = pos[0]} (:1498),
 *     {@code sendButton.setX(...)} (:1518) e il lerp di apertura (:394). La copia deve partire e stare dove sta la
 *     pillola vera, altrimenti al tocco lungo si vedrebbe saltare da sinistra a destra.</li>
 * <li><b>Deve essere quella di upstream</b> per tutto ciò che è ancorato al bordo destro della ZONA di invio: il
 *     «right» da cui la lista delle bolle ricava scala e rientro (:458), la x della lista (:1523), quella del menu
 *     delle opzioni (:1531) e quella del selettore degli effetti (:1543). Senza correzione la lista finisce col bordo
 *     destro a 9dp dal bordo sinistro dello schermo e con una scala sbagliata.</li>
 * </ul>
 *
 * <p>Il confine fra le due metà è una riga sola: {@code pos[0] -= Math.round(traslazioneSlot(anchorSendButton))},
 * messa dopo l'ultimo lettore della prima metà e prima del primo lettore della seconda. Correggere i tre consumatori
 * uno per uno costava una riga in più e, soprattutto, <b>li lasciava divergere</b>: correggendo la lista e non il
 * menu, il menu finiva incollato al bordo sinistro mentre la lista a cui appartiene stava a destra, 105dp più in là,
 * a ogni tocco lungo. Con la riga unica non è possibile, e un quarto consumatore che upstream aggiungesse lì sotto
 * nascerebbe corretto. {@code :458} resta una riga a sé perché sta in un altro metodo e legge
 * {@code sendButtonInitialPosition}, cioè il valore della prima metà. Con la pillola a destra
 * {@link #traslazioneSlot(View)} vale 0 e il caso normale non cambia di un pixel.
 *
 * <h3>Il secondo gradino: anche il microfono nella corsia ({@link #voiceLeft()})</h3>
 * Con il suo interruttore acceso il microfono diventa il secondo occupante possibile della corsia, e con lui si
 * spostano il cerchio della registrazione, le onde, la serratura e i due pulsanti che le stanno attorno (pausa e
 * «una volta»). Costa sei righe upstream nuove, non le 75-94 della barra specchiata, perché {@code RecordCircle} e
 * {@code ControlsView} ancorano ciascuna tutto il proprio disegno orizzontale a un solo {@code cx} (vedi
 * {@link #offsetVocale}). Tre delle sei servono a una cosa sola, ed è il difetto che valeva il giro: senza di esse
 * l'annullamento per trascinamento <b>non scatterebbe mai</b> e il vocale partirebbe al rilascio del dito (il conto,
 * bias compreso, sta in {@link #corsaPerAnnullare}).
 *
 * <p>La corsia è larga {@code max(pillola, microfono)} per entrambi gli occupanti, quindi il rientro del campo non
 * cambia quando i due si scambiano il posto: a interruttore acceso <b>sparisce lo scatto</b> dichiarato più sopra
 * come prezzo strutturale, perché la corsia c'è prima che ci sia il testo.
 *
 * <p>È un interruttore separato, e subordinato a {@link #sendLeft()}, perché è l'unica parte che tocca l'apparato di
 * registrazione: chi la trova scomoda la spegne e l'invio a sinistra continua a funzionare.
 *
 * <h4>Due limiti dichiarati, e perché si è scelto di dichiararli</h4>
 * Si è provato a spostare anche la <b>fila</b> della registrazione (punto rosso, cronometro, «scorri per
 * annullare»), rientrando {@code recordPanel} come si rientra il campo. Non si è tenuto, e la ragione è che quel
 * pannello è {@code MATCH_PARENT} nella colonna: rientrarlo non lo sposta, lo <b>accorcia</b>, e la scritta
 * «scorri per annullare» — che {@code SlideTextView} centra nella propria larghezza e ritaglia con un
 * {@code clipRect} ancorato al cronometro — finiva tagliata in testa e in coda, con la freccia del gesto mai
 * visibile. Su una barra stretta, o col menu bot aperto, spariva del tutto: cioè si perdeva l'istruzione del gesto
 * <i>proprio</i> dove il gesto è cambiato. Fra una fila intera che il cerchio copre in parte e un'istruzione
 * illeggibile si è scelta la prima, che è anche quello che upstream accetta a destra, dove il cerchio arriva fin
 * oltre il centro della barra. Quindi: <b>col microfono a sinistra il cerchio copre il punto rosso e può arrivare
 * sul cronometro</b>. Va guardato sullo schermo.
 *
 * <p>E i <b>fumetti</b> restano dove li mette upstream, interi, a destra: quello «trascina in alto per bloccare»
 * (disegnato in {@code ControlsView} con il corpo ancorato al bordo destro) e i due dei pulsanti pausa e «una
 * volta», che sono viste figlie ancorate col margine destro e con la punta a destra
 * ({@code HintView2.DIRECTION_RIGHT}). Portarli a sinistra non è una traslazione — sono larghi quanto mezza barra e
 * uscirebbero dallo schermo — ma un rispecchiamento, cioè altre quattro righe upstream in viste animate. Indicano
 * un pulsante che sta altrove: è brutto, non impedisce niente, e i pulsanti sotto si <b>toccano dove si vedono</b>,
 * perché i loro bersagli ({@code rectF}, {@code pauseRect}) derivano dal {@code cx} che abbiamo agganciato.
 *
 * <h3>Quello che NON fa, e perché va bene</h3>
 * Il <b>pulsante emoji non si sposta</b>, nemmeno al secondo gradino, e la richiesta diceva «invertire». Il motivo è
 * l'hit test: {@code emojiButton} è figlio di {@code messageEditTextContainer}, che è larga {@code barra - 44dp}
 * (:2702), e portarlo dove sta il microfono lo farebbe uscire dal box del padre. Un figlio fuori dal box del padre
 * <b>si vede e non si tocca</b>, perché {@code textFieldContainer} ferma l'hit test al box della colonna: si
 * vedrebbe l'emoji a destra e non si potrebbe premere. Per un mancino, del resto, l'emoji a sinistra sta già bene
 * dov'è: il valore della richiesta era il tasto dei vocali.
 *
 * <p>Restano a destra, a interruttore del microfono spento, il microfono stesso e tutto l'apparato di registrazione.
 * Il vocale col pollice sinistro è comunque già risolto altrove, dal blocco della modalità di registrazione
 * ({@code VoiceSettings.recordModeLocked}) più la serratura di upstream. Restano a destra anche la fila degli
 * allegati (che è nascosta esattamente quando compare la pillola di invio) e la geometria interna di
 * {@code ChatActivityEnterView.SendButton}, che è istanziata da 8 punti fuori da quel file (PhotoViewer, ShareAlert,
 * StickersAlert, ChatAttachAlert, MessageSendPreview, PeerStoriesView, ChatActivity, DialogsActivity) e che toccare
 * da dentro cambierebbe anche quelle schermate.
 *
 * <h3>Cosa si vede, dove si tocca</h3>
 * Si trasla lo slot e non il suo figlio {@code SendButton} perché è lo slot a decidere <i>dove si tocca</i>: il
 * padre {@code textFieldContainer} fa l'hit test sul box dello slot traslato, e dentro quel box
 * {@code SendButton.onTouchEvent} (:15425) accetta solo i punti che cadono nel rettangolo vero della pillola
 * ({@code getWidth() - width()}, {@code getHeight() - height()}). Traslando il solo figlio, la pillola si
 * vedrebbe a sinistra e si toccherebbe a destra; traslando lo slot, pillola disegnata, area cliccabile, ripple e
 * scrim del tocco lungo ({@code sendButton.setOnLongClickListener}, :3522 — sullo slot ci va solo con lo screen
 * reader acceso, :3523-3525) si spostano insieme.
 *
 * <p>Il prezzo della controtraslazione, detto chiaro: un figlio controtraslato si <i>vede</i> fuori dal box dello
 * slot (lo slot ha {@code setClipChildren(false)}, :2975) ma <b>non prende i tocchi</b>, perché l'hit test del padre
 * si ferma al box dello slot. Quindi per i 220 ms della dissolvenza in andata e i 150 ms di quella al ritorno
 * (:8252 e :8669) il microfono si vede dove deve stare ma non si tocca. È esattamente la finestra in cui upstream lo
 * sta spegnendo o accendendo e in cui è comunque inaffidabile: la pillola, che gli si sovrappone, prende il tocco
 * appena la sua alpha supera 0 e la sua scala fa uscire il microfono dall'hit test. Per questo il ritorno dura 150 ms
 * e non 220: la finestra cieca finisce quando finisce la dissolvenza di upstream, non 70 ms dopo.
 *
 * <h3>Dove si applica</h3>
 * Solo nella barra di una chat vera ({@code getParentFragment() != null}): non nelle storie, non nel commento della
 * lista chat, non nelle notifiche popup, non nel foglio dei regali. E non mentre si modifica un messaggio, mentre si
 * registra o con un vocale registrato in attesa (lì a sinistra c'è il cestino): in quegli stati l'offset torna a 0.
 *
 * <p>L'offset torna a 0 anche con il <b>messaggio effimero</b> acceso ({@code isEphemeralMessageVisible()}, :6939).
 * Lì upstream rimpicciolisce lo slot e gli disegna attorno l'anello {@code sendOutlineView} (:2941, 44x44dp,
 * BOTTOM|RIGHT, :15631-15636), che è un FRATELLO dello slot in {@code textFieldContainer} e non un figlio, ed è
 * privato: traslarlo costerebbe una riga upstream e non la spendiamo, quindi col cerchio a sinistra l'anello
 * resterebbe a destra — lo stesso comando spezzato in due punti dello schermo. L'aggancio si ricalcola da solo
 * entrando e uscendo, perché {@code checkIsEphemeralMessage} chiama {@code checkSendButton(animated)} a ogni cambio
 * di stato (:6956) dopo avere già aggiornato il valore che {@code isEphemeralMessageVisible()} legge.
 *
 * <p>E torna a 0 con la <b>bozza ricca</b> attiva ({@code isRichDraftActive()}, :10762): lì il campo è coperto da
 * {@code richDraftPreview} e a destra compare {@code richButton}, che vive nella corsia riservata e non possiamo
 * spostare. Non è una rottura, è una non-applicazione, come le storie, la modifica, il vocale in attesa, il
 * messaggio effimero, il bot inline e lo slow mode.
 *
 * <p>E torna a 0 quando nello slot non c'è una pillola di invio visibile. Nello stato del bot inline upstream spegne
 * la pillola e accende al suo posto la X di annullamento (:8259-8260), che è uno degli altri figli e quindi resta a
 * destra: se lo slot restasse spostato, la X si vedrebbe a destra senza prendere i tocchi e la ricerca inline non si
 * potrebbe più annullare.
 *
 * <p>E torna a 0, infine, quando il rientro ridurrebbe il campo di testo sotto {@link #CAMPO_MINIMO_DP}: con il
 * menu bot aperto (collassato a {@code dp(40)} quando c'è testo, {@code BotCommandsMenuView:126}) o con una pillola
 * larga per il prezzo in stelle, su uno schermo stretto, la modalità si spegne da sola invece di consegnare un campo
 * da due parole.
 *
 * <h3>Il prezzo strutturale</h3>
 * Il campo perde {@code larghezzaPillola + 2 * aria} di larghezza (48dp col cerchio normale, di più con la pillola a
 * stelle): il testo va a capo prima e la barra cresce in altezza un rigo prima di upstream. La corsia della pillola
 * deve venire da qualche parte, e l'unico posto che non invade nessuna corsia di upstream è il margine sinistro del
 * campo.
 *
 * <p>E il rientro scatta, non slitta: cambia quando compare il testo, cioè nello stesso istante della dissolvenza
 * microfono→pillola, quindi al primo carattere il testo salta di ~48dp a destra invece di scivolare.
 * {@code beginDelayedTransition()} (:14612) è privato, quindi non possiamo riusare l'animazione di upstream;
 * animare il margine costerebbe una passata di layout per fotogramma sul sottoalbero della barra. In questa
 * versione si accetta lo scatto — ma lo scatto all'ANDATA, non quello al ritorno: l'azzeramento del rientro è
 * rinviato alla fine del ritorno della pillola (vedi {@link #bersaglioDiLayout}), perché azzerarlo subito spostava
 * di 48dp il testo che vola dentro la bolla a ogni messaggio inviato.
 *
 * <h3>Effetto immediato, e cosa costa a interruttore spento</h3>
 * Una barra si registra qui in una {@link WeakHashMap} la prima volta che ha qualcosa da spostare: barra di una chat
 * vera ({@code getParentFragment() != null}), pillola nello slot e testo nel campo. Così
 * {@link #setSendLeft(boolean)} riapplica subito a quelle vive: chi gira l'interruttore e torna nella chat la trova
 * già cambiata, senza riavviare l'app e senza riaprire la chat. La voce in mappa è il meccanismo di quell'effetto,
 * quindi la registrazione <b>non</b> può dipendere da {@link #sendLeft()}: se dipendesse, accendendo l'interruttore
 * non ci sarebbe nessuna barra su cui riapplicare e l'effetto arriverebbe al carattere successivo
 * ({@code checkSendButton} non gira al ritorno da un frammento: i suoi 26 richiamanti sono tutti cambi di stato
 * della barra, non il ciclo di vita).
 *
 * <p>Il prezzo a interruttore SPENTO è una voce di {@link WeakHashMap} — che muore con la {@code ChatActivity} —
 * e nient'altro: <b>nessun listener</b> (lo appende {@link #applica}, e solo se la modalità è accesa), <b>nessun
 * {@code requestLayout}</b>, nessun animatore, <b>nessuna scrittura su nessuna vista</b>. Si arriva fino in fondo ad
 * {@link #applica} e si esce senza toccare niente, perché {@code larghezzaSlot} nasce uguale alla larghezza vera
 * dello slot e {@code applicato} nasce a 0 invece che a {@code NaN}: il bersaglio è già quello corrente. Non era
 * così prima — il primo giro di ogni barra scriveva un {@code requestLayout} e faceva partire un
 * {@code ViewPropertyAnimator} da 150 ms verso un valore che era già quello, invisibile ma vero, a ogni chat in cui
 * si digitava, anche per chi non accenderà mai la modalità. E ci finivano anche la barra commento della lista chat
 * (:4941) e il foglio dei regali, che non sono {@code isStories}: li esclude ora la guardia sul frammento.
 *
 * <p>Il {@code OnLayoutChangeListener} su {@code textFieldContainer}, quando c'è, ricalcola l'offset se la barra
 * cambia larghezza (rotazione, pieghevole, multi-finestra), se lo slot di invio cambia stato senza passare da
 * {@code checkSendButton} (modifica di un messaggio, vocale registrato) e — soprattutto — è il punto in cui
 * {@link #offsetDelloSlot} vede che il rientro è davvero in piedi e muove la pillola nello stesso fotogramma.
 */
public final class MorsegramHand {

    /**
     * Aria fra la pillola spostata e le due colonne che le stanno accanto: la pillola atterra ad {@code aria} dal
     * bordo sinistro del campo, e il rientro del campo vale {@code larghezzaPillola + 2 * aria}, cioè l'aria la
     * pillola ce l'ha sia a sinistra (verso il pulsante emoji) sia a destra (verso il testo).
     */
    private static final float ARIA_DP = 6f;
    /**
     * La larghezza del microfono, cioè {@code ChatActivityEnterView.DEFAULT_HEIGHT} (44dp): il contenitore è
     * aggiunto allo slot con {@code createFrame(DEFAULT_HEIGHT, DEFAULT_HEIGHT, RIGHT | BOTTOM)} (:3256). Non si
     * misura come la pillola — che dichiara la propria larghezza con {@code SendButton.width()} e cresce col prezzo
     * in stelle — perché il microfono è sempre quel quadrato.
     */
    private static final float MICROFONO_DP = 44f;
    /**
     * Il tetto della corsa per annullare un vocale quando il microfono sta nella corsia di sinistra (upstream usa
     * {@code min(0.35 * larghezza, 140dp)}). Il conto sta in {@link #corsaPerAnnullare}: con 90dp lo spostamento
     * richiesto scende a circa 58dp contro i 78 che il dito ha davvero, mentre col tetto di upstream ne servirebbero
     * 85 e il gesto non scatterebbe mai.
     */
    private static final float CORSA_MANCINI_DP = 90f;
    /**
     * La larghezza minima che il campo deve avere <b>adesso</b>, a campo vuoto, perché la corsia si apra: quanto
     * basta a non far troncare il segnaposto. È molto più bassa di {@link #CAMPO_MINIMO_DP} perché a campo vuoto non
     * c'è testo da mandare a capo, e il margine destro grande che upstream tiene in quello stato è spazio che il
     * campo riavrà appena si scrive. Vedi la seconda soglia in {@link #rientro}.
     */
    private static final float SEGNAPOSTO_MINIMO_DP = 90f;
    /**
     * Sotto questa larghezza utile del campo di testo la modalità si spegne da sola. Il rientro va a prendere lo
     * spazio dal margine sinistro del campo: col menu bot aperto o con la pillola larga del prezzo in stelle, su uno
     * schermo stretto, il campo scenderebbe sotto le due parole e tanto vale lasciare la pillola a destra.
     */
    private static final float CAMPO_MINIMO_DP = 120f;
    /** Stessa durata e stessa curva della dissolvenza microfono → pillola di upstream (:8252-8253). */
    private static final long DURATA_ANDATA = 220L;
    /**
     * La dissolvenza pillola → microfono di upstream dura 150 ms, non 220 (:8669). Il ritorno dello slot usa la sua
     * e non quella dell'andata, così la finestra in cui i figli controtraslati si vedono ma non si toccano finisce
     * insieme alla dissolvenza di upstream invece di durare 70 ms in più.
     */
    private static final long DURATA_RITORNO = 150L;

    /** Nello slot non c'è niente che vada spostato a sinistra. */
    private static final int NIENTE = 0;
    /** Nello slot comanda la pillola di invio: la modalità si applica se {@link #sendLeft()}. */
    private static final int PILLOLA = 1;
    /** Nello slot comanda il microfono: la modalità si applica se anche {@link #voiceLeft()}. */
    private static final int MICROFONO = 2;

    /** Lettura calda (checkSendButton gira a ogni carattere digitato): cache volatile come in VoiceSettings. */
    private static volatile Boolean cache;
    /** Idem per il secondo gradino. Due cache separate: i due interruttori si leggono in punti diversi. */
    private static volatile Boolean cacheVoce;

    /**
     * Le barre vive. Chiave debole: la voce muore con la ChatActivity. Il valore NON deve riferirsi alla chiave,
     * altrimenti la WeakHashMap non raccoglierebbe mai nulla — per questo {@link Stato} tiene solo scalari e il
     * listener (che sì, cattura la barra) vive dentro la barra stessa.
     */
    private static final Map<ChatActivityEnterView, Stato> BARRE = new WeakHashMap<>();

    private MorsegramHand() {
    }

    public static boolean sendLeft() {
        Boolean v = cache;
        if (v == null) {
            v = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_HAND_SEND_LEFT, false);
            cache = v;
        }
        return v;
    }

    /** Scrive la preferenza e riapplica subito alle barre vive: niente riavvio, niente riapertura della chat. */
    public static void setSendLeft(boolean on) {
        cache = on;
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_HAND_SEND_LEFT, on).apply();
        riapplica();
    }

    /**
     * Secondo gradino: anche il microfono passa nella corsia di sinistra, e con lui il cerchio della registrazione.
     * È subordinato a {@link #sendLeft()} — da solo non fa niente — perché la corsia è quella dell'invio: senza
     * l'invio a sinistra non ci sarebbe nessuna corsia in cui mettere il microfono.
     */
    public static boolean voiceLeft() {
        if (!sendLeft()) {
            return false;
        }
        Boolean v = cacheVoce;
        if (v == null) {
            v = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_HAND_VOICE_LEFT, false);
            cacheVoce = v;
        }
        return v;
    }

    /** Come {@link #setSendLeft(boolean)}: scrive e riapplica subito alle barre vive. */
    public static void setVoiceLeft(boolean on) {
        cacheVoce = on;
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_HAND_VOICE_LEFT, on).apply();
        riapplica();
    }

    /** Invalida la cache dopo una scrittura che non passa dai setter (ripristino da file). */
    public static void ricarica() {
        cache = null;
        cacheVoce = null;
        riapplica();
    }

    /**
     * Quanto l'apparato della registrazione deve spostarsi a sinistra per restare sotto il microfono, 0 quando il
     * microfono è dove lo mette upstream. La chiamano tre righe upstream.
     *
     * <p>Serve perché quell'apparato non vive nella barra ma in {@code sizeNotifierLayout} (MATCH_PARENT), in due
     * viste sorelle che non sanno niente dello slot.
     * <ul>
     * <li>{@code RecordCircle} (:4517) ancora <b>tutto</b> il suo disegno orizzontale a UN solo valore:
     *     {@code onDraw} calcola {@code cx = getMeasuredWidth() - dp2(26)} (:2183) e da lì derivano
     *     {@code drawingCx}, il cerchio, le due onde ({@code BlobDrawable}), l'icona, il rettangolo di invio e la
     *     transizione verso la seekbar, tutti scritti come {@code cx + slideDelta}. Una riga sola per il cerchio.</li>
     * <li>{@code ControlsView} (:4529) è un <b>fratello</b> di {@code RecordCircle} e tiene una <b>copia</b> della
     *     stessa formula (:1404), più la {@code translate} della freccia del fumetto (:1474). Da lì derivano la
     *     serratura, il tasto pausa, il «una volta» e il fumetto «trascina in alto per bloccare», e anche i loro
     *     bersagli di tocco ({@code rectF}, {@code pauseRect}). Queste due righe erano state mancate: col solo
     *     {@code cx} del cerchio il lucchetto restava al bordo destro, cioè <b>proprio il ripiego</b> che il testo
     *     dell'impostazione indica a chi non riesce ad annullare trascinando. Le ha trovate la revisione del
     *     04/11/2026, non lo schermo.</li>
     * </ul>
     *
     * <p>Lo scorrimento per annullare non va invertito, e non è una dimenticanza: {@code slideDelta} vale
     * {@code -distance * (1 - slideToCancelProgress)} (:1959 e :2169) con {@code distance == distCanMove}, e
     * {@code slideToCancelProgress} vale {@code 1 + dist / distCanMove}, quindi {@code slideDelta == dist}. Il
     * cerchio segue esattamente il dito: finché il dito è sullo schermo il cerchio è sullo schermo, qualunque sia il
     * punto di partenza. Il prezzo è un altro, dichiarato nel javadoc della classe: la corsa per annullare si
     * accorcia.
     *
     * <p>Si torna {@code stato.nostro} solo se l'occupante corrente è il microfono: con la pillola nello slot il
     * cerchio della registrazione non si vede, e spostarlo sarebbe spostare qualcosa che non c'è.
     */
    public static float offsetVocale(ChatActivityEnterView barra) {
        try {
            if (barra == null) {
                return 0f;
            }
            final Stato stato = BARRE.get(barra);
            return stato == null || stato.esclusa || stato.occupante != MICROFONO ? 0f : stato.nostro;
        } catch (Throwable ignore) {
            return 0f;
        }
    }

    /**
     * Aggancio 1, in coda a {@code ChatActivityEnterView.checkSendButton}.
     *
     * <p>{@code shownSendButton} da solo non vuol dire «c'è testo»: upstream lo accende anche per
     * {@code forceShowSendButton} (scegliendo i messaggi da inoltrare, {@code ChatActivity} chiama
     * {@code setForceShowSendButton(true, false)} col campo vuoto, :15230), per la bozza ricca, per lo slow mode con
     * un invio in caricamento e per lo streaming di un bot (:8119). Spostare la barra lì sarebbe uno spostamento a
     * campo vuoto, cioè una barra che si muove senza che l'utente abbia scritto niente: quindi la condizione è la
     * congiunzione di {@code shownSendButton} (la pillola occupa lo slot) e del testo VERO del campo, misurato come lo
     * misura upstream in cima a {@code checkSendButton} (:7927). Serve la congiunzione e non il solo testo: con lo
     * slow mode acceso il campo ha testo ma nello slot c'è il contatore, non la pillola.
     *
     * @param barra              la barra di scrittura che ha appena deciso il suo stato
     * @param pillolaDiInvio     {@code shownSendButton}: nello slot c'è la pillola di invio
     * @param animato            {@code animated} dello stesso metodo
     */
    public static void sendSlot(ChatActivityEnterView barra, boolean pillolaDiInvio, boolean animato) {
        if (barra == null || barra.isStories) {
            return;
        }
        try {
            Stato stato = BARRE.get(barra);
            if (stato == null && barra.getParentFragment() == null) {
                // Non è la barra di una chat vera: si esce PRIMA di toccare qualunque cosa e non si entra in mappa.
                // Così la barra commento della lista chat (DialogsActivity:4941), il foglio dei regali e le notifiche
                // popup non si accorgono mai che esistiamo — prima entravano anche loro, perché non sono
                // {@code isStories}, e si prendevano un listener permanente che poi {@link #rientro} scartava.
                // {@code parentFragment} è assegnato nel costruttore (:2637), prima che le viste esistano e prima
                // del primo {@code checkSendButton} (:3570): a differenza di {@code isStories} qui è già vero.
                //
                // Qui NON si guarda {@link #sendLeft()}: una barra di chat deve entrare in mappa anche a
                // interruttore spento, altrimenti {@link #setSendLeft(boolean)} non avrebbe niente su cui
                // riapplicare e l'effetto immediato non esisterebbe. A interruttore spento la voce in mappa è
                // l'UNICA cosa che costa: nessun listener (lo appende {@link #applica}, solo a modalità accesa),
                // nessun {@code requestLayout}, nessun animatore, nessuna scrittura su nessuna vista.
                return;
            }
            final boolean pillolaPerTesto = pillolaDiInvio && testoNelCampo(barra);
            if (stato == null) {
                if (!pillolaPerTesto && !microfonoNelloSlot(barra)) {
                    // niente da spostare: non registrare la barra e non appenderle addosso un listener permanente.
                    // È anche la prima difesa sulle storie, dove isStories è ancora falso dentro il costruttore.
                    //
                    // «Qualcosa da spostare» comprende ora anche il microfono nello slot, e NON guarda
                    // {@link #voiceLeft()}: come per {@link #sendLeft()}, se la registrazione in mappa dipendesse
                    // dall'interruttore, accenderlo non troverebbe nessuna barra su cui riapplicare e l'effetto
                    // immediato non esisterebbe. A campo vuoto il microfono c'è quasi sempre, quindi quasi ogni
                    // barra di chat entra in mappa al primo {@code checkSendButton}: il prezzo resta una voce di
                    // WeakHashMap, perché {@link #applica} attraversa tutto e esce senza scrivere niente quando
                    // nessuno dei due interruttori è acceso.
                    return;
                }
                stato = new Stato();
                BARRE.put(barra, stato);
            }
            stato.pillolaDiInvio = pillolaPerTesto;
            applica(barra, stato, animato);
        } catch (Throwable ignore) {
            // una barra fuori posto non deve impedire di scrivere un messaggio
        }
    }

    /**
     * Aggancio 2, in {@code ChatActivityEnterView.onMeasure}: subito dopo la catena if/else che riscrive i
     * leftMargin nei tre rami (menu bot, selettore mittente, normale) e <b>prima</b> di {@code super.onMeasure}.
     *
     * <p>Fa due cose, entrambe scritture di layout che vanno fatte qui e non altrove:
     * <ul>
     * <li><b>somma</b> il rientro al leftMargin del campo e dell'anteprima della bozza ricca. Si somma, non si
     *     sostituisce: il valore di partenza è quello che upstream ha appena scritto, qualunque dei tre rami abbia
     *     preso, quindi il menu bot e il selettore mittente continuano a funzionare da soli. E poiché upstream
     *     riscrive quel margine da zero a ogni misurazione, leggerlo prima di sommarci sopra restituisce sempre il
     *     valore puro di upstream: niente accumulo fra una misurazione e l'altra.</li>
     * <li>scrive la <b>larghezza</b> dei LayoutParams dello slot di invio, che qui vale per questa stessa
     *     misurazione. Nessuna riga in tutto l'albero scrive quei LayoutParams (verificato), e il valore originale
     *     viene letto dalla vista e rimesso quando l'offset torna a 0, invece di essere ricopiato da upstream.</li>
     * </ul>
     *
     * <p>Una barra che non abbiamo registrato esce subito: non le tocchiamo niente. Una barra esclusa (le storie) ha
     * rientro 0 e larghezza originale, quindi attraversa questo metodo senza che cambi nulla — è proprio così che
     * {@link #escludi} rimette a posto i margini che aveva fatto scrivere.
     *
     * @param barra          la barra che si sta misurando
     * @param campoTesto     {@code messageEditText}: passato per nome, così la riga upstream FALLISCE IN
     *                       COMPILAZIONE se upstream lo rinomina o lo rimuove invece di spegnersi in silenzio
     * @param anteprimaBozza {@code richDraftPreview}, idem; può essere {@code null}, upstream lo crea a richiesta
     */
    public static void rientraIlCampo(ChatActivityEnterView barra, View campoTesto, View anteprimaBozza) {
        if (barra == null) {
            return;
        }
        try {
            final Stato stato = BARRE.get(barra);
            if (stato == null) {
                return;
            }
            final ViewGroup.MarginLayoutParams lpCampo = margini(campoTesto);
            if (lpCampo != null) {
                // Il margine che upstream ha appena scritto, letto PRIMA di sommarci sopra. Si registra perché è la
                // verità da cui {@link #offsetDelloSlot} ricava il bordo sinistro ATTESO del campo, e quel valore non
                // si può ricavare per sottrazione dal margine finale: se un riallineamento mettesse questa riga prima
                // di una scrittura di upstream, la sottrazione tornerebbe un valore coerente con una somma che in
                // realtà è stata cancellata, e la pillola si sposterebbe sopra il testo.
                stato.margineBase = lpCampo.leftMargin;
                stato.margineBaseVisto = true;
            }
            final int rientro = stato.rientro;
            stato.rientroApplicato = lpCampo == null ? 0 : rientro;
            if (rientro != 0) {
                if (lpCampo != null) {
                    lpCampo.leftMargin += rientro;
                }
                sommaAlMargineSinistro(anteprimaBozza, rientro);
            }
            final View slot = barra.sendButtonContainer;
            if (slot != null && stato.larghezzaSlot > 0) {
                final ViewGroup.LayoutParams lp = slot.getLayoutParams();
                if (lp != null && lp.width != stato.larghezzaSlot) {
                    lp.width = stato.larghezzaSlot;
                }
            }
        } catch (Throwable ignore) {
            // una barra fuori posto non deve impedire di scrivere un messaggio
        }
    }

    /**
     * Aggancio 3, due volte in {@code MessageSendPreview}: quanto della posizione sullo schermo dell'ancora di invio
     * ci abbiamo messo NOI, cioè il valore da sottrarre per riavere la x che upstream calcolerebbe con la pillola a
     * destra. Con la pillola a destra, o fuori da una chat, vale 0 e il caso normale non cambia di un pixel.
     *
     * <p>Si risale dall'ancora alla {@code ChatActivityEnterView} che la contiene, invece di contare i livelli:
     * {@code MessageSendPreview} è aperta anche da PhotoViewer, ShareAlert, StickersAlert, ChatAttachAlert,
     * PeerStoriesView, ChatActivity e DialogsActivity, e con un'ancora che non vive in una barra di scrittura nostra
     * la risalita non trova niente e torna 0.
     *
     * <p>Si torna {@code stato.nostro}, il nostro contributo fotogramma per fotogramma, e non
     * {@code slot.getTranslationX()}: su quella proprietà scrive anche {@code setHorizontalPadding} per le storie, e
     * sottrarre il valore di un altro scrittore sarebbe sbagliato due volte.
     */
    public static float traslazioneSlot(View ancoraInvio) {
        try {
            ViewParent padre = ancoraInvio == null ? null : ancoraInvio.getParent();
            for (int i = 0; i < 8 && padre != null; i++) {
                if (padre instanceof ChatActivityEnterView) {
                    final Stato stato = BARRE.get((ChatActivityEnterView) padre);
                    return stato == null || stato.esclusa ? 0f : stato.nostro;
                }
                padre = padre.getParent();
            }
        } catch (Throwable ignore) {
        }
        return 0f;
    }

    /**
     * C'è davvero testo da inviare nel campo. Stesso valore che upstream calcola in cima a {@code checkSendButton}
     * ({@code AndroidUtilities.getTrimmedString(messageEditText.getTextToUse()).length() > 0}, :7927): si legge lo
     * stato vero del campo e non il flag, e il campo di testo si raggiunge con {@code getEditField()} (pubblico,
     * :10599) senza spendere righe upstream.
     */
    private static boolean testoNelCampo(ChatActivityEnterView barra) {
        final EditTextCaption campoTesto = barra.getEditField();
        if (campoTesto == null) {
            return false;
        }
        final CharSequence testo = AndroidUtilities.getTrimmedString(campoTesto.getTextToUse());
        return testo != null && testo.length() > 0;
    }

    private static void riapplica() {
        try {
            for (Map.Entry<ChatActivityEnterView, Stato> e : BARRE.entrySet()) {
                final ChatActivityEnterView barra = e.getKey();
                if (barra != null) {
                    applica(barra, e.getValue(), true);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * Ricalcola quando la barra viene disposta di nuovo: larghezza cambiata (rotazione, pieghevole, multi-finestra)
     * oppure slot di invio nascosto senza passare da {@code checkSendButton}, che esce subito mentre si modifica un
     * messaggio o si registra. La translationX non provoca un nuovo layout, quindi il solo ciclo possibile passa dal
     * {@code requestLayout} del rientro, che {@link #applica} chiede solo quando il bersaglio cambia davvero.
     */
    private static void ascolta(final ChatActivityEnterView barra, final Stato stato) {
        if (stato.ascolta) {
            return;
        }
        final View barraTesto = barra.textFieldContainer;
        if (barraTesto == null) {
            return;
        }
        stato.ascolta = true;
        barraTesto.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (barra.isStories) {
                // il campo può essere diventato vero dopo che siamo già entrati nella mappa
                escludi(barra, stato);
                return;
            }
            applica(barra, stato, false);
        });
    }

    private static void applica(ChatActivityEnterView barra, Stato stato, boolean animato) {
        if (stato.esclusa) {
            return;
        }
        if (barra.isStories) {
            escludi(barra, stato);
            return;
        }
        final View slot = barra.sendButtonContainer;
        if (slot == null) {
            return;
        }
        if (sendLeft()) {
            // Il listener si appende qui, e solo a modalità accesa: è lui che vede il rientro diventare vero e
            // muove la pillola nello stesso fotogramma ({@link #offsetDelloSlot}), e che ricalcola a rotazione,
            // pieghevole e multi-finestra. Qui e non in {@link #sendSlot} perché questo metodo è anche la via di
            // {@link #riapplica()}: chi accende l'interruttore mentre la chat è viva si prende il listener subito,
            // senza dover digitare un altro carattere. A interruttore spento non si appende niente.
            ascolta(barra, stato);
        }
        if (stato.larghezzaSlotBase == 0) {
            // la larghezza originale si legge dalla vista una volta sola, prima di scriverci sopra; un valore non
            // utilizzabile (MATCH_PARENT, WRAP_CONTENT, zero) diventa -1 e lo slot non si restringe mai
            final ViewGroup.LayoutParams lp = slot.getLayoutParams();
            stato.larghezzaSlotBase = lp == null || lp.width <= 0 ? -1 : lp.width;
            // e diventa subito il bersaglio corrente, così il primo giro a rientro zero non chiede nessuna
            // misurazione: una barra che non si sposta non deve pagare nemmeno un requestLayout
            stato.larghezzaSlot = stato.larghezzaSlotBase;
        }
        final float larghezzaCorsia = larghezzaCorsia(barra, stato, slot);
        final int rientro = rientro(barra, stato, slot, larghezzaCorsia);
        final int larghezzaSlot = rientro == 0 || stato.larghezzaSlotBase <= 0
                ? stato.larghezzaSlotBase
                : (int) larghezzaCorsia + AndroidUtilities.dp(8);
        bersaglioDiLayout(barra, stato, rientro, larghezzaSlot);
        final float offset = offsetDelloSlot(barra, stato, slot, rientro);
        if (Math.abs(stato.applicato - offset) < 0.5f) {
            // niente da animare, ma lo slot può aver guadagnato un figlio da quando l'offset è stato deciso
            // (expandStickersButton nasce alla prima apertura degli sticker, :3992): rimettilo a posto subito
            fermaGliAltriFigli(barra, slot, stato);
            return;
        }
        stato.applicato = offset;
        final long durata = offset == 0f ? DURATA_RITORNO : DURATA_ANDATA;
        slot.animate().cancel();
        if (animato) {
            final float partenza = stato.nostro;
            slot.animate().translationX(offset).setDuration(durata).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .setUpdateListener(a -> {
                        // la frazione è quella vera dell'animatore, interpolatore compreso: l'ultimo fotogramma
                        // vale 1 e ci lascia esattamente sul bersaglio, un annullamento a metà ci lascia dove è
                        // rimasta la vista. Niente withEndAction: quello scatta anche sull'annullamento e
                        // scriverebbe il bersaglio vecchio mentre la vista è a metà strada
                        stato.nostro = partenza + (offset - partenza) * a.getAnimatedFraction();
                        fermaGliAltriFigli(barra, slot, stato);
                    })
                    .start();
        } else {
            slot.setTranslationX(offset);
            stato.nostro = offset;
            fermaGliAltriFigli(barra, slot, stato);
        }
    }


    /**
     * La corsa che il dito deve fare per annullare un vocale trascinando, accorciata quando il microfono sta nella
     * corsia di sinistra. Torna il valore di upstream quando il microfono è dove lo mette lui.
     *
     * <h3>Perché serve: l'annullamento non scatterebbe mai</h3>
     * Upstream annulla quando {@code alpha = 1 + dist / distCanMove} scende sotto {@code 0.45}, cioè quando
     * {@code dist < -0.55 * distCanMove}. Ma {@code dist} non è lo spostamento del dito: {@code startedDraggingX}
     * è preso come {@code motionEvent.getX()} puro (:3183) mentre {@code dist} si misura su
     * {@code getX() + audioVideoButtonContainer.getX()} (:3190), quindi porta un <b>bias costante</b> pari alla x
     * del microfono dentro lo slot. Lo spostamento vero che serve è
     * {@code 0.55 * distCanMove + bias}: col microfono a destra (slot da 100dp, bias 56dp) sono circa 133dp, e dal
     * bordo destro dello schermo ci sono tutti.
     *
     * <p>Col microfono nella corsia di sinistra il dito parte da circa 78dp dal bordo sinistro e di corsa ne ha
     * altrettanta, mentre ne servirebbero 85 su uno schermo largo (dove {@code distCanMove} è al suo tetto di
     * 140dp). Non è un difetto estetico: il gesto non scatta, l'utente arriva al bordo dello schermo convinto di
     * avere annullato, <b>alza il dito e il vocale parte</b>. Lo slot ristretto aiuta — il bias scende da 56 a 8dp
     * perché il microfono è RIGHT in uno slot da 52dp — ma non basta.
     *
     * <p>Con {@link #CORSA_MANCINI_DP} la corsa necessaria diventa {@code 0.55 * 90 + 8 ≈ 58dp} contro i 78
     * disponibili: venti di margine. Si accorcia e non si inverte: {@code slideDelta} vale
     * {@code -distance * (1 - slideToCancelProgress)} con {@code distance} ricavata dalla stessa formula, quindi
     * finché i tre punti che la calcolano passano tutti da qui resta {@code slideDelta == dist} e il cerchio segue
     * esattamente il dito, senza uscire dallo schermo. Passarla per argomento invece di riscrivere la formula
     * significa che se upstream cambia il tetto o la frazione, il nuovo valore arriva qui da solo.
     */
    public static float corsaPerAnnullare(ChatActivityEnterView barra, float corsaUpstream) {
        try {
            if (barra == null) {
                return corsaUpstream;
            }
            final Stato stato = BARRE.get(barra);
            if (stato == null || stato.esclusa || stato.occupante != MICROFONO
                    || Math.abs(stato.nostro) < 0.5f) {
                return corsaUpstream;
            }
            return Math.min(corsaUpstream, AndroidUtilities.dp(CORSA_MANCINI_DP));
        } catch (Throwable ignore) {
            return corsaUpstream;
        }
    }

    /**
     * Chi comanda nello slot adesso, e quindi che cosa andrebbe spostato a sinistra: la pillola di invio, il
     * microfono, o niente. Scrive {@link Stato#occupante} e lo restituisce.
     *
     * <p>La pillola vince sul microfono perché sono occupanti alternativi dello stesso slot e upstream, mentre
     * dissolve l'uno nell'altro, li tiene entrambi VISIBLE: il {@code GONE} del microfono arriva solo alla fine
     * della dissolvenza (:8265). Guardando prima la pillola, l'istante in cui compare il testo è anche l'istante in
     * cui la corsia passa alla pillola, senza un fotogramma ambiguo in cui si contendono il posto.
     *
     * <p>Il microfono conta solo a {@link #voiceLeft()} acceso. La pillola, come prima, conta solo se c'è davvero
     * del testo ({@link Stato#pillolaDiInvio}, deciso in {@link #sendSlot}): la pillola a campo vuoto —
     * inoltro di messaggi, bozza ricca, streaming di un bot — non sposta niente.
     */
    private static int occupante(ChatActivityEnterView barra, Stato stato, View slot) {
        if (stato.pillolaDiInvio && larghezzaPillola(slot, slot.getHeight()) > 0f) {
            return stato.occupante = PILLOLA;
        }
        if (voiceLeft() && microfonoNelloSlot(barra)) {
            return stato.occupante = MICROFONO;
        }
        return stato.occupante = NIENTE;
    }

    /**
     * Quanto è larga la corsia da ritagliare nel margine sinistro del campo, o {@code -1} se non c'è niente da
     * spostare.
     *
     * <p>A {@link #voiceLeft()} acceso la corsia è la stessa per i due occupanti — il massimo fra la pillola e il
     * microfono — e questo non è un dettaglio di arrotondamento: tiene il rientro FERMO mentre la pillola e il
     * microfono si scambiano il posto. Il campo di testo non si muove di un pixel quando si scrive il primo
     * carattere o si cancella l'ultimo, e sparisce lo scatto di ~48dp che il javadoc di questa classe dichiara come
     * prezzo strutturale del primo gradino: col microfono già nella corsia, la corsia c'è prima che ci sia il testo.
     *
     * <p>A interruttore del microfono spento resta esattamente il comportamento di prima: la corsia è larga quanto
     * la pillola, e a campo vuoto non c'è corsia.
     */
    private static float larghezzaCorsia(ChatActivityEnterView barra, Stato stato, View slot) {
        switch (occupante(barra, stato, slot)) {
            case PILLOLA: {
                final float pillola = larghezzaPillola(slot, slot.getHeight());
                return voiceLeft() ? Math.max(pillola, AndroidUtilities.dp(MICROFONO_DP)) : pillola;
            }
            case MICROFONO:
                return AndroidUtilities.dp(MICROFONO_DP);
            default:
                return -1f;
        }
    }

    /**
     * Nello slot c'è il microfono visibile. Si raggiunge con {@code getAudioVideoButtonContainer()} (pubblico,
     * :10359), quindi costa zero righe upstream e una rinomina non compila.
     *
     * <p>Si guarda la visibilità e non l'alpha: durante le dissolvenze upstream tiene il microfono VISIBLE e ne
     * anima l'alpha, e {@link #occupante} risolve l'ambiguità dando la precedenza alla pillola.
     */
    private static boolean microfonoNelloSlot(ChatActivityEnterView barra) {
        final View microfono = barra.getAudioVideoButtonContainer();
        return microfono != null && microfono.getVisibility() == View.VISIBLE;
    }

    /**
     * Il bersaglio di layout: il rientro del campo e la larghezza dei LayoutParams dello slot, che
     * {@link #rientraIlCampo} applicherà alla prossima misurazione. La misurazione si chiede soltanto quando il
     * bersaglio cambia davvero, altrimenti sarebbe un ciclo con l'{@code OnLayoutChangeListener}.
     *
     * <h3>L'azzeramento si rinvia di {@link #DURATA_RITORNO}</h3>
     * Il motivo è l'invio di un messaggio di testo, cioè il percorso più battuto dell'app. Upstream non svuota il
     * campo al tocco: lo svuota in un {@code Runnable} a +200 ms e nello STESSO runnable chiama
     * {@code delegate.onMessageSend} ({@code ChatActivityEnterView}:7405-7415). Quello {@code setText("")} ci arriva
     * come {@code checkSendButton}, cioè come «non c'è più testo», e fa {@code requestLayout()} sul campo: se
     * azzerassimo il rientro lì, nello stesso fotogramma il campo tornerebbe al margine puro di upstream. Un
     * fotogramma dopo, {@code onMessageSend} ha inserito la bolla e
     * {@code ChatListItemAnimator.runPendingAnimations} costruisce {@code TextMessageEnterTransition}, che a :227
     * campiona la posizione del campo con {@code ViewPositionWatcher.computeCoordinatesInParent} per far volare il
     * testo dentro la bolla: il testo sarebbe partito 48dp a sinistra di dove l'utente lo ha visto per 200 ms, e con
     * lui lo sfondo della bolla che cresce da quella x. Su uno schermo da 360dp è il 13% della larghezza, a ogni
     * messaggio inviato.
     *
     * <p>Tenendo il rientro in piedi finché la pillola non ha finito di tornare a destra, la transizione campiona la
     * posizione giusta; e il campo, in quei 150 ms, è vuoto, quindi non si vede niente tornare indietro. Chiude
     * anche lo scatto al contrario (cancellando l'ultimo carattere la pillola restava disegnata sopra il testo
     * ormai riallineato a sinistra per tutta la durata del ritorno). Se il testo ricompare prima della scadenza —
     * si invia e si ricomincia subito a scrivere — il bersaglio torna quello di prima, il rinvio si annulla e il
     * rientro non si muove affatto.
     */
    private static void bersaglioDiLayout(ChatActivityEnterView barra, Stato stato, int rientro, int larghezzaSlot) {
        if (stato.rientro == rientro && stato.larghezzaSlot == larghezzaSlot) {
            annullaRinvio(stato);
            return;
        }
        if (rientro == 0 && stato.rientro != 0 && Math.abs(stato.nostro) >= 0.5f) {
            if (stato.rinvio == null) {
                // il rinvio cattura la barra DEBOLMENTE: un riferimento forte dal valore alla chiave terrebbe in
                // vita per sempre la voce della WeakHashMap (vedi la nota su BARRE)
                final WeakReference<ChatActivityEnterView> debole = new WeakReference<>(barra);
                final Runnable rinvio = () -> {
                    stato.rinvio = null;
                    final ChatActivityEnterView viva = debole.get();
                    if (viva == null || stato.esclusa) {
                        return;
                    }
                    stato.rientro = 0;
                    stato.larghezzaSlot = stato.larghezzaSlotBase;
                    chiediMisurazione(viva);
                };
                stato.rinvio = rinvio;
                AndroidUtilities.runOnUIThread(rinvio, DURATA_RITORNO);
            }
            return;
        }
        annullaRinvio(stato);
        stato.rientro = rientro;
        stato.larghezzaSlot = larghezzaSlot;
        chiediMisurazione(barra);
    }

    private static void annullaRinvio(Stato stato) {
        if (stato.rinvio != null) {
            AndroidUtilities.cancelRunOnUIThread(stato.rinvio);
            stato.rinvio = null;
        }
    }

    /**
     * Chiede la misurazione in cui {@link #rientraIlCampo} applicherà il bersaglio, e la chiede al <b>campo</b> e
     * non alla barra.
     *
     * <p>{@code requestLayout()} accende {@code PFLAG_FORCE_LAYOUT} sulla vista e lo propaga agli ANTENATI, non ai
     * figli. Chiedendola alla barra, {@code barra.onMeasure} gira — quindi {@link #rientraIlCampo} scrive i nuovi
     * valori negli oggetti LayoutParams — ma poi {@code super.onMeasure} misura {@code textFieldContainer} con gli
     * stessi {@code MeasureSpec} di prima e senza FORCE_LAYOUT: {@code View.measure} esce prima di chiamare
     * {@code onMeasure}, il sottoalbero non si rimisura, {@code setFrame} torna {@code changed == false},
     * {@code PFLAG_LAYOUT_REQUIRED} non è acceso e {@code onLayout} non viene nemmeno chiamato. Il margine appena
     * scritto resta dentro i LayoutParams senza vedersi, e l'{@code OnLayoutChangeListener} non scatta.
     *
     * <p>Sul percorso del testo la cosa era mascherata, perché un cambio di testo fa già {@code requestLayout()} sul
     * campo e forza il sottoalbero da solo. Ma l'«effetto immediato» dell'interruttore non ha nessun altro innesco:
     * si accendeva la modalità con una chat aperta e del testo nel campo, e la pillola scivolava a sinistra mentre
     * il campo NON rientrava, cioè la pillola finiva disegnata sopra le prime parole fino al carattere successivo.
     *
     * <p>Chiedendola al campo, FORCE_LAYOUT risale campo → {@code messageEditTextContainer} →
     * {@code textFieldContainer} → barra (il campo è figlio della colonna, :5825, e la colonna di
     * {@code textFieldContainer}, :2702), quindi tutto quello che deve rimisurarsi si rimisura: la colonna perché il
     * margine del campo è cambiato, {@code textFieldContainer} perché deve ridisporre i figli, e lo slot perché
     * {@code measureChildWithMargins} gli ricava lo spec dai suoi LayoutParams, che abbiamo appena cambiato.
     */
    private static void chiediMisurazione(ChatActivityEnterView barra) {
        final View campoTesto = barra.getEditField();
        if (campoTesto != null) {
            campoTesto.requestLayout();
        } else {
            barra.requestLayout();
        }
    }

    /**
     * L'offset da dare alla translationX dello slot, 0 se la pillola non deve muoversi adesso.
     *
     * <p>Non si ricava dall'aritmetica sui margini ma dal <b>bordo sinistro vero</b> del campo dopo l'ultima
     * disposizione, e la pillola si muove soltanto se quel bordo è ESATTAMENTE dove il rientro lo vuole. È la
     * garanzia che tiene insieme le due metà della modalità, e chiude tre cose in una.
     *
     * <ul>
     * <li><b>L'ordine della riga upstream.</b> La somma del rientro vive dentro
     *     {@code ChatActivityEnterView.onMeasure} e il suo ordine rispetto alle scritture di upstream non è ancorato
     *     a nessun simbolo: è posizione nel file, cioè la cosa che un riallineamento sposta per contesto. Se la
     *     nostra riga finisse PRIMA di una scrittura di upstream, il margine verrebbe sovrascritto e la sola
     *     aritmetica non se ne accorgerebbe: la pillola si sposterebbe comunque e finirebbe disegnata sopra il testo,
     *     cioè silenziosamente SBAGLIATA invece di silenziosamente spenta. Confrontando col bordo vero, in quel caso
     *     la pillola non si muove affatto e la modalità semplicemente non fa niente.</li>
     * <li><b>Lo scatto di un fotogramma all'andata.</b> Il rientro è una scrittura di layout e la traslazione no:
     *     arrivavano in due momenti diversi e per un fotogramma la pillola copriva le prime parole (all'apertura di
     *     una chat con una bozza, e al primo carattere). Adesso la traslazione può arrivare solo DOPO che il margine
     *     è diventato vero, e ci arriva dalla callback di disposizione che gira DENTRO la stessa passata: le due
     *     cose si vedono nello stesso fotogramma.</li>
     * <li><b>La divergenza fra rientro e offset.</b> Non può più esistere un offset che non corrisponda al margine
     *     davvero in piedi, perché l'offset è calcolato da quel margine e non da un valore parallelo.</li>
     * </ul>
     *
     * <p>Conseguenza voluta: l'andata non si anima quasi mai, perché la traslazione arriva dalla disposizione, dove
     * {@code animato} è falso. Resta animata (e sincronizzata con la dissolvenza microfono→pillola di upstream, 220
     * ms) solo quando il rientro è GIÀ in piedi, per esempio se si ricomincia a scrivere subito dopo un invio,
     * mentre il rinvio di {@link #bersaglioDiLayout} tiene ancora il campo rientrato.
     */
    private static float offsetDelloSlot(ChatActivityEnterView barra, Stato stato, View slot, int rientro) {
        if (rientro <= 0 || stato.rientroApplicato <= 0) {
            return 0f;
        }
        final EditTextCaption campoTesto = barra.getEditField();
        final View colonna = barra.messageEditTextContainer;
        if (campoTesto == null || colonna == null) {
            return 0f;
        }
        final int margineBase = margineBaseDelCampo(barra, stato);
        if (margineBase < 0) {
            return 0f;
        }
        // il bordo sinistro del campo in coordinate di textFieldContainer. La colonna ha leftMargin 0 (:2702) e
        // textFieldContainer paddingLeft 0 (:2658), quindi getLeft() della colonna vale 0 nella barra di una chat;
        // sommarlo comunque tiene il conto giusto anche se cambiassero (in DialogsActivity, per esempio, upstream
        // mette un padding destro a textFieldContainer, :4941)
        final int bordoSinistro = colonna.getLeft() + campoTesto.getLeft();
        if (bordoSinistro != margineBase + stato.rientroApplicato) {
            // il rientro non è (ancora, oppure non è più) in piedi: la pillola resta dove la mette upstream
            return 0f;
        }
        // Bordo destro della pillola, senza traslazione: {@code slot.getRight() - dp(4)}. {@code getRight()} è la
        // posizione VERA del bordo destro dello slot nel padre, non la larghezza della barra: così il conto non
        // dipende dal fatto che lo slot non abbia rightMargin e che {@code textFieldContainer} non abbia
        // paddingRight (nella barra di una chat è vero, in quella della lista chat NO — lì upstream mette
        // {@code setPadding(0, dp(1), dp(20), 0)}, :4941 — e quella barra la esclude la guardia sul frammento, ma
        // il valore giusto è giusto da solo). {@code getRight()} non contiene la translationX, quindi è la
        // posizione a riposo, ed è indipendente dalla larghezza che diamo ai LayoutParams dello slot, perché lo
        // slot è RIGHT nel padre. Il {@code dp(4)} è l'inset con cui {@code SendButton} disegna la pillola dentro
        // il proprio box ({@code getMeasuredWidth() - dp(4) - circlePadX}, :15013, con circlePadX mai impostato
        // sulla pillola della barra).
        if (slot.getRight() <= 0) {
            // non ancora disposto: ci ripensa l OnLayoutChangeListener
            return 0f;
        }
        // Il dp(4) e' l'inset con cui SendButton disegna la pillola dentro il proprio box. Il microfono invece
        // riempie il suo: e' aggiunto con createFrame(44, 44, RIGHT | BOTTOM) senza margini (:3256), quindi il suo
        // bordo destro coincide con quello dello slot e l'inset non c'e'. Senza questa distinzione il microfono
        // atterrerebbe 4dp piu' a destra della pillola, e i due si scambierebbero il posto con un salto.
        final int inset = stato.occupante == MICROFONO ? 0 : AndroidUtilities.dp(4);
        final int bordoDestroOccupante = slot.getRight() - inset;
        final float proposta = bordoSinistro - AndroidUtilities.dp(ARIA_DP) - bordoDestroOccupante;
        // una proposta non negativa vorrebbe dire una barra più stretta del margine sinistro del campo:
        // CAMPO_MINIMO_DP lo rende impossibile, ma se accadesse la pillola è già dove deve stare e non si muove
        return proposta < 0f ? proposta : 0f;
    }

    /**
     * Questa barra non è nostra (è la barra delle storie, dove {@code setHorizontalPadding} scrive la translationX
     * dello slot): toglie il nostro contributo, se mai ne avevamo messo uno, e non la tocca più. Si sottrae invece di
     * azzerare, così resta in piedi quello che ci ha scritto l'altro scrittore. Non si rimuove la voce dalla mappa
     * perché {@link #riapplica()} può essere in mezzo a un'iterazione; la {@link WeakHashMap} la raccoglie da sola —
     * e perché la voce serve ancora a {@link #rientraIlCampo}, che deve attraversare almeno un'altra misurazione con
     * rientro 0 e larghezza originale per rimettere a posto quello che aveva scritto.
     */
    private static void escludi(ChatActivityEnterView barra, Stato stato) {
        if (stato.esclusa) {
            return;
        }
        stato.esclusa = true;
        stato.occupante = NIENTE;
        annullaRinvio(stato);
        try {
            final View slot = barra.sendButtonContainer;
            if (slot != null && (stato.nostro != 0f || stato.figliSpostati)) {
                slot.animate().cancel();
                slot.setTranslationX(slot.getTranslationX() - stato.nostro);
                stato.nostro = 0f;
                fermaGliAltriFigli(barra, slot, stato);
            }
            if (stato.rientro != 0 || stato.larghezzaSlot != stato.larghezzaSlotBase) {
                stato.rientro = 0;
                stato.larghezzaSlot = stato.larghezzaSlotBase;
                chiediMisurazione(barra);
            }
        } catch (Throwable ignore) {
        }
        stato.applicato = 0f;
    }

    /**
     * Il leftMargin che upstream ha scritto nel campo di testo, senza il nostro rientro.
     *
     * <p>Non si tiene un valore nostro: si legge il margine vero e si sottrae il rientro che
     * {@link #rientraIlCampo} ha davvero applicato l'ultima volta. Così il conto è giusto anche prima della prima
     * misurazione (dove il rientro applicato è 0 e il margine è già quello di upstream) e resta giusto in tutti e tre
     * i rami di upstream, che scrivono dp(50), dp(54)+avatar o dp(57)+menu bot.
     *
     * @return il margine di upstream, oppure -1 se il campo non c'è ancora o non ha margini
     */
    private static int margineBaseDelCampo(ChatActivityEnterView barra, Stato stato) {
        if (stato.margineBaseVisto) {
            return stato.margineBase;
        }
        // prima della prima misurazione nostra: il margine della vista è ancora quello puro di upstream, e
        // rientroApplicato vale 0
        final ViewGroup.MarginLayoutParams lp = margini(barra.getEditField());
        return lp == null ? -1 : lp.leftMargin - stato.rientroApplicato;
    }

    private static ViewGroup.MarginLayoutParams margini(View vista) {
        if (vista == null) {
            return null;
        }
        final ViewGroup.LayoutParams lp = vista.getLayoutParams();
        return lp instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) lp : null;
    }

    private static void sommaAlMargineSinistro(View vista, int rientro) {
        final ViewGroup.MarginLayoutParams lp = margini(vista);
        if (lp != null) {
            lp.leftMargin += rientro;
        }
    }

    /** La larghezza della barra di scrittura, cioè del padre dello slot ({@code textFieldContainer}). */
    private static int larghezzaBarra(View slot) {
        return slot.getParent() instanceof View ? ((View) slot.getParent()).getWidth() : 0;
    }

    private static int margineDestro(View vista) {
        final ViewGroup.MarginLayoutParams lp = margini(vista);
        return lp == null ? 0 : lp.rightMargin;
    }

    /**
     * Quanto rientra il bordo sinistro del campo di testo, 0 se la modalità non si applica adesso.
     *
     * <p>Tutte le guardie stanno qui, così l'offset dello slot è pura aritmetica: se il rientro è 0 la pillola non si
     * muove, e se la pillola non si muove il campo non rientra. I due valori non possono divergere.
     *
     * <p>La larghezza della pillola si MISURA (vedi {@link #larghezzaPillola(View, int)}) e non si ricava
     * dall'altezza: nei canali con messaggi a pagamento {@code width()} vale {@code dp(18) + dp(20|22) + prezzo},
     * cioè più del cerchio, e dedurla dall'altezza metterebbe il prezzo in stelle sopra il pulsante emoji. Upstream
     * compensa lo stesso scarto in cinque punti con {@code Math.max(0, sendButton.width() - dp(DEFAULT_HEIGHT))}.
     */
    private static int rientro(ChatActivityEnterView barra, Stato stato, View slot, float larghezzaCorsia) {
        if (stato.occupante == NIENTE || !sendLeft()) {
            return 0;
        }
        if (barra.isStories || barra.getParentFragment() == null) {
            return 0;
        }
        // a sinistra, in questi stati, ci sono già il cestino del vocale registrato o il campo di modifica
        if (barra.isEditingMessage() || barra.hasAudioToSend()) {
            return 0;
        }
        // Mentre si registra, col solo primo gradino, lo slot torna a destra: a sinistra c'è la fila della
        // registrazione (punto rosso, timer, «scorri per annullare») e la pillola non c'entra niente. Col microfono
        // nella corsia, invece, il dito è PROPRIO lì: tornare a destra glielo strapperebbe da sotto a metà gesto.
        // La fila resta dove la mette upstream e il cerchio le passa sopra: è uno dei due limiti dichiarati nel
        // javadoc della classe.
        if (barra.isRecordingAudioVideo() && stato.occupante != MICROFONO) {
            return 0;
        }
        // l'anello del messaggio effimero (sendOutlineView) è fratello dello slot e privato: resterebbe a destra
        if (barra.isEphemeralMessageVisible()) {
            return 0;
        }
        // bozza ricca: il campo è coperto dall'anteprima e a destra c'è richButton, nella corsia riservata
        if (barra.isRichDraftActive()) {
            return 0;
        }
        final EditTextCaption campoTesto = barra.getEditField();
        if (campoTesto == null) {
            return 0;
        }
        // Bot inline. Upstream spegne la pillola e accende al suo posto la X di annullamento, ma il GONE della
        // pillola arriva solo alla FINE della dissolvenza (:8260): per 220 ms larghezzaPillola trova ancora una
        // SendButton VISIBILE e lo slot scivolerebbe a sinistra portandosi dietro l'area di tocco della X, che si
        // vedrebbe a destra (è controtraslata) senza prendere i tocchi — cioè per 220 ms la ricerca inline non si
        // potrebbe annullare, a ogni «@qualcosa» digitato. La didascalia è la condizione con cui upstream decide
        // quello stato (:8120, {@code messageEditText.getCaption()}): leggendola qui si esce PRIMA di muovere
        // niente, invece di accorgersene quando la dissolvenza è finita. Non basta guardare l'alpha della pillola:
        // al momento in cui questo codice gira l'animatore non ha ancora applicato il primo fotogramma e l'alpha
        // vale ancora 1. getCaption() è pubblica su EditTextCaption (:883), quindi costa zero righe upstream e una
        // rinomina non compila.
        //
        // Si guarda VUOTA, non diversa da null, e non e' pignoleria: la didascalia non torna piu' a null una volta
        // diventata la stringa vuota. {@code EditTextCaption.setCaption} (:123-125) esce subito quando la vecchia e
        // la nuova sono entrambe vuote — e {@code null} e' vuota — quindi da "" non si risale mai a {@code null};
        // e ci si arriva davvero, perche' {@code MentionsAdapter.getBotCaption()} (:808-814) torna
        // {@code bot_inline_placeholder}, che un bot puo' dichiarare vuoto. Con una didascalia a "" questa guardia
        // con {@code != null} restava vera per sempre e la modalita' si spegneva in silenzio per tutta la vita di
        // quella barra. Trovato dalla revisione del 31/10/2026 leggendo upstream, non provato sullo schermo.
        if (!TextUtils.isEmpty(campoTesto.getCaption())) {
            return 0;
        }
        if (slot.getVisibility() != View.VISIBLE) {
            // nota: questa guardia NON scatta dal percorso dell'editor di articoli, che mette lo slot a INVISIBLE
            // (RichEditor:195). setVisibility(INVISIBLE) non provoca una disposizione — solo GONE la provoca —
            // quindi l'OnLayoutChangeListener non parte e qui non ci si arriva: il campo resta rientrato dietro uno
            // slot invisibile. È innocuo perché l'editor copre tutta la barra, e al ritorno (:221, di nuovo
            // VISIBLE) il conto si rifà da solo; la guardia serve agli stati in cui lo slot va GONE
            return 0;
        }
        // nello slot comanda un altro figlio (la X del bot inline, lo slow mode) o non è ancora misurato
        if (larghezzaCorsia <= 0f) {
            return 0;
        }
        final int larghezzaBarra = larghezzaBarra(slot);
        if (larghezzaBarra <= 0) {
            // non ancora misurata: ci ripensa l'OnLayoutChangeListener
            return 0;
        }
        final int margineBase = margineBaseDelCampo(barra, stato);
        if (margineBase < 0) {
            return 0;
        }
        final int candidato = (int) larghezzaCorsia + 2 * AndroidUtilities.dp(ARIA_DP);
        final View colonna = barra.messageEditTextContainer;
        if (colonna == null) {
            return 0;
        }
        // la colonna emoji/testo è MATCH_PARENT con il rightMargin che riserva il posto allo slot (:2702): la sua
        // larghezza vera se è già disposta, altrimenti il conto dai margini
        final int larghezzaColonna = colonna.getWidth() > 0 ? colonna.getWidth() : larghezzaBarra - margineDestro(colonna);
        // Il margine destro del campo si conta solo quando c'è del testo, cioè quando quel margine è davvero
        // il confine del campo. A campo vuoto — l'unico stato in cui l'occupante è il microfono — upstream NON ci
        // mette dp(2): {@code updateFieldRight} (:8782-8789) scrive dp(50), dp(98) con un bot, un canale in cui si
        // pubblica o messaggi programmati in attesa, e dp(146) con tutti e tre, perché lì riserva il posto alla fila
        // degli allegati. Ma quei pulsanti SPARISCONO appena si scrive, e il margine torna a dp(2) (ramo else):
        // quello spazio non è perso, è spazio che il campo riavrà. Contandolo, su una barra da 360dp il conto
        // scendeva a 316 - 106 - 98 = 112dp, sotto il minimo di 120, e la corsia non si apriva affatto: in un canale
        // o in una chat con un bot l'interruttore non avrebbe fatto NIENTE, e si sarebbe scoperto solo sul telefono.
        // Trovato dalla revisione del 04/11/2026. La guardia resta intera dove serve, cioè col testo nel campo.
        final boolean microfono = stato.occupante == MICROFONO;
        final int utile = larghezzaColonna - (margineBase + candidato) - (microfono ? 0 : margineDestro(campoTesto));
        if (utile < AndroidUtilities.dp(CAMPO_MINIMO_DP)) {
            // il campo resterebbe troppo stretto: la modalità si spegne da sola invece di consegnare due parole
            return 0;
        }
        if (microfono) {
            // La seconda soglia, molto più bassa, sulla larghezza VERA del campo adesso. Non contare il margine
            // destro (sopra) è giusto perché quello spazio il campo lo riavrà appena si scrive, ma intanto il campo
            // c'è e ha un segnaposto: con un bot o un canale su uno schermo da 320dp la larghezza vera scendeva a
            // 262 - 106 - 98 = 58dp, e {@code EditTextBoldCursor.setHintText} tronca il segnaposto con
            // {@code TextUtils.ellipsize(..., getMeasuredWidth(), END)} (:652-653) invece di andare a capo: si
            // leggeva «Messaggi…». Qui la corsia resta chiusa, e si riapre da sé appena c'è testo — dove la soglia
            // vera è quella sopra. Trovato dalla revisione del 04/11/2026.
            final int veroOra = larghezzaColonna - (margineBase + candidato) - margineDestro(campoTesto);
            if (veroOra < AndroidUtilities.dp(SEGNAPOSTO_MINIMO_DP)) {
                return 0;
            }
        }
        return candidato;
    }

    /**
     * Dà a tutti i figli dello slot che NON fanno parte del comando di invio la traslazione opposta alla NOSTRA, così
     * restano disegnati dove li mette upstream: a destra. Senza questo, la traslazione dello slot si porterebbe
     * dietro il microfono per tutti i fotogrammi della dissolvenza.
     *
     * <p>Seguono lo slot soltanto {@code SendButton} (la pillola) e {@code SendButtonBlockedByTypingView} (il
     * «ferma» che upstream disegna sopra la pillola mentre un bot risponde): sono lo stesso comando. Tutti gli altri
     * figli — microfono, X del bot inline, espandi sticker, slow mode — sono occupanti alternativi dello slot.
     *
     * <p>Si controtrasla di {@code -stato.nostro}, cioè del valore che abbiamo applicato NOI, e non di
     * {@code -slot.getTranslationX()}. Leggere la vista sembrava più robusto (un'unica sorgente, somma esattamente
     * zero a ogni fotogramma) ma trattava quella proprietà come se fosse nostra: qualunque altro scrittore veniva
     * amplificato alla cieca. Sulla barra delle storie, dove {@code setHorizontalPadding} mette circa -148dp
     * (:14746), il microfono finiva disegnato 148dp a destra e fuori da ogni area di tocco, e ci restava perché
     * quella scrittura non fa {@code requestLayout}. Col nostro valore, invece, la controtraslazione cancella
     * esattamente il nostro contributo e lascia in piedi quello di chiunque altro.
     *
     * <p>Il valore nostro resta allineato alla vista fotogramma per fotogramma perché l'animazione dello slot porta
     * un {@code setUpdateListener} che lo interpola con la frazione vera dell'animatore: l'ultimo fotogramma vale 1
     * e lo lascia sul bersaglio, un annullamento a metà lo lascia dove è rimasta la vista. Quindi la somma fa
     * esattamente zero a ogni fotogramma anche se l'animazione parte da un valore intermedio.
     *
     * <p>Funziona anche con lo slot ristretto: i sei figli sono tutti {@code Gravity.RIGHT}, e poiché anche lo slot
     * è RIGHT nel padre i due bordi destri coincidono, quindi la posizione assoluta di ogni figlio non dipende dalla
     * larghezza dello slot e la controtraslazione li rimette esattamente dove li mette upstream.
     *
     * <p><b>Un difetto dichiarato, di un fotogramma e mezzo.</b> La {@code translationX} di un figlio è moltiplicata
     * per lo {@code scaleX} del padre, e upstream scala lo slot a {@code 0.79} per l'anello del messaggio effimero
     * (:15632-15633). Mentre quella scala è in corso, la controtraslazione cancella il 79% del nostro contributo
     * invece del 100%, quindi il microfono resta sfalsato di circa il 21% di {@code stato.nostro}. È un transitorio
     * dentro una dissolvenza che upstream sta già facendo, e dura quanto il ritorno della pillola (150 ms): col
     * messaggio effimero acceso il rientro va a 0 ({@code isEphemeralMessageVisible()}), quindi è esattamente la
     * finestra in cui lo slot sta tornando a destra. Dividere per lo {@code scaleX} del padre costerebbe zero righe
     * upstream ma renderebbe la controtraslazione dipendente da un valore che upstream anima: si è preferito
     * dichiararlo.
     *
     * <p>Quando il nostro valore è zero non si scrive NIENTE, tranne la volta in cui bisogna disfare una
     * controtraslazione che avevamo messo noi ({@code figliSpostati}): una barra che non abbiamo mai spostato — la
     * modalità spenta, le storie, il commento della lista chat — non si accorge nemmeno che esistiamo. Rimettere a
     * zero è legittimo perché in tutto il progetto <b>nessuna riga upstream scrive la translationX di questi
     * figli</b> (verificato su {@code audioVideoButtonContainer}, {@code cancelBotButton}, {@code slowModeButton},
     * {@code expandStickersButton}: zero occorrenze).
     */
    private static void fermaGliAltriFigli(ChatActivityEnterView barra, View slot, Stato stato) {
        if (!(slot instanceof ViewGroup)) {
            return;
        }
        final boolean fermo = Math.abs(stato.nostro) < 0.5f;
        if (fermo && !stato.figliSpostati) {
            return;
        }
        final float opposta = fermo ? 0f : -stato.nostro;
        // A secondo gradino acceso il microfono NON e' un occupante alternativo: e' lui che si sta spostando, quindi
        // segue lo slot come la pillola. Si riconosce per identita' e non per tipo, perche' e' un FrameLayout nudo
        // (:2979) come potrebbero esserlo altri figli.
        //
        // La condizione e' {@link #voiceLeft()} e non «l'occupante e' il microfono», e la differenza si vede per i
        // 220 ms della dissolvenza: upstream tiene microfono e pillola entrambi VISIBLE fino alla fine (:8265) e
        // {@link #occupante} da' la precedenza alla pillola, quindi legando la cosa all'occupante il microfono
        // salterebbe nella posizione di destra PROPRIO mentre sfuma, a ogni primo carattere digitato. Seguendo
        // sempre lo slot, invece, sfuma esattamente dove la pillola sta comparendo: che e' il senso del secondo
        // gradino — un posto solo per i due tasti che si scambiano il turno.
        final View microfono = voiceLeft() ? barra.getAudioVideoButtonContainer() : null;
        final ViewGroup gruppo = (ViewGroup) slot;
        for (int i = 0; i < gruppo.getChildCount(); i++) {
            final View figlio = gruppo.getChildAt(i);
            if (figlio instanceof ChatActivityEnterView.SendButton || figlio instanceof SendButtonBlockedByTypingView) {
                continue;
            }
            if (microfono != null && figlio == microfono) {
                figlio.setTranslationX(0f);
                continue;
            }
            figlio.setTranslationX(opposta);
        }
        stato.figliSpostati = !fermo;
    }

    /**
     * La larghezza vera della pillola di invio, col cerchio {@code altezzaSlot - dp(8)} come pavimento, oppure
     * {@code -1} se nello slot non c'è una pillola visibile (bot inline, slow mode, slot non ancora misurato): in
     * quel caso lo slot non si sposta, perché a comandare è un altro figlio e quel figlio resta a destra.
     *
     * <p>Si cerca fra i figli dello slot, non col campo {@code sendButton} (privato) né con un getter che possa
     * creare viste come effetto collaterale ({@code getBotWebViewButton()} istanzia la vista quando la si chiede).
     * {@code SendButton} e {@code width()} sono pubblici, quindi costa zero righe upstream. Gli altri figli dello
     * slot (microfono, {@code SendButtonBlockedByTypingView}, slow mode, cancel bot, expand stickers) non sono
     * {@code SendButton} e non entrano nel conto; se nello slot non c'è una pillola visibile si torna {@code -1} e
     * non il pavimento, perché «slot senza pillola» non vuol dire «pillola larga quanto il cerchio»: vuol dire che
     * lo slot non deve spostarsi affatto.
     *
     * <p>Quando il prezzo in stelle si apre la larghezza cambia, ma {@code updateSendButtonPaid} chiama
     * {@code updateFieldRight}, che riscrive i LayoutParams del campo di testo: il {@code requestLayout} che ne
     * segue fa scattare l'{@code OnLayoutChangeListener} di {@link #ascolta} e rientro e offset si ricalcolano.
     */
    private static float larghezzaPillola(View slot, int altezzaSlot) {
        if (altezzaSlot <= 0 || !(slot instanceof ViewGroup)) {
            return -1f;
        }
        boolean trovata = false;
        float larghezza = 0f;
        final ViewGroup gruppo = (ViewGroup) slot;
        for (int i = 0; i < gruppo.getChildCount(); i++) {
            final View figlio = gruppo.getChildAt(i);
            if (figlio instanceof ChatActivityEnterView.SendButton && figlio.getVisibility() == View.VISIBLE) {
                trovata = true;
                larghezza = Math.max(larghezza, ((ChatActivityEnterView.SendButton) figlio).width());
            }
        }
        if (!trovata) {
            return -1f;
        }
        // il pavimento serve anche quando la pillola c'è ma non è ancora misurata: width() userebbe
        // getMeasuredHeight() == 0 e tornerebbe -dp(8), spingendo lo slot fuori dallo schermo a sinistra
        return Math.max(larghezza, altezzaSlot - AndroidUtilities.dp(8));
    }

    /** Solo scalari: vedi la nota su {@link #BARRE}. */
    private static final class Stato {
        /** {@code shownSendButton} E testo vero nel campo: vedi {@link #sendSlot}. */
        boolean pillolaDiInvio;
        /**
         * Chi comanda nello slot adesso: {@link #NIENTE}, {@link #PILLOLA} o {@link #MICROFONO}. Lo decide
         * {@link #occupante}, e da lui dipendono tre cose: se il campo rientra, se il microfono segue lo slot o
         * resta a destra, e se il cerchio della registrazione si sposta ({@link #offsetVocale}).
         */
        int occupante;
        boolean ascolta;
        /** La barra non è nostra (storie): vedi {@link #escludi}. */
        boolean esclusa;
        /**
         * Il bersaglio deciso per la translationX dello slot. Parte da 0, non da {@code NaN}: a bersaglio zero e
         * contributo nostro zero non c'è NIENTE da scrivere, e il primo giro di una barra appena registrata deve
         * essere un vero no-op (con {@code NaN} la condizione corto-circuitava e si arrivava a
         * {@code slot.animate().cancel()} e a un animatore da 150 ms verso un valore che era già quello).
         */
        float applicato;
        /**
         * Quanto della translationX dello slot ci abbiamo messo NOI, fotogramma per fotogramma: è la base della
         * controtraslazione degli altri figli, al posto del valore letto dalla vista, ed è il valore che
         * {@link #traslazioneSlot(View)} restituisce a {@code MessageSendPreview}.
         */
        float nostro;
        /** Abbiamo una controtraslazione in piedi sugli altri figli, e prima o poi va disfatta. */
        boolean figliSpostati;
        /** Il rientro deciso per il campo, da applicare alla prossima misurazione. */
        int rientro;
        /** Il rientro che {@link #rientraIlCampo} ha davvero sommato al margine del campo. */
        int rientroApplicato;
        /**
         * Il leftMargin che upstream ha scritto nel campo, letto da {@link #rientraIlCampo} prima di sommarci sopra:
         * è il termine di confronto con cui {@link #offsetDelloSlot} verifica che il rientro sia davvero in piedi.
         */
        int margineBase;
        boolean margineBaseVisto;
        /**
         * Il rinvio dell'azzeramento del rientro (vedi {@link #bersaglioDiLayout}). Cattura la barra per
         * {@link WeakReference}: un riferimento forte dal valore alla chiave terrebbe in vita per sempre la voce
         * della {@link #BARRE}.
         */
        Runnable rinvio;
        /** La larghezza da dare ai LayoutParams dello slot, e quella che aveva upstream (-1 = da non toccare). */
        int larghezzaSlot;
        int larghezzaSlotBase;
    }
}
