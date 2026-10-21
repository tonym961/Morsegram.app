# Cosa fanno gli altri fork di Telegram, e cosa conviene aggiungere a Morsegram

Resoconto del 21 ottobre 2026. Base di confronto: Nekogram, Nagram (erede di NekoX), exteraGram,
AyuGram, OwlGram, Catogram, Forkgram, Swiftgram, Plus Messenger, Nicegram, Kotatogram, Unigram,
Telegram-FOSS. Morsegram resta un fork sottile: perimetro ~25 file e ~71 righe dentro il codice di
Telegram, e ogni riga si paga a ogni riallineamento mensile. La valuta di questo documento sono
quelle righe, non il numero di funzioni.

---

## 1. In breve

1. Gli altri offrono, rispetto a noi: controllo delle notifiche per categoria (menzioni, risposte,
   messaggi fissati), backup delle proprie impostazioni nel cloud Telegram, inoltro senza mittente e
   senza didascalia, selezione dei messaggi per intervallo e per autore, cartella d'avvio e gesti
   configurabili nella lista chat, e un enorme catalogo di opzioni estetiche.
2. La cosa più sorprendente: la funzione più richiesta di tutta la famiglia — il ghost mode — è anche
   quella che costerebbe l'account, e il fork metodologicamente più vicino a noi, Nekogram (stessa base
   13.0.2, pubblicata lo stesso giorno), **non ce l'ha** e non nasconde nemmeno i messaggi sponsorizzati.
   Restare puliti non è una zavorra: è la scelta di chi è rimasto vivo su Play.
3. Seconda sorpresa: nessuno dei fork Android esaminati ha una sola funzione per i pieghevoli, nessuno
   ha Wear OS, e nessuno trascrive i vocali sul dispositivo. Nekogram manda l'audio a Cloudflare.
   Su questi quattro punti non siamo in ritardo: siamo soli.
4. Terza sorpresa, meno gradita: il catalogo è grande ma le fonti utilizzabili sono poche. OwlGram e
   Catogram sono archiviati, exteraGram ha il sorgente chiuso dal gennaio 2025 e le build offuscate,
   AyuGram4A tiene parte del codice in un repo privato. Si copiano idee, non righe.
5. Il grosso di quello che questi fork vendono come "funzioni" sono interruttori estetici: Nagram ha
   circa 68 KB di sole stringhe aggiunte, cioè centinaia di opzioni, ognuna un aggancio da riallineare
   ogni mese. È l'esatto contrario di un fork sottile e non va imitato.

---

## 2. Quello che abbiamo già (e dove siamo avanti)

| Funzione | Chi la offre | Come la facciamo noi |
|---|---|---|
| Trascrizione vocali senza Premium | Nekogram (audio inviato a Cloudflare Workers AI, serve un account Cloudflare), exteraGram (Vosk sul dispositivo), Swiftgram (motore di sistema) | Sul dispositivo, l'audio non esce. **Più riservata di tutte**, Premium incluso |
| Pin oltre il limite del server | NekoX/Nagram, OwlGram, Forkgram — tutti "spegnendo la sincronizzazione" | Pin locali, stessa tecnica, stesse parole. **Alla pari**: conferma che l'approccio è quello praticato |
| Chat protette | Nicegram (lucchetto per chat, cartelle nascoste, Double Bottom), Nekogram/OwlGram/exteraGram (passcode per *account*) | Chat nascoste con PIN e impronta, invisibili in lista, ricerca, condivisione e widget, notifiche mascherate. **Avanti**: gli altri lavorano per account, noi per singola chat |
| Interfaccia senza Storie, regali, Stars, AI | Nagram e Swiftgram le *nascondono* (i dati si caricano comunque) | Non le carichiamo all'avvio. **Avanti** |
| Assistente AI con chiavi dell'utente | exteraGram (servizi configurabili, "ruoli"), Nagram (LLMTranslator, 11 provider), Nicegram | Riassunto arretrato, traduzione, bozza che invia l'utente, ricerca in linguaggio naturale. **Alla pari**, con due dettagli loro che ci mancano (vedi candidati 3) |
| Due pannelli sui pieghevoli | Nessun fork Android. Solo Unigram, su Windows, con finestre separate | Due pannelli con continuità della chat. **Soli** |
| Orologio | Nessuno | App Wear OS completa, invio e ascolto di vocali. **Soli** |
| Auto | Nagram ha aggiunto i vocali in Android Auto nella 12.10.1 | Android Auto già presente. **Alla pari**, con l'orologio in più |
| Sospendere la connessione | AyuGram ha un "Kill App" che chiude il processo | Modalità aereo interna: sospende l'app, il telefono resta connesso. **Avanti** |
| Aggiornamento in-app solo da sideload | Nekogram, OwlGram, exteraGram, Nagram: identica scelta, identica ragione (la revisione di Play) | Stessa cosa. **Alla pari** |
| Niente finto Premium | OwlGram e Forkgram dichiarano la stessa linea; NekoX, Nagram e AyuGram hanno il "Local Premium" | Premium lo decide il server. **Alla pari con i migliori** |

