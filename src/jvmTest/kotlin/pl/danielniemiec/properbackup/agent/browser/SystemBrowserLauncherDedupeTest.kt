package pl.danielniemiec.properbackup.agent.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SystemBrowserLauncherDedupeTest {

    @Test
    fun panelOpenDedupeKeyTrims() {
        assertEquals("http://x/y", SystemBrowserLauncher.panelOpenDedupeKey("  http://x/y  "))
    }
}
