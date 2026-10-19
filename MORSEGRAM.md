# Morsegram — inventario delle patch sull'upstream

Fork sottile di [DrKLO/Telegram](https://github.com/DrKLO/Telegram) (GPL-2.0-or-later). Base: tag `upstream/13.0.2-7175` (commit f32ffeec, 13/10/2026). Branch di lavoro: `morsegram`.

## Mission di prodotto (Tony, 19/10/2026)
1. **Velocissimo** — avvio e scorrimento senza attese; niente feed/commerce/AI a rallentare.
2. **Pin illimitati** — pinna tutte le chat che vuoi (pin locali, oltre il limite server di 5/10).
3. **Focus privacy** — zero telemetria propria, tutto on-device; MAI ghost mode / manomissione di spunte, online, 'sta scrivendo' (ToS 1.4).
4. **Chat nascoste** — chat protette da PIN + biometria, notifiche mascherate (niente mittente/testo) per quelle chat.
5. **Trascrizione vocali** — convertitore voce→testo on-device (Android SpeechRecognizer offline con audio da file su API 33+), senza Premium e senza server terzi.
6. **Super ottimizzato per i foldable** — due pannelli sullo schermo interno (lista + chat), continuità aprendo/chiudendo, postura tabletop/book (Jetpack WindowManager), cerniera rispettata.
7. **Funziona anche su Wear OS (app propria, TDLib) e Android Auto** — vedi ../WEAR-AUTO.md.
8. **Modalità aereo in-app** — un toggle che sospende la connettività dell'app (niente rete, niente notifiche) senza toccare quella del telefono; al ritorno online le letture in sospeso si sincronizzano come in upstream. NON è un ghost mode: non sopprime né ritarda artificialmente le spunte (ToS 1.4).

Ogni mission è una feature cliente-side compatibile con i Termini API; il costo vero è il perimetro di patch sui file caldi (DialogsActivity, ChatActivity, LaunchActivity, NotificationsController): va misurato a ogni rebase.

## Regola del perimetro
- Ogni modifica a un file upstream è racchiusa tra marcatori `<morsegram> … </morsegram>` (commento del linguaggio del file), così resta greppabile dopo un drop upstream di 300 file.
- Budget v1: **≤ 20 file upstream / ≤ 150 righe cambiate** (`git diff --stat upstream/13.0.2-7175 -- . ':!TMessagesProj_App/src/main/res' ':!MORSEGRAM.md'`). Oltre 50 file o 800 righe si tolgono feature, non si spinge.
- Tutto ciò che è nostro e nuovo vive fuori dai file upstream: risorse in `TMessagesProj_App/src/main/res/`, codice futuro in `it.iotatec.morsegram`.
- Rebase: `git rebase --onto upstream/<nuovo> upstream/<vecchio> morsegram`, poi ricontare il budget.

## Patch v1 (fase 1 — identità)
| # | File upstream | Cosa | Perché |
|---|---|---|---|
| 1 | `gradle.properties` | `APP_PACKAGE=it.iotatec.morsegram` | applicationId proprio (debug: `.beta`) |
| 2 | `TMessagesProj_App/build.gradle` (2 righe) | `storeFile` da `RELEASE_STORE_FILE` in `~/.gradle/gradle.properties`, fallback al keystore dummy upstream | keystore e password fuori dal repo pubblico |
| 3 | `settings.gradle` | tolti `AppHuawei`, `AppHockeyApp`, `AppStandalone` | non servono a Play; meno config |
| 4 | `TMessagesProj/build.gradle` (defaultConfig) | `buildConfigField APP_ID/APP_HASH` da `MORSEGRAM_APP_ID`/`MORSEGRAM_APP_HASH` (proprietà Gradle utente), default = valori di test upstream | api_id segreto fuori dal repo; ToS 2.1 |
| 5 | `BuildVars.java` | `APP_ID/APP_HASH = BuildConfig.*`; `CHECK_UPDATES=false`; `SAFETYNET_KEY=""`; `PLAYSTORE_APP_URL` nostro; `IS_BILLING_UNAVAILABLE=true`; `SUPPORTS_PASSKEYS=false` | fork: niente update check ufficiali, niente SafetyNet con chiave altrui, zero IAP (store Premium/Stars non disponibili ai terzi), passkey da verificare |
| 7 | `TMessagesProj_App/src/main/AndroidManifest.xml` (+7 righe) | meta-data `com.google.android.gms.car.application` → `@xml/morsegram_auto_desc` (nostro, solo `notification`) + guard `tools:node="remove"` su `TelegramCarAppService` e `minCarApiLevel` | Android Auto via notifiche (unico percorso GA su Play); upstream tiene la sua dichiarazione commentata dal 12.7.0; il templated (Car App Library) è labs e respinto in produzione — vedi ../WEAR-AUTO.md |
| 8 | `NotificationsController.java` (+1 riga) | `wearableExtender.addAction(readAction)` | "Segna come letto" anche sull'orologio Wear OS (DrKLO PR #2196) |
| 6 | `TMessagesProj/google-services.json`, `TMessagesProj_App/google-services.json` | placeholder con i package Morsegram (+ org.telegram.messenger per il namespace della libreria) | il plugin google-services pretende un client con il package; **da sostituire col file Firebase reale** |
| 9 | `settings.gradle` (+7 righe) | `include ':TMessagesProj_CarLabs'` solo se la proprietà Gradle `MORSEGRAM_CAR_LABS` è presente e ≠ `false` | Android Auto labs opt-in: il modulo templated non entra mai in una build normale — vedi "Android Auto labs" |
| 10 | `TMessagesProj_App/build.gradle` (+17 righe) | `morsegramCarLabs = findProject(':TMessagesProj_CarLabs') != null` → `implementation project(':TMessagesProj_CarLabs')` e `manifestPlaceholders[morsegramAutoDesc]` = `morsegram_auto_desc` / `morsegram_auto_desc_labs` | unica fonte di verità = settings.gradle; il descriptor con `template` esce solo insieme al modulo |
| 11 | `TMessagesProj_App/src/main/AndroidManifest.xml` (2 righe modificate, +7 righe di commento) | resource `@xml/${morsegramAutoDesc}`; guard `minCarApiLevel` con nome `${morsegramMinCarApiLevelGuard}` | il merger abbina il `remove` per nome: senza placeholder il guard cancellerebbe anche il `minCarApiLevel=7` del modulo labs (`tools:selector` scartato: lascia un meta-data vuoto nel manifest fuso) |

Non modificati di proposito: `GOOGLE_AUTH_CLIENT_ID` (vuoto farebbe crashare `requestIdToken` nel login con Google), `USE_CLOUD_STRINGS`, hash SMS (solo app ufficiali), account type `org.telegram.messenger` del sync adapter contatti (vedi TODO).

## Risorse nostre (nessun file upstream toccato)
- `TMessagesProj_App/src/main/res/values*/strings.xml` — `AppName=Morsegram`, `AppNameBeta=Morsegram Beta` per la locale di default e le 9 locale in cui upstream ridefinisce `AppName` (ar, de, it, es, ko, nl, pt-rBR, ru, uk). Il modulo app vince sulla libreria in fase di merge. Nota: `TMessagesProj_App/build.gradle` ha `localeFilters += ["zz"]`, quindi oggi le risorse localizzate Android vengono comunque scartate dall'APK (Telegram usa i propri language pack); i file per locale restano come assicurazione se upstream togliesse il filtro. I testi in-app che citano `AppName` possono arrivare dal language pack cloud di Telegram e dire "Telegram": accettabile (usiamo le API Telegram), la ToS 2.3 riguarda solo il titolo dell'app.
- `TMessagesProj_App/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` + `drawable/morsegram_icon_{bg,fg,mono}.xml` + `mipmap-*/ic_launcher*.png` — icona provvisoria "· —" scura su ambra `#F5A623`. Sostituisce l'icona adattiva, quella rotonda, la monocromatica e le PNG legacy (API 23-25). ToS 2.4: nessun aeroplanino, nessun blu Telegram.
- `TMessagesProj_App/src/main/res/xml/morsegram_auto_desc_labs.xml` — `notification` + `template`; finisce nel manifest solo con `-PMORSEGRAM_CAR_LABS=true` (vedi "Android Auto labs").
- `TMessagesProj_CarLabs/` — modulo Gradle opt-in (namespace `it.iotatec.morsegram.carlabs`): `MorsegramCarAppService`, `MorsegramCarSession`, `HomeScreen`, `MusicSongsScreen` copiati dal package `car/` upstream e portati ad `androidx.car.app` 1.7.0; manifest con il service MESSAGING e `minCarApiLevel 7`.

## Segreti (mai nel repo)
`~/.gradle/gradle.properties`: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `MORSEGRAM_APP_ID`, `MORSEGRAM_APP_HASH`. Keystore di upload: `~/.morsegram/morsegram-upload.keystore` (alias `morsegram`, RSA 4096, 30 anni). Con Play App Signing questa è la chiave di **upload**: se si perde si chiede il reset a Google, l'app non muore.

## Checklist di rebase (dopo ogni drop upstream)
1. `git rebase --onto upstream/<nuovo> upstream/<vecchio> morsegram`; ricontare il budget.
2. `grep -n "gms.car.application\|CarAppService\|minCarApiLevel\|AutoMessageHeardReceiver\|WearReplyReceiver" TMessagesProj/src/main/AndroidManifest.xml` — upstream ha toggolato questo blocco in 10.5.0, 10.6.1, 12.7.0, 12.8.0: se riattiva il CarAppService templated, i guard nel manifest app lo rimuovono; se ricompare un `gms.car.application` con resource diverso, vince il nostro `tools:replace`.
3. `grep -n "SEMANTIC_ACTION_MARK_AS_READ\|SEMANTIC_ACTION_REPLY\|setBridgeTag\|extend(wearableExtender)\|<morsegram>" TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java` — la riga 8 deve stare prima di `.extend(wearableExtender)`.
4. Build, poi sul manifest fuso (`TMessagesProj_App/build/intermediates/merged_manifests/<variant>/*/AndroidManifest.xml`): esattamente 1 `gms.car.application` → `morsegram_auto_desc`, 0 `CarAppService`, 0 `minCarApiLevel`, `AutoMessageHeardReceiver` e `WearReplyReceiver` presenti. Per l'AAB: `bundletool dump manifest --bundle=<aab>` con gli stessi attesi. (Solo build labs `-PMORSEGRAM_CAR_LABS=true`: 1 `CarAppService` = `it.iotatec.morsegram.carlabs.car.MorsegramCarAppService`, `minCarApiLevel` 7, `morsegram_auto_desc_labs`.)
5. Smoke test: Desktop Head Unit (messaggio 1:1 e di gruppo letti, risposta vocale, segna come letto) + orologio Wear (Rispondi + Segna come letto, dismiss sincronizzato).

## Build
```bash
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a   # debug, solo arm64
./gradlew :TMessagesProj_App:bundleAfatRelease                                           # AAB per Play (tutte le ABI)
```

## Android Auto labs (Car App Library "templated messaging", opt-in)
Google tiene il templated messaging (`CarAppService` con categoria `androidx.car.app.category.MESSAGING`, `ConversationItem`, schede Notifiche/Musica) in beta/"labs": Play lo accetta solo su **internal/closed testing** e respinge in open testing/produzione le app che dichiarano `<uses name="template"/>` + `CarAppService` MESSAGING. Per questo vive nel modulo opt-in `TMessagesProj_CarLabs/` e **non entra mai in una build normale**: l'opt-in è una proprietà Gradle da riga di comando, non va in `gradle.properties` del repo e CI/release non la passano.

- Modulo: `com.android.library`, namespace `it.iotatec.morsegram.carlabs`, dipende da `:TMessagesProj` + `androidx.car.app:app` 1.7.0 + `app-projected` 1.7.0 + `androidx.media` 1.7.0 (per `MediaControllerCompat`). `TMessagesProj/build.gradle` resta a car.app 1.4.0: Gradle risolve al più alto, in debug coesistono il package `car/` upstream (non referenziato) e il nostro, in release R8 elimina quello upstream.
- Differenze dal codice upstream (`org.telegram.messenger.car`): ogni `Person` ha `key` non nulla (1.7.0 valida `ConversationItem`/`CarMessage`: name+key obbligatori, altrimenti il template viene rifiutato), `ConversationItem.Builder` a 5 argomenti (il no-arg è deprecato), titolo/back via `Header` (`setTitle`/`setHeaderAction` deprecati, `setHeader` richiede Car API 7), `HostValidator` con allowlist `hosts_allowlist_sample` (in debug tutti gli host, per il Desktop Head Unit). Il manifest del modulo dichiara anche `FOREGROUND_SERVICE_CONNECTED_DEVICE` perché il service porta `foregroundServiceType="connectedDevice"` come upstream.

```bash
# build normale (= produzione): nessun CarAppService, descriptor solo notification
./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a
# build labs (solo internal/closed testing, Desktop Head Unit): CarAppService MESSAGING + minCarApiLevel 7 + notification+template
./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a -PMORSEGRAM_CAR_LABS=true
```
Verifica sul manifest fuso (`TMessagesProj_App/build/intermediates/merged_manifests/afatDebug/processAfatDebugManifest/AndroidManifest.xml`): normale → 0 `CarAppService`, 0 `minCarApiLevel`, `@xml/morsegram_auto_desc`; labs → 1 `CarAppService` (`it.iotatec.morsegram.carlabs.car.MorsegramCarAppService`), `minCarApiLevel` 7, `@xml/morsegram_auto_desc_labs`. Le due build scrivono nello stesso `build/`: dopo una build labs rifare quella normale prima di qualunque upload.

## TODO (fasi successive)
- [ ] Sostituire `google-services.json` con quello del progetto Firebase Morsegram e registrare la chiave FCM su my.telegram.org/apps.
- [ ] `MORSEGRAM_APP_ID/HASH` reali in `~/.gradle/gradle.properties` (finché mancano, il login usa l'api_id di test upstream: solo per prove).
- [ ] Gate: nascondere il selettore "Icona app" (le icone alternative upstream sono aeroplanini) — ToS 2.4.
- [ ] Schermata di primo avvio con dichiarazione "client non ufficiale che usa le API Telegram" (ToS 2.2) + accettazione termini (Play UGC).
- [ ] Account type del sync adapter contatti: resta `org.telegram.messenger`; con l'app ufficiale installata la sincronizzazione contatti nel sistema può fallire silenziosamente. Valutare `${applicationId}` (authenticator.xml, sync xml, ContactsController).
- [ ] Testare: chiamata vocale, push, sponsored message (t.me/SecretAdTestChannel), passkey col nostro api_id.
- [ ] Android Auto: test su Desktop Head Unit con build debug (toggle "Origini sconosciute" in Android Auto › impostazioni sviluppatore), poi da Play internal testing; opt-in form factor Android Auto solo in closed testing (review non bloccante). Vedi ../WEAR-AUTO.md.
- [ ] Wear OS: v1 = notifiche bridged (nessun form factor Wear, nessuno screenshot orologio). Gate app watch ~aprile 2027 (criteri in ../WEAR-AUTO.md).
- [ ] CI: build solo da tag pubblico, assert `APP_ID != 4`, allineamento 16 KB, `targetSdk == 36`.
