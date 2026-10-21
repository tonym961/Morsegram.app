<p align="center">
  <img src="docs/img/banner.png" alt="Morsegram" width="100%">
</p>

<p align="center">
  <b>Un client Telegram per Android fatto a modo nostro.</b><br>
  Veloce, riservato, senza niente da pagare.
</p>

---

## Scarica

Gli APK si trovano nelle [**Release**](../../releases). Prendi l'ultima e installala:
Morsegram si affianca a Telegram ufficiale, non lo sostituisce e non tocca i suoi dati.

| File | Per cosa |
|---|---|
| `Morsegram-x.y.z-sideload.apk` | telefono e tablet, Android 6 o successivo, arm64 |
| `Morsegram-x.y.z-wear.apk` | orologio Wear OS 3 o successivo |

### Aggiornamenti automatici con Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) tiene aggiornate le app installate fuori
dallo store. Aggiungi questo repository come sorgente e ti avvisera' a ogni nuova release:

```
https://github.com/tonym961/Morsegram.app
```

In alternativa Morsegram sa aggiornarsi da solo: in **Impostazioni → Morsegram → Aggiornamenti**
puoi indicare una chat Telegram (di default i tuoi Messaggi salvati) dove trovare le versioni
nuove. Prima di installare qualunque file, l'app verifica che sia firmato con la stessa chiave,
che il nome pacchetto coincida e che la versione sia superiore.

## Cosa fa di diverso

| | |
|---|---|
| **Modalità aereo interna** | Sospende solo Morsegram: il telefono resta connesso, le chat tacciono. Al rientro recupera tutto. |
| **Pin illimitati** | Oltre il limite del server: i pin in più restano su questo dispositivo. |
| **Chat nascoste** | Protette da PIN e impronta, invisibili in lista, ricerca e condivisione. Notifiche mascherate. |
| **Trascrizione dei vocali** | Sul dispositivo, senza abbonamento e senza mandare l'audio a nessuno. |
| **Avvio alleggerito** | Storie, regali, Stars, portafoglio e funzioni AI non vengono caricate. |
| **Pieghevoli** | Due pannelli sullo schermo interno, divisore sulla piega, posizione conservata aprendo e chiudendo. |
| **Wear OS** | App completa: chat, media, invio e ascolto di messaggi vocali, risposta dal polso. |
| **Android Auto** | Scelta della chat dal display dell'auto e ascolto dei vocali. |

## Privacy

Nessuna raccolta dati, nessuna analisi d'uso, nessun servizio di tracciamento. Firebase e' usato
soltanto per ricevere le notifiche, e si puo' spegnere: in **Impostazioni → Notifiche** la
*connessione in background* fa arrivare i messaggi senza passare da Google.

## Avvertenze

Morsegram e' un client **non ufficiale** e non e' affiliato a Telegram FZ-LLC. Secondo le regole
di Telegram, accedere da un client non ufficiale puo' mettere l'account sotto osservazione.
L'app non nasconde la conferma di lettura ne' rimuove i messaggi sponsorizzati, perche' i termini
delle API lo vietano.

## Licenza e sorgente

Software libero sotto **GNU GPL versione 2 o successiva**, come l'app ufficiale di Telegram da cui
deriva. Il codice sorgente corrispondente a ogni APK pubblicato e' questo stesso repository, al tag
della versione.

## Compilare

```bash
git clone --recurse-submodules https://github.com/tonym961/Morsegram.app.git
cd Morsegram.app
```

Servono JDK 21, Android SDK 36, NDK 27 e CMake. Le credenziali dell'API Telegram non sono nel
repository: metti le tue in `~/.gradle/gradle.properties` come `MORSEGRAM_APP_ID` e
`MORSEGRAM_APP_HASH` (si ottengono su [my.telegram.org](https://my.telegram.org)). Senza, la build
usa le credenziali di prova pubbliche di Telegram, che i server limitano.

```bash
# APK del telefono, con aggiornamento in-app
./gradlew :TMessagesProj_App:assembleAfatRelease -PMORSEGRAM_ABI=arm64-v8a -PMORSEGRAM_SIDELOAD
# APK dell'orologio
./gradlew :TMessagesProj_Wear:assembleRelease
```

Gli artefatti buoni finiscono in `build/outputs/`. Prima di distribuirne uno:
`tools/check-artifact.sh <file>`.

Che cosa e' stato cambiato rispetto a Telegram, file per file e riga per riga, e' documentato in
[MORSEGRAM.md](MORSEGRAM.md). Le istruzioni di compilazione originali di Telegram sono in
[BUILD-upstream.md](BUILD-upstream.md).