Vale la pena scrivere i primi quattro punti anche nel materiale pubblico, invece di lasciarli impliciti:
sono esattamente ciò che nessun altro fork ha.

---

## 3. Candidati veri

Ordinati per rapporto fra utilità e costo, non per popolarità. Il costo è in righe dentro il codice di
Telegram. I file che upstream riscrive più spesso sono `ChatMessageCell.java` (1,63 MB),
`ChatActivity.java` (2,57 MB) e `DialogsActivity.java`: ogni aggancio lì si ripaga ogni mese.

### 3a. Si fa in casa nostra — zero righe upstream

**1. Backup delle impostazioni nel cloud Telegram.**
Esporta la configurazione di Morsegram come JSON e la conserva nel proprio cloud, con ripristino.
Fork: Nekogram (`CloudSettingsHelper`), Nagram (`CloudConfig`); versione su file in OwlGram, exteraGram,
Plus Messenger, Kotatogram.
Perché per questo utente: Morsegram si installa da sideload e si reinstalla a ogni riallineamento. Oggi
ogni reinstallazione azzera tema, widget, chiavi dell'assistente AI, configurazione dell'orologio e
dell'auto. È l'unico candidato che risolve un problema che l'utente ha *già*, ogni mese.
Costo: tutto nel nostro package (serializzazione delle nostre preferenze + un messaggio nel proprio
cloud). Zero righe upstream, al massimo una voce nella schermata impostazioni che è già nostra.
Avvertenza, presa da NekoX: PIN e chat nascoste vanno **esclusi** dall'esportazione e dall'import.
NekoX ha dovuto scrivere una stringa per avvisare che importare da fonte ignota può sovrascrivere il PIN
e bloccare l'app.
Questo è il miglior candidato di tutta la ricerca.

**2. Notifiche per categoria: menzioni, risposte, messaggi fissati.**
Tre livelli indipendenti dal silenzioso della chat: predefinito, silenziose, disattivate.
Fork: solo Swiftgram, e la vende come funzione a pagamento. Telegram non la offre.
Perché per questo utente: sta in molti gruppi. Oggi l'unica via è silenziare tutto, e con tutto si
perdono le menzioni dirette. È la funzione a più alto valore d'uso di tutto l'elenco.
Costo: il filtro vive nel nostro percorso di notifica, che già tocchiamo per le notifiche mascherate
delle chat nascoste. Il grosso è nostro. Resta da verificare se la categoria (menzione / risposta /
fissato) sia distinguibile lato client prima di aprire la chat: se lo è, costa poche righe; se serve
leggere il messaggio, il costo cambia (vedi §5).

**3. Assistente AI: contesto, rotazione delle chiavi, test di connessione.**
Nagram passa al modello fino a 5 messaggi precedenti come contesto, gestisce più chiavi a rotazione,
elenca i modelli disponibili e ha un pulsante di test. exteraGram salva "ruoli", cioè prompt riusabili.
Perché per questo utente: la traduzione e la bozza di risposta senza contesto sbagliano proprio nei
gruppi, dove il messaggio da tradurre risponde a qualcosa. La rotazione delle chiavi serve quando una
finisce la quota a metà giornata.
Costo: zero righe upstream, è tutto dentro codice che abbiamo scritto noi. È la modifica più economica
dell'elenco in assoluto.

