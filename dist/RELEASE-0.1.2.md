# Morsegram 0.1.2

Correzioni trovate usando l'app, non leggendo il codice. Base: Telegram per Android 13.0.2 (7175).

## Chat nascoste: non possono piu' restare senza protezione

Nella 0.1.1 si poteva nascondere una chat senza aver impostato un PIN, e la sezione
si apriva senza chiedere nulla: una funzione di privacy che taceva proprio quando non
proteggeva. Ora:

- nascondendo la prima chat senza PIN, l'app lo dice e propone di impostarlo subito;
- nelle impostazioni la riga dice **Non protette** finche' un PIN non c'e';
- rimuovendo il PIN con chat nascoste esistenti, avverte e chiede conferma;
- aprendo la sezione scoperta, avvisa una volta.

Resti libero di non mettere il PIN, ma sapendolo.

## Due falle di riservatezza chiuse

- **Widget.** Una chat nascosta poteva comparire nel widget della schermata iniziale,
  con nome e anteprima, leggibile senza sbloccare il telefono: il filtro non copriva
  il percorso dati dei widget. Ora copre anche quello, widget contatti compreso.
- **Conteggio.** Con il PIN impostato e la sezione bloccata, le impostazioni mostravano
  comunque quante chat nascoste esistevano. Il numero ora si vede solo da sbloccati.

## Archivio

Non era un difetto: nell'app ufficiale l'archivio non si nasconde da solo, va fatto una
volta con una pressione lunga sulla riga Archivio. Siccome e' poco scopribile, le
impostazioni Morsegram ora hanno un interruttore dedicato. Nota: se hai delle cartelle,
lo swipe sulla riga Archivio non fa nulla, perche' Telegram lo assegna al cambio
cartella finche' non scegli un gesto nelle impostazioni.

## File
| File | Cosa |
|---|---|
| `Morsegram-0.1.2-sideload.apk` | telefono, con aggiornamento automatico |
| `Morsegram-0.1.2-wear.apk` | orologio Wear OS 3+ |

Provato: installazione reale su emulatore e avvio senza errori. Non provato: tutto cio'
che sta dietro l'accesso a un account.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
