# Morsegram 0.1.16

**«Accedi dal telefono» sull'orologio funziona davvero, e l'app non porta piu' in giro i percorsi
del computer su cui e' stata compilata.**

## L'accesso dall'orologio, rifatto al rovescio

Nella 0.1.15 quel pulsante era un vicolo cieco: arrivava fino in fondo e poi il server rispondeva
`webauth_token_expired`. Non era un difetto di trasporto — il meccanismo scelto **non e' aperto ai
client non ufficiali**, e nessuna correzione lo avrebbe reso tale.

Adesso i ruoli sono invertiti. Prima era il telefono a creare un codice d'accesso e l'orologio a
presentarlo; ora **e' l'orologio a chiedere il proprio codice** (lo stesso del percorso QR, chiesto
con le chiavi di Morsegram) e il telefono si limita a confermarlo, con la stessa chiamata che
Telegram esegue quando si inquadra un codice QR. Quella e' aperta a tutti.

**Come si usa.** Sull'orologio tocchi **Accedi dal telefono**. Sul telefono si apre un foglio: scegli
l'account, **Avanti**, e compaiono quattro emoji. Se sono le stesse che vedi al polso, tocchi
**Accedi** sul telefono. Fine: niente codice QR da inquadrare, niente numero da digitare sull'orologio.

Le quattro emoji non sono un ornamento: sono l'impronta della chiave concordata fra i due
apparecchi. Se non coincidono, non confermare.

**Con la verifica in due passaggi** l'orologio chiede la password cloud dopo la conferma. Non e' una
nostra scelta: e' come si comporta Telegram su questo percorso. La password si digita nel campo
dell'app, non viene salvata sull'orologio e non passa dalla dettatura.

**Vanno aggiornati tutti e due.** Un orologio 0.1.16 con un telefono piu' vecchio non trova nessuno
con cui parlare: l'orologio lo dice e ti rimanda al codice QR, che continua a funzionare come prima.

## L'app non porta piu' in giro i percorsi del mio computer

Nelle versioni fino alla 0.1.15, dentro la libreria nativa dell'APK c'erano **427 stringhe** con il
percorso completo delle cartelle sulla macchina di chi compila — nome utente compreso. Arrivano dai
controlli interni di webrtc e tgcalls, che incidono il nome del file sorgente nel binario, e non
sono informazioni di debug che si possano togliere a posteriori: sono costanti vere.

Non uscivano credenziali di nessun tipo, ma il nome utente e la disposizione delle cartelle non
hanno motivo di stare in un pacchetto che si distribuisce. Da questa versione i percorsi sono
riscritti in una forma neutra.

## Limiti dichiarati, senza girarci intorno

- **Il giro completo dell'accesso non e' stato visto su apparecchi veri.** Serve il telefono con il
  debug via Wi-Fi acceso e l'orologio associato. E' verificato che compila, che le due meta' del
  protocollo si corrispondono byte per byte e che le tabelle delle emoji dei due apparecchi sono
  identiche voce per voce; il comportamento con la verifica in due passaggi e' quello documentato da
  Telegram per il percorso QR, non una cosa che abbiamo guardato.
- **La barra di scrittura continua a non essere stata vista da nessuno**, quindi la modalita' mancini
  della 0.1.15 resta verificata leggendo il codice. Se trovi la pillola di invio fuori posto, spegni
  l'interruttore: torna tutto dove era.
- **La corona per il volume non e' ancora stata provata al polso** su un orologio vero.
- Un giro di revisione in venti punti ha trovato dodici difetti in questo codice, tutti corretti
  prima di pubblicare: fra gli altri, il foglio sul telefono non poteva comparire ad app chiusa — che
  e' il caso normale — e una risposta in ritardo del server poteva chiudere il foglio di un accesso
  diverso. E' ragionevole aspettarsi che qualcosa resti.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