**4. Modello sul dispositivo per riassunto e bozza (Gemini Nano via AICore).**
exteraGram scarica il modello una volta e lo esegue sul telefono: niente chiavi, niente rete, niente
testo che esce.
Perché per questo utente: è la stessa scelta già fatta per la trascrizione, applicata al resto
dell'assistente; tiene alla privacy e il Galaxy Z Fold è fra i dispositivi che hanno AICore.
Costo: zero righe upstream, ma non è una riga nemmeno da noi: va gestito il modello assente, la memoria
insufficiente, il messaggio troppo lungo, e il ripiego sulle chiavi. Da fare dopo i primi tre, e solo
dopo aver verificato AICore sul dispositivo (§5).

**5. Avvisi di privacy proattivi.**
Segnala che il numero è visibile, che si può essere trovati tramite numero, che le chiamate P2P sono
attive, che manca la verifica in due passaggi — e offre di correggere.
Fork: NekoX/Nagram (`PrivacyNotice*`).
Perché per questo utente: tiene alla privacy e queste quattro impostazioni stanno in quattro posti
diversi dell'app ufficiale.
Costo: legge le impostazioni privacy dal server e propone la modifica; vive in una schermata nostra.
Zero righe upstream. Valore una volta sola: si sistema e non torna. Ma costa poco.

**6. Silenzia i non contatti.**
Le notifiche di chi non è in rubrica arrivano senza suono. Fork: Nekogram (`silenceNonContacts`).
Perché per questo utente: molti gruppi significa messaggi privati da sconosciuti.
Costo: una riga nel percorso di notifica che già gestiamo. Si fa insieme al candidato 2.

### 3b. Tocca file di Telegram

**7. Inoltro senza mittente e senza didascalia.**
Fork: Nekogram, NekoX/Nagram, Forkgram, Kotatogram, Nicegram. È il più richiesto della famiglia.
Non è una finzione locale: usa i flag `drop_author` e `drop_media_captions` di `messages.forwardMessages`,
che Telegram supporta ufficialmente.
Perché per questo utente: sposta materiale fra gruppi; oggi deve salvare e reinviare a mano.
Costo: due flag sulla chiamata, più una voce nel foglio di inoltro. La voce di menu atterra in
`ChatActivity.java`, **file riscritto spesso**: metterne una sola, non tre.
Da non estendere alla variante "Fast Forward" verso molte chat in coda: lì si entra nell'invio
automatico (§4).

**8. Cartella d'avvio, o riapri l'ultima cartella usata.**
Fork: Kotatogram, Swiftgram.
Perché per questo utente: con molti gruppi non si vive in "Tutte le chat", si vive in una cartella.
Costo: una o due righe nella selezione iniziale di `DialogsActivity.java`, **file riscritto spesso**.
Rapporto utilità/costo fra i migliori del gruppo.

**9. Gesti della lista chat configurabili, compreso disattivare lo swipe che elimina.**
Fork: Swiftgram.
Perché per questo utente: sul Fold si usa molto il telefono chiuso, con il pollice, e lo swipe che
archivia o elimina scatta per sbaglio. Richiesta ricorrente in tutte le recensioni.
Costo: un aggancio nel gestore dello swipe nelle celle della lista. Poche righe, file riscritto spesso.

**10. Conferma prima di chiamare, e prima di inviare sticker, GIF o vocale.**
Fork: tutti — AyuGram, OwlGram, Catogram, Swiftgram, Kotatogram.
Perché per questo utente: la chiamata involontaria parte dalla lista membri di un gruppo o dal profilo;
l'orologio è il posto dove i tocchi sbagliati sono la norma.
Costo: due o tre guardie di una riga. Economico; da proporre come interruttori spenti per default,
perché la conferma a chi non la vuole è solo un tocco in più.

