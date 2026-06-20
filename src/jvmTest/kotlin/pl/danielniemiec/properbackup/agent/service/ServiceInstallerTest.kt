package pl.danielniemiec.properbackup.agent.service

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ServiceInstallerTest {

    @Test
    fun `generateSystemdUnit contains correct fields`() {
        val unit = ServiceInstaller.generateSystemdUnit(
            Path.of("/opt/properbackup/properbackup-agent"),
            Path.of("/opt/properbackup/conf.d"),
        )
        assertContains(unit, "[Unit]")
        assertContains(unit, "[Service]")
        assertContains(unit, "[Install]")
        assertContains(unit, "Type=simple")
        assertContains(unit, "Restart=on-failure")
        assertContains(unit, "RestartSec=5")
        assertContains(unit, "WantedBy=multi-user.target")
        assertContains(unit, "After=network-online.target")
        assertContains(unit, "/opt/properbackup/properbackup-agent")
        assertContains(unit, "--headless")
    }

    @Test
    fun `generateSystemdUnit contains conf-dir and config flags`() {
        val unit = ServiceInstaller.generateSystemdUnit(
            Path.of("/usr/local/bin/properbackup-agent"),
            Path.of("/etc/properbackup"),
        )
        assertContains(unit, "--config /etc/properbackup/global.yml")
        assertContains(unit, "--conf-dir /etc/properbackup")
    }

    @Test
    fun `status returns a string`() {
        val status = ServiceInstaller.status()
        assertTrue(status.isNotEmpty())
    }
}
