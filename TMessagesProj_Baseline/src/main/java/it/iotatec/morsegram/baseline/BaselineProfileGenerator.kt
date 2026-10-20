package it.iotatec.morsegram.baseline

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genera baseline-prof.txt e startup-prof.txt dell'app (variante afatNonMinifiedRelease, package it.iotatec.morsegram):
 *   ../wear-build.sh :TMessagesProj_App:generateBaselineProfile -Pandroid.injected.build.abi=arm64-v8a                     # device adb (Z Fold, API >= 33)
 *   ../wear-build.sh :TMessagesProj_App:generateBaselineProfile -Pandroid.injected.build.abi=arm64-v8a -PMORSEGRAM_GMD=true  # Gradle Managed Device
 * (con mergeIntoMain non esistono task per variante come generateAfatReleaseBaselineProfile.)
 * Output (committare): TMessagesProj_App/src/main/generated/baselineProfiles/{baseline-prof,startup-prof}.txt.
 * Il device deve avere un account gia' loggato (stessa firma: l'APK si installa sopra senza perdere i dati);
 * senza account il profilo copre solo avvio + schermata di login.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Startup profile: solo l'avvio a freddo fino alla lista chat (classi/metodi finiscono nel dex primario con dexLayoutOptimization). */
    @Test
    fun startup() = rule.collect(
        packageName = PKG,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        waitForChatList()
    }

    /** Baseline profile: percorsi caldi dopo l'avvio — scroll della lista chat, apertura della prima chat, scroll dei messaggi. */
    @Test
    fun chatListAndChat() = rule.collect(
        packageName = PKG,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        val list = waitForChatList() ?: return@collect
        flingList(list)
        openFirstChatAndScroll()
    }
}