**11. Seleziona l'intervallo fra due messaggi, e seleziona i messaggi di un solo autore.**
Fork: Forkgram, Nicegram, Swiftgram ("Select from Author"), Nekogram.
Perché per questo utente: in un gruppo il caso reale è raccogliere o inoltrare in blocco il materiale
di una persona.
Costo: logica di selezione in `ChatActivity.java`, file riscritto spesso. Qualche riga in più degli
altri candidati di questa sezione.

**12. ID e datacenter nel profilo, più la data di registrazione stimata.**
Fork: tutti e otto, in una forma o nell'altra. La data si stima interpolando l'ID utente su una tabella.
Perché per questo utente: è la voce più richiesta dagli utenti tecnici, e la data di registrazione è il
controllo anti-truffa più rapido quando uno sconosciuto scrive da un gruppo.
Costo: una riga nel profilo più una tabella dati inclusa nell'app. Va dichiarata come **approssimata**,
come fa Swiftgram.

**13. Filtro per parole chiave che collassa i messaggi invece di nasconderli.**
Fork: Swiftgram Pro, AyuGram, Nagram (`RegexFilters`, codice di origine AyuGram).
Perché per questo utente: segue canali e gruppi rumorosi.
Costo: aggancio nel rendering della lista messaggi, cioè `ChatMessageCell.java`, **il file più costoso
da riallineare**. Scelta di progetto: collassare e marcare, non rimuovere — così si resta lontani sia
dalla censura dei contenuti sia da qualsiasi somiglianza con il nascondere i messaggi sponsorizzati.

**14. Reazioni piccole o nascoste, e secondi nell'orario.**
Fork: Swiftgram, Nicegram, AyuGram (reazioni); quattro fork su otto hanno i secondi.
Perché per questo utente: le reazioni sporcano la lettura nei canali.
Costo: due flag, ma entrambi in `ChatMessageCell.java`. Valore modesto, file carissimo: da prendere solo
se si tocca già quel file per il candidato 13 o 15.

**15. Larghezza adattiva delle bolle sul pieghevole aperto.**
Fork: AyuGram, Swiftgram, Kotatogram — tutti su desktop o iPad, nessuno su pieghevole Android.
Perché per questo utente: è il suo dispositivo, e a schermo aperto la colonna stretta spreca metà
schermo. I nostri due pannelli lo rendono più visibile, non meno.
Costo: il più alto dell'elenco, perché è aritmetica di layout dentro `ChatMessageCell.java` e non esiste
prior art Android da copiare. Da considerare solo come *la* spesa grossa dell'anno, e forse limitata ai
soli post di canale, dove il problema si vede di più e il codice è più isolato.

**16. Seek sui vocali.**
Fork: Forkgram, Swiftgram, AyuGram. La più richiesta fra le funzioni media.
Perché per questo utente: ascolta vocali dall'orologio e in auto, dove riascoltare dall'inizio un vocale
di tre minuti è il caso peggiore.
Costo: interfaccia del player, costo medio. In fondo alla lista solo perché l'orologio e l'auto hanno
già una resa accettabile.

### Quello che non vale la pena nemmeno valutare

Il grosso del catalogo di questi fork è rumore: pacchetti di icone installabili, otto o nove icone
alternative dell'app, avatar come sfondo del menu laterale con sfocatura regolabile, animazioni
"gooey" e "liquid glass", set di emoji sostituibili, spaziatura Pangu per il cinese, registro delle
richieste di rete, barra inferiore riscritta, motore di plugin Python. Ognuna è un aggancio da
riallineare ogni mese e nessuna cambia come si usa l'app. Nagram ne ha centinaia: è il controesempio,
non il modello. Da questa famiglia si prende l'idea del catalogo, non il catalogo.

