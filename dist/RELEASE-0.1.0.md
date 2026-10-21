# Morsegram 0.1.0

Prima build di prova, basata su Telegram per Android 13.0.2 (7175).
Pacchetto `it.morsegram.app`: si installa **accanto** a Telegram ufficiale.

## Cosa c'è

**Telefono**
- Modalità aereo interna (impostazioni + riquadro nelle impostazioni rapide): sospende solo Morsegram, il telefono resta connesso. Le letture in sospeso si sincronizzano al rientro.
- Pin illimitati: oltre il limite del server, i pin extra restano su questo dispositivo.
- Chat nascoste con PIN e impronta, invisibili in lista, ricerca e condivisione; notifiche mascherate.
- Trascrizione dei vocali sul dispositivo (Android 13+), senza Premium e senza inviare l'audio.
- Avvio più rapido: niente Storie, regali, Stars, wallet e AI caricati all'avvio.
- Pieghevoli: due pannelli sullo schermo interno, divisore sulla piega, chat e posizione conservate aprendo e chiudendo.

**Wear OS** (APK separato, stesso pacchetto e stessa firma)
- App completa: lista chat, chat, foto e media, **ascolto e invio di messaggi vocali**, risposta rapida, notifiche con risposta dal polso, modalità aereo.
- Accesso dall'orologio con codice QR oppure dal telefono Morsegram.

**Android Auto**
- Scelta della chat dal display dell'auto e ascolto dei vocali; l'assistente legge i messaggi e si risponde a voce.
- La variante con registrazione vocale dal microfono dell'auto esiste ma Google la ammette solo in test chiuso.

## Aggiornamento automatico (solo APK sideload)
Impostazioni → Morsegram → Aggiornamenti: controlla una volta al giorno (mai su rete a consumo) la chat di origine, di default i Messaggi salvati. Per pubblicare una nuova versione si invia l'APK in quella chat con la versione nel nome del file. L'installazione avviene solo se firma, nome pacchetto e versione superiore sono verificati. La build per Google Play non ha l'updater: lo Store vieta alle app di aggiornarsi da sole.

## Limiti noti di questa build
- **Notifiche non attive**: manca ancora la configurazione Firebase/FCM.
- Niente di tutto questo è stato provato su un telefono vero: finora solo build verdi ed emulatore Wear OS.
- La trascrizione dipende dal riconoscimento vocale offline del dispositivo: se Samsung non lo espone, la funzione si disattiva da sola.
- Accedere da un client non ufficiale mette l'account "sotto osservazione" secondo le regole di Telegram.

## File
| File | Cosa | Dove si installa |
|---|---|---|
| `Morsegram-0.1.0-sideload.apk` | app telefono (release, arm64) con aggiornamento automatico | installazione diretta |
| `Morsegram-0.1.0-wear.apk` | app orologio (release) | Wear OS 3+ |
| `Morsegram-0.1.0-playstore.aab` | pacchetto per Google Play | Play Console |

Licenza GNU GPL v2 o successiva. Morsegram non è affiliato a Telegram FZ-LLC.
