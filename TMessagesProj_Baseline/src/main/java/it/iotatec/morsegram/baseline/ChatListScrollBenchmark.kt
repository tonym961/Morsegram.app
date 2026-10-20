package it.iotatec.morsegram.baseline

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jank sullo scroll (frameDurationCpuMs P50/P90/P95/P99 + frameOverrunMs) della lista chat e della prima chat:
 *   ../wear-build.sh :TMessagesProj_Baseline:connectedAfatBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.iotatec.morsegram.baseline.ChatListScrollBenchmark
 * Stessi gesti del generatore (flingList / openFirstChatAndScroll), cosi' il profilo copre esattamente cio' che si misura.
 */
@RunWith(AndroidJUnit4::class)
class ChatListScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollNoProfile() = scroll(CompilationMode.None())

    @Test
    fun scrollBaselineProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = PKG,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForChatList()
        },
    ) {
        val list = waitForChatList() ?: return@measureRepeated
        flingList(list)
        openFirstChatAndScroll()
    }
}