Due cose popolari sono fuori perimetro per ragioni di peso, non di principio: il client proxy
multiprotocollo di NekoX (librerie native, build separate, superficie di sicurezza enorme — Nekogram non
l'ha mai copiato) e l'esportazione della sessione come QR cifrato: un QR che vale un accesso al proprio
account è un bersaglio, e noi non abbiamo modo di proteggerlo meglio di chi l'ha scritto.

---

## 4. Da non fare, e perché

Non è una questione di morale, è un calcolo di rischio, e il rischio non cade sulla funzione: cade su
tutto il resto. I termini delle API prevedono che l'avviso di violazione arrivi all'account Telegram del
numero che possiede l'api_id, con 10 giorni per rimediare (§4.1-4.2); poi la revoca dell'api_id, che
rende inutile **ogni copia installata** — e sullo stesso numero l'api_id non si rifà. Gli account che
entrano da client non ufficiali sono comunque "automaticamente messi sotto osservazione". Quindi la
posta in gioco di una spunta di lettura nascosta è: l'app sull'orologio, l'auto, le chat nascoste, i
widget, la trascrizione. Tutto.

- **Ghost mode: non confermare le letture.** AyuGram ("Don't read messages"), Nagram
  (`NekoGhostModeActivity`, codice preso da AyuGram, sopprime il pacchetto di lettura verso il server).
  Vietato alla lettera: §1.4 cita "implementing a 'ghost mode'". È la funzione più richiesta della
  famiglia, e la risposta resta no.
- **Simulare di essere offline, o andare offline subito dopo essere stati online.** §1.4 vieta di
  impedire la corretta visualizzazione di online e ultimo accesso.
- **Non inviare lo stato "sta scrivendo".** NekoX (`DisableChatAction`), AyuGram, Catogram. Variante più
  leggera, stessa clausola §1.4, stesso trattamento.
- **Rispondere senza far risultare la chat letta** (AyuGram: "Schedule messages", "Send read status
  after reply"). È l'aggiramento delle conferme di lettura travestito da pianificazione.
- **Rimuovere o nascondere i messaggi sponsorizzati.** NekoX/Nagram (`hideSponsoredMessage`), AyuGram
  ("Ad-Free by Default"), e la stessa famiglia in formato ridotto: nascondere il canale sponsor del
  proxy (`HideProxySponsorChannel`). §3.3 impone di supportare gli sponsored message senza interferire;
  chi non li mostra "will have to be disconnected". Nekogram, che è il nostro vero termine di confronto,
  non ha né la stringa né la chiave: l'ha deciso anche lui.
- **Salvare i messaggi che gli altri hanno cancellato, e la cronologia delle modifiche.** AyuGram
  ("Save deleted messages", "Save edits history", anti-recall). La documentazione di AyuGram le elenca
  lei stessa fra le funzioni che infrangono i termini e aggiunge di non essere responsabile dell'eventuale
  blocco dell'account. Oltre ai termini, è un problema di policy sui dati per Play. *Nota utile:
  in Nekogram e in Nagram questa funzione non esiste affatto.*
- **Invio automatico da account utente.** Le varianti spinte del "Fast Forward" di Forkgram e
  dell'inoltro multiplo di Kotatogram, il "Mention All" di Nicegram, "Auto Replace Repeat" di Nagram, e
  "elimina tutti i miei messaggi in una chat" quando parte a raffica. È il motivo più comune di account
  limitati. Confine pratico: un'azione per un tocco dell'utente, nessuna coda automatica. Le risposte
  rapide a template restano lecite proprio per questo.
- **Contenuti a scadenza resi permanenti** ("expire button" per foto e video a tempo): §1.4 vieta di
  impedire l'autodistruzione.
- **Usare l'api_id dell'app ufficiale** ("built with official keys", AyuGram4A; "Custom API" con i
  preset "Telegram Android" in NekoX/Nagram). §2.1 impone un api_id proprio; chi usa chiavi non proprie
  porta i suoi utenti all'errore `API_ID_PUBLISHED_FLOOD`. NekoX stesso metteva l'avviso "il tuo account
  Telegram potrebbe essere bannato" sul menu che la conteneva.
- **Premium locale.** NekoX, Nagram, AyuGram. Sblocca solo cosmetica, il server rifiuta il resto, e il
  risultato è un'app che mente a chi la usa. Linea già scelta: Premium lo decide il server.
- **Finto screenshot** (NekoX) e **disattivare FLAG_SECURE** (Nagram, AyuGram, Catogram): il primo
  fabbrica un messaggio di servizio che non è avvenuto; il secondo smonta la protezione dello schermo in
  un'app che offre chat nascoste con PIN. Il secondo non è vietato dai termini: è solo autolesionista.
- **Accesso con token di bot** (NekoX/Nagram) e **rimozione del rilevamento emulatore** (patch
  Telegraher): automazione e antiabuso, cioè la categoria che fa scattare le limitazioni.
- **Ignorare le restrizioni di contenuto** (Nekogram, NekoX, Telegram-FOSS, AyuGram): zona grigia
  tollerata su F-Droid, incompatibile con Google Play, dove Morsegram ha una versione.
- **Motore di plugin di terze parti** (exteraGram, plugin Python) e **server di sincronizzazione proprio**
  (AyuSync): non sono violazioni dei termini, sono codice altrui e chat altrui dentro il processo che
  tiene la sessione Telegram. Fuori discussione in un fork sottile.

---

## 5. Non verificato

- **Notifiche per categoria (candidato 2):** le voci di Swiftgram vengono dal suo file di stringhe, che
  è affidabile sui nomi e muto sull'implementazione. Non so se agisca lato client o chieda eccezioni al
  server, e su Android questo cambia completamente il costo. Da controllare: se il payload push e
  l'oggetto messaggio bastino a distinguere menzione, risposta e messaggio fissato *prima* di aprire la
  chat. Va guardato nel nostro percorso di notifica e in `NotificationsController` dell'upstream.
- **AICore sul Galaxy Z Fold (candidato 4):** non ho verificato che Gemini Nano via AICore sia
  disponibile a un'app di terze parti su SM-F971B senza autorizzazioni particolari, né quanta memoria
  chieda. Da provare sul dispositivo prima di impegnarci.
- **Blocco di codice con scelta del linguaggio:** probabile che la base 13.0.2 lo abbia già. Da
  verificare per non riscrivere una funzione esistente.
- **Nessuna misura reale del perimetro altrui.** Le dimensioni che circolano (KB di stringhe, righe dei
  singoli file) sono lette dall'albero dei repo: dicono l'ordine di grandezza, non le righe di patch
  sull'upstream. Nessuna delle stime di costo in §3 è stata misurata su un diff: sono valutazioni di
  dove atterra l'aggancio.
- **Sorgenti non utilizzabili.** exteraGram: repo ufficiale archiviato il 03/01/2025, build 12.x
  offuscate, elenco funzioni ricostruito da una deoffuscazione di terzi (nomi affidabili, codice no).
  AyuGram4A: repo pubblico fermo all'11/09/2023, parte del codice in un repository privato. Da nessuno
  dei due si prende codice.
- **Fonti promozionali.** Plus Messenger e Nicegram non pubblicano i sorgenti: gli elenchi vengono da
  App Store, APKMirror e copy del produttore, su versioni 8.x mentre la corrente è 12.x. Alcune voci
  potrebbero non esistere più. La descrizione F-Droid di Forkgram non è stata confrontata col codice
  sulla base 12.9.
- **Limiti client contro limiti server.** Plus Messenger dichiara 100 pin: alcuni limiti sono validati
  dal server e l'alzata è solo apparente. Il nostro approccio (pin locali con sincronizzazione spenta) è
  quello verificato.
- **Clausola AI dei termini (§1.5 e content-licensing).** Vieta in termini larghi l'uso di dati della
  piattaforma per sviluppare o far funzionare modelli, con eccezione solo per consenso esplicito e
  continuato di tutti gli utenti coinvolti. Non dice nulla di esplicito su riassunto e traduzione chiesti
  dall'utente sulle proprie chat, ed exteraGram lo fa comunque con chiavi dell'utente — ma la
  formulazione è abbastanza larga da toccare il nostro assistente. Decisione da prendere
  consapevolmente, non da ignorare.
- **Pieghevoli e accessibilità.** Nessun fork Android esaminato ha funzioni per i pieghevoli e nessuno
  documenta lavoro verificabile su TalkBack. Non c'è niente da copiare: il candidato 15 è tutto nostro,
  ed è anche per questo che costa.
- **Fork non esaminato che vale una seconda ricerca:** OctoGram (erede vivo della linea CatogramX,
  ultimo push 21/09/2026), l'unico fork attivo e non aggressivo rimasto fuori da questa raccolta.
