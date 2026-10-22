# Morsegram 0.1.6

Due correzioni che si vedono, e una che toglie un ostacolo alla pubblicazione.

## La barra in basso, davvero

Nella 0.1.4 l'avevo resa opaca, ma il colore che il tema le assegnava era **lo stesso
marrone**: togliere la trasparenza non cambiava niente a vedersi, e infatti non cambiava.
Ora il fondo e' **grigio neutro**, un gradino sopra lo sfondo cosi' resta distinguibile, con
l'**ambra solo sulla voce selezionata**. Il testo della voce scelta passa da 3,27 a 7,21 di
contrasto.

## Testi leggibili ovunque

Misurato il contrasto di tutte le coppie testo-fondo dei due temi. Il tema chiaro ne aveva
**otto sotto la soglia di leggibilita'**, la peggiore era l'ora sui messaggi inviati a 2,09
contro i 4,5 richiesti: praticamente invisibile. Corrette scurendo il minimo necessario, senza
toccare le tinte. Il generatore dei temi ora applica e verifica il contrasto da solo, quindi il
difetto non puo' tornare dalla porta di servizio.

## Un permesso in meno

Tolto `READ_CALL_LOG`, che serviva solo a leggere da se' il codice di verifica arrivato per
chiamata. Google lo considera sensibile e pretende un modulo di dichiarazione con rischio di
sospensione, per una comodita' che vale due secondi di digitazione. Il codice ora si scrive a
mano, come in qualunque altra app.

## File

| File | Cosa |
|---|---|
| `Morsegram-0.1.6-sideload.apk` | telefono, con aggiornamento automatico |
| `Morsegram-0.1.6-wear.apk` | orologio Wear OS 3+ |

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
