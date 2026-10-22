# Morsegram 0.1.11

**Sblocchi locali, solo in questa edizione.**

Due vincoli che Telegram applica *nel client* e che qui non si applicano. Nessuna finzione:
il flag dell'abbonamento non viene toccato e nessuna risposta del server viene falsificata.
Si interviene su ogni singolo vincolo, uno per uno.

| Sbloccato | Cosa cambia |
|---|---|
| **Quattro account invece di tre** | il commutatore accetta un quarto accesso; ogni account e' un accesso indipendente e il server non conta quante sessioni stanno nella stessa app |
| **Traduzione di tutta la chat** | la barra Traduci e la traduzione automatica della conversazione, senza abbonamento |

Nelle impostazioni Morsegram c'e' la sezione **Sblocchi locali** che elenca cosa e' attivo.

## Cosa NON e' stato sbloccato, e perche'

- **Icone alternative dell'app**: sono grafiche Telegram. Metterebbero il logo di un'altra app
  sulla tua schermata iniziale.
- **Cartelle oltre il limite**: si vedrebbero ma non si riuscirebbero a salvare, perche' quel
  limite lo applica il server.
- **Scarico piu' veloce**: la banda la concede il server, il guadagno non e' garantito e si
  pagherebbero batteria e dati.
- **Caricamenti da 4 GB, trascrizione lato server, assenza di pubblicita', reazioni e sticker
  speciali**: li decide il server. Nessun fork puo' sbloccarli, per quanto prometta.

## Questa edizione e quella di Google Play

Gli sblocchi esistono **solo nell'APK distribuito qui**. Nel pacchetto per lo Store non ci sono:
non sono nascosti, non ci sono proprio, nemmeno i testi. Sbloccare le funzioni a pagamento di un
altro servizio viola le regole dello Store, e l'app verrebbe rimossa.

## Nota sulle versioni 0.1.8, 0.1.9 e 0.1.10

Quelle tre release contenevano gia' questo lavoro, allora incompleto, senza che le note lo
dicessero: un errore mio nel preparare i commit. Il codice era spento nell'edizione per lo
Store, quindi non e' uscito nulla di indesiderato, ma le note non corrispondevano al contenuto.
Da ora si committano i percorsi toccati invece dell'intero albero.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
