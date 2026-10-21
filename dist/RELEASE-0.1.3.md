# Morsegram 0.1.3

La versione piu' grossa finora: un assistente che lavora con le tue chiavi, cinque widget
nuovi, e tutto quello che serviva a non tradire le premesse dell'app.

## Assistente

Quattro cose, e nessuna di piu':

- **riassume l'arretrato** di una chat, quando torni e trovi centinaia di messaggi;
- **traduce** un messaggio (e' una funzione a pagamento su Telegram: qui la fai con la tua chiave);
- **propone una bozza di risposta**, che finisce nel campo di scrittura perche' la corregga e la
  mandi **tu**;
- **cerca nella cronologia** con una domanda in parole normali.

Funziona con **Groq** o **Google Gemini**, chiavi tue: ne imposti una principale e una di
ripiego, che entra in gioco solo se la prima e' guasta (limite superato, server giu', rete
assente) e mai quando il modello semplicemente rifiuta di rispondere.

**L'app non invia mai un messaggio da sola.** E' il vincolo piu' importante di tutta la versione:
l'invio automatico da un account utente e' il motivo piu' comune di account limitati da Telegram.

### Cosa esce dal telefono, e come lo controlli

- **Spento di default**, si accende **per singola chat**, mai su una chat nascosta.
- Prima della prima richiesta su una chat vedi **l'anteprima esatta** del testo che partirebbe,
  oscuramenti gia' applicati, e chi lo riceverebbe (anche il fornitore di ripiego, se configurato).
- Prima dell'invio vengono **mascherati** numeri di telefono, email, IBAN, carte, codice fiscale,
  codici di verifica e link con token dentro. L'oscuramento avviene **prima** del taglio del testo,
  cosi' un identificatore a cavallo del limite non esce a pezzi.
- L'oscuramento prende gli schemi, non il significato: una confidenza resta sensibile anche senza
  numeri dentro. Per questo si accende una chat alla volta.
- Le chiavi sono **cifrate** e fuori dai backup.
- Se un risultato e' offensivo o falso lo puoi **segnalare dall'app**. La segnalazione resta sul
  telefono: nessun indirizzo a cui spedirla vive dentro l'app, perche' finirebbe dentro un APK
  pubblico.

I fornitori ritirano i modelli ogni pochi mesi. Nelle impostazioni puoi scrivere il nome di un
modello attuale senza aspettare un aggiornamento dell'app.

## Widget

Cinque, personalizzabili per chat, trasparenza, tema e dimensioni:

| Widget | Cosa fa |
|---|---|
| Modalita' aereo | interruttore sulla schermata iniziale, senza aprire l'app |
| Chat nascoste | scorciatoia al lucchetto: non mostra mai nomi, anteprime o conteggi |
| Non letti | contatore, chat nascoste escluse |
| Vocale rapido | registra per una chat scelta; l'invio lo confermi tu |
| Chat singola | una chat e la sua risposta rapida; l'invio lo confermi tu |

I tre widget che Telegram ha gia' passano all'ambra. E nascondere una chat ora li aggiorna
subito: prima quella chat restava visibile sulla schermata iniziale finche' non si ricaricavano
da soli.

## Provato e non provato

Provato davvero: l'oscuramento (38 casi su JVM), la compilazione, l'installazione reale e
l'avvio. **Non provato**: tutto cio' che sta dietro l'accesso a un account, cioe' quasi tutta
l'interfaccia nuova. Segnala quello che trovi.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
