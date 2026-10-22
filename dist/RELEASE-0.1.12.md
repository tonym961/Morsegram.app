# Morsegram 0.1.12

**Sblocchi locali: la rassegna completa.**

Sono stati esaminati **393 punti** del codice di Telegram in cui l'app controlla se sei
abbonato, uno per uno, divisi in tre aree. Di questi, **otto** sono vincoli che il client
applica da solo e che qui non si applicano piu'.

| Sbloccato | Cosa cambia |
|---|---|
| **Sticker a pagamento** | si inviano dal pannello e dalla pressione lunga, e sparisce il lucchetto dalla cella |
| **Categorie di ricerca delle emoji** | torna la riga cuore, festa, animali nella tastiera emoji |
| **Cartelle bloccate** | le cartelle in eccesso smettono di essere lucchettate e tornano usabili |
| **Sfumature a pagamento dell'avatar** | le sfumature riservate del costruttore di avatar |
| **Sfumatura personalizzata** | interruttore separato, vedi avvertenza sotto |
| **Link nelle note sui contatti** | i link nelle biografie restano cliccabili |
| **Quattro account** | gia' dalla 0.1.11 |
| **Traduzione di tutta la chat** | gia' dalla 0.1.11 |

Ogni voce ha il suo interruttore in **Impostazioni → Morsegram → Sblocchi locali**: se una
non ti piace la spegni senza perdere le altre.

## Cosa resta fuori, e perche'

Trentuno gruppi di funzioni le decide il **server**: caricamenti oltre 2 GB, velocita' di
scarico, trascrizione lato server, assenza di pubblicita', reazioni e nomi utente in piu'.
Nessun fork le sblocca, per quanto prometta: il client chiede e il server rifiuta.

Altre sei erano tecnicamente sbloccabili ma sono state lasciate fuori di proposito:

- **Icone alternative dell'app**: sono grafiche Telegram, finirebbero sulla tua schermata
  iniziale addosso a un'app che non e' Telegram.
- **Qualita' piena delle storie, posizione della scheda, canali simili** e altre: portano a
  operazioni che il server poi rifiuta, o i censimenti non concordavano sulla classificazione.

Il criterio e' sempre lo stesso: **una funzione che si accende e poi da' errore e' peggio di
una funzione assente**.

## Edizione per Google Play

Gli sblocchi non ci sono, e la verifica e' stata fatta sugli artefatti e non sui sorgenti:
nessuna delle chiavi e nessuno dei testi compare nell'APK ne' nel bundle. Resta, nel deposito
delle stringhe, il solo prefisso comune "MorsegramUnlock" che il compilatore mette in comune:
quindici caratteri senza alcuna funzione, non un pezzo di codice attivo. Lo scriviamo invece
di dichiarare uno zero che non sarebbe esatto.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
