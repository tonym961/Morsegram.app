# Morsegram — inventario delle patch sull'upstream

Fork sottile di [DrKLO/Telegram](https://github.com/DrKLO/Telegram) (GPL-2.0-or-later). Base: tag `upstream/13.0.2-7175` (commit f32ffeec, 13/10/2026). Branch di lavoro: `morsegram`.

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

Non modificati di proposito: `GOOGLE_AUTH_CLIENT_ID` (vuoto farebbe crashare `requestIdToken` nel login con Google), `USE_CLOUD_STRINGS`, hash SMS (solo app ufficiali), account type `org.telegram.messenger` del sync adapter contatti (vedi TODO).

## Risorse nostre (nessun file upstream toccato)
- `TMessagesProj_App/src/main/res/values*/strings.xml` — `AppName=Morsegram`, `AppNameBeta=Morsegram Beta` per la locale di default e le 9 locale in cui upstream ridefinisce `AppName` (ar, de, it, es, ko, nl, pt-rBR, ru, uk). Il modulo app vince sulla libreria in fase di merge. Nota: `TMessagesProj_App/build.gradle` ha `localeFilters += ["zz"]`, quindi oggi le risorse localizzate Android vengono comunque scartate dall'APK (Telegram usa i propri language pack); i file per locale restano come assicurazione se upstream togliesse il filtro. I testi in-app che citano `AppName` possono arrivare dal language pack cloud di Telegram e dire "Telegram": accettabile (usiamo le API Telegram), la ToS 2.3 riguarda solo il titolo dell'app.
- `TMessagesProj_App/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` + `drawable/morsegram_icon_{bg,fg,mono}.xml` + `mipmap-*/ic_launcher*.png` — icona provvisoria "· —" scura su ambra `#F5A623`. Sostituisce l'icona adattiva, quella rotonda, la monocromatica e le PNG legacy (API 23-25). ToS 2.4: nessun aeroplanino, nessun blu Telegram.

## Segreti (mai nel repo)
`~/.gradle/gradle.properties`: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `MORSEGRAM_APP_ID`, `MORSEGRAM_APP_HASH`. Keystore di upload: `~/.morsegram/morsegram-upload.keystore` (alias `morsegram`, RSA 4096, 30 anni). Con Play App Signing questa è la chiave di **upload**: se si perde si chiede il reset a Google, l'app non muore.

## Checklist di rebase (dopo ogni drop upstream)
1. `git rebase --onto upstream/<nuovo> upstream/<vecchio> morsegram`; ricontare il budget.
2. `grep -n "gms.car.application\|CarAppService\|minCarApiLevel\|AutoMessageHeardReceiver\|WearReplyReceiver" TMessagesProj/src/main/AndroidManifest.xml` — upstream ha toggolato questo blocco in 10.5.0, 10.6.1, 12.7.0, 12.8.0: se riattiva il CarAppService templated, i guard nel manifest app lo rimuovono; se ricompare un `gms.car.application` con resource diverso, vince il nostro `tools:replace`.
3. `grep -n "SEMANTIC_ACTION_MARK_AS_READ\|SEMANTIC_ACTION_REPLY\|setBridgeTag\|extend(wearableExtender)\|<morsegram>" TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java` — la riga 8 deve stare prima di `.extend(wearableExtender)`.
4. Build, poi sul manifest fuso (`TMessagesProj_App/build/intermediates/merged_manifests/<variant>/*/AndroidManifest.xml`): esattamente 1 `gms.car.application` → `morsegram_auto_desc`, 0 `CarAppService`, 0 `minCarApiLevel`, `AutoMessageHeardReceiver` e `WearReplyReceiver` presenti. Per l'AAB: `bundletool dump manifest --bundle=<aab>` con gli stessi attesi.
5. Smoke test: Desktop Head Unit (messaggio 1:1 e di gruppo letti, risposta vocale, segna come letto) + orologio Wear (Rispondi + Segna come letto, dismiss sincronizzato).

## Build
```bash
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a   # debug, solo arm64
./gradlew :TMessagesProj_App:bundleAfatRelease                                           # AAB per Play (tutte le ABI)
```

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
