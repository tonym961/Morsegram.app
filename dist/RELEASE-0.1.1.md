# Morsegram 0.1.1

Basata su Telegram per Android 13.0.2 (7175). Pacchetto `it.morsegram.app`:
si installa accanto a Telegram ufficiale.

## Cosa cambia dalla 0.1.0

**L'app si installa davvero.** Gli artefatti della 0.1.0 erano marcati come build di
prova e il telefono li rifiutava con "applicazione non installata". Ora le
architetture si limitano nel modo corretto e ogni file viene verificato prima di
essere pubblicato con `tools/check-artifact.sh`, installazione reale compresa.

**Identità visiva propria.** La 0.1.0 all'avvio diceva "Telegram" con l'aeroplanino
blu: oltre a non essere personalizzata, violava i termini delle API di Telegram.
Ora c'è una palette ambra ricavata dal logo (#F0A020 su #101010), due temi completi
chiaro e scuro applicati al primo avvio, logo e schermata iniziale nostri, e il nome
dell'app corretto nei dialoghi di sistema, in qualunque lingua. Tutti i temi di
Telegram restano disponibili e una scelta dell'utente non viene mai sovrascritta.

**Notifiche.** Firebase configurato sul progetto dell'autore e credenziali push
registrate su my.telegram.org: i server di Telegram possono svegliare l'app.

**Aggiornamento automatico** nella sola versione sideload: cerca in una chat Telegram
(di default i Messaggi salvati) un APK con versione superiore e lo installa solo dopo
aver verificato firma, nome pacchetto e versione. La versione per Google Play non ha
l'updater, perché lo Store vieta alle app di aggiornarsi da sole.

## Cosa c'era già
Modalità aereo interna, pin illimitati, chat nascoste con PIN e impronta,
trascrizione dei vocali sul dispositivo, avvio alleggerito, due pannelli sui
pieghevoli, app completa per Wear OS con invio e ascolto di vocali, e Android Auto
per scegliere la chat e ascoltare i messaggi.

## Limiti noti
- Nulla è stato provato su hardware vero: finora solo emulatore.
- Solo architettura arm64: per i dispositivi a 32 bit serve una build senza
  `-PMORSEGRAM_ABI`.
- Accedere da un client non ufficiale mette l'account sotto osservazione secondo le
  regole di Telegram.

## File
| File | Cosa |
|---|---|
| `Morsegram-0.1.1-sideload.apk` | telefono, con aggiornamento automatico |
| `Morsegram-0.1.1-wear.apk` | orologio Wear OS 3+ |
| `Morsegram-0.1.1-playstore.aab` | pacchetto per Google Play |

Licenza GNU GPL v2 o successiva. Morsegram non è affiliato a Telegram FZ-LLC.
