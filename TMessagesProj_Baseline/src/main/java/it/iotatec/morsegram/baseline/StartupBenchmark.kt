package it.iotatec.morsegram.baseline

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start (TTID = timeToInitialDisplayMs, TTFD = timeToFullDisplayMs via LaunchActivity.reportFullyDrawn di StartupReporter)
 * sulla variante afatBenchmarkRelease (R8 come release, profileable):
 *   ../wear-build.sh :TMessagesProj_Baseline:connectedAfatBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.iotatec.morsegram.baseline.StartupBenchmark
 * Solo su device fisico (su emulatore il benchmark rifiuta di girare: misure non significative).
 * `coldStartBaselineProfile` richiede che l'APK contenga gia' il profilo (BaselineProfileMode.Require): generare prima.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Nessun profilo (JIT puro): il peggiore, equivale a un'installazione adb appena fatta senza dexopt. */
    @Test
    fun coldStartNoProfile() = coldStart(CompilationMode.None())

    /** Baseline profile dell'APK compilato con speed-profile: cio' che l'utente riceve da Play (DM) o da ProfileInstaller + bg-dexopt. */
    @Test
    fun coldStartBaselineProfile() = coldStart(CompilationMode.Partial(BaselineProfileMode.Require))

    /** AOT completo (speed): limite superiore teorico, non realistico in produzione. */
    @Test
    fun coldStartFullAot() = coldStart(CompilationMode.Full())

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = PKG,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForChatList()
    }
}
