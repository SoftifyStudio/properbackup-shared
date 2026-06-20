package pl.danielniemiec.properbackup.agent.service

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Generates and manages OS-level service definitions so the ProperBackup agent
 * starts automatically on boot and restarts on failure (RestartSec=5).
 *
 * Detects sudo availability:
 *  - root/sudo → auto-install to /etc/systemd/system/
 *  - non-root  → prints the manual install command
 */
object ServiceInstaller {

    private const val SERVICE_NAME = "properbackup-agent"
    private const val SERVICE_LABEL = "com.properbackup.agent"

    data class InstallResult(
        val success: Boolean,
        val message: String,
        val unitPath: String? = null,
        val manualCommand: String? = null,
    )

    fun install(agentBinary: Path, configDir: Path): InstallResult {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") -> installSystemd(agentBinary, configDir)
            os.contains("mac") || os.contains("darwin") -> installLaunchd(agentBinary, configDir)
            else -> InstallResult(false, "Unsupported OS: ${System.getProperty("os.name")}")
        }
    }

    fun uninstall(): InstallResult {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") -> uninstallSystemd()
            os.contains("mac") || os.contains("darwin") -> uninstallLaunchd()
            else -> InstallResult(false, "Unsupported OS: ${System.getProperty("os.name")}")
        }
    }

    fun status(): String {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") -> statusSystemd()
            os.contains("mac") || os.contains("darwin") -> statusLaunchd()
            else -> "Unsupported OS"
        }
    }

    // ── systemd (Linux) ──

    private fun systemdUnitPath(): Path = Path.of("/etc/systemd/system/$SERVICE_NAME.service")

    fun hasSudo(): Boolean {
        val isRoot = System.getProperty("user.name") == "root"
        if (isRoot) return true
        return try {
            val p = ProcessBuilder("sudo", "-n", "true")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun generateSystemdUnit(agentBinary: Path, configDir: Path): String {
        val binAbsolute = agentBinary.toAbsolutePath().normalize()
        val confAbsolute = configDir.toAbsolutePath().normalize()
        val user = System.getProperty("user.name") ?: "root"

        return buildString {
            appendLine("[Unit]")
            appendLine("Description=ProperBackup Agent — encrypted backup service")
            appendLine("After=network-online.target")
            appendLine("Wants=network-online.target")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=simple")
            appendLine("User=$user")
            appendLine("WorkingDirectory=${confAbsolute.parent ?: confAbsolute}")
            appendLine("ExecStart=$binAbsolute --headless --config $confAbsolute/global.yml --conf-dir $confAbsolute")
            appendLine("Restart=on-failure")
            appendLine("RestartSec=5")
            appendLine("StandardOutput=journal")
            appendLine("StandardError=journal")
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=multi-user.target")
        }
    }

    private fun installSystemd(agentBinary: Path, configDir: Path): InstallResult {
        val unitContent = generateSystemdUnit(agentBinary, configDir)
        val unitPath = systemdUnitPath()

        if (!hasSudo()) {
            val manualCmd = buildString {
                appendLine("sudo tee $unitPath > /dev/null << 'EOF'")
                append(unitContent)
                appendLine("EOF")
                appendLine("sudo systemctl daemon-reload")
                appendLine("sudo systemctl enable --now $SERVICE_NAME")
            }
            return InstallResult(
                success = false,
                message = "Root access required. Run: sudo properbackup install-service\n\nOr manually:\n$manualCmd",
                manualCommand = manualCmd,
            )
        }

        return try {
            Files.createDirectories(unitPath.parent)
            Files.writeString(unitPath, unitContent)

            exec("systemctl", "daemon-reload")
            exec("systemctl", "enable", SERVICE_NAME)
            exec("systemctl", "start", SERVICE_NAME)

            InstallResult(true, "Service installed and started. Auto-starts on boot, auto-restarts on crash.", unitPath.toString())
        } catch (e: Exception) {
            InstallResult(false, "Failed to install systemd service: ${e.message}", unitPath.toString())
        }
    }

    private fun uninstallSystemd(): InstallResult {
        return try {
            exec("systemctl", "stop", SERVICE_NAME)
            exec("systemctl", "disable", SERVICE_NAME)
            Files.deleteIfExists(systemdUnitPath())
            exec("systemctl", "daemon-reload")
            InstallResult(true, "Service removed.")
        } catch (e: Exception) {
            InstallResult(false, "Failed to uninstall: ${e.message}")
        }
    }

    private fun statusSystemd(): String {
        return try {
            val p = ProcessBuilder("systemctl", "is-active", SERVICE_NAME)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            output
        } catch (_: Exception) {
            "unknown"
        }
    }

    // ── launchd (macOS) ──

    private fun launchdPlistPath(): Path {
        val home = System.getProperty("user.home") ?: "/Users/unknown"
        return Path.of("$home/Library/LaunchAgents/$SERVICE_LABEL.plist")
    }

    private fun installLaunchd(agentBinary: Path, configDir: Path): InstallResult {
        val binAbsolute = agentBinary.toAbsolutePath().normalize()
        val confAbsolute = configDir.toAbsolutePath().normalize()
        val logDir = Path.of(System.getProperty("user.home"), "Library/Logs/ProperBackup")

        val plist = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
            appendLine("""<plist version="1.0">""")
            appendLine("<dict>")
            appendLine("  <key>Label</key><string>$SERVICE_LABEL</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            appendLine("    <string>$binAbsolute</string>")
            appendLine("    <string>--headless</string>")
            appendLine("    <string>--config</string>")
            appendLine("    <string>$confAbsolute/global.yml</string>")
            appendLine("    <string>--conf-dir</string>")
            appendLine("    <string>$confAbsolute</string>")
            appendLine("  </array>")
            appendLine("  <key>RunAtLoad</key><true/>")
            appendLine("  <key>KeepAlive</key>")
            appendLine("  <dict><key>SuccessfulExit</key><false/></dict>")
            appendLine("  <key>ThrottleInterval</key><integer>5</integer>")
            appendLine("  <key>StandardOutPath</key><string>$logDir/agent.log</string>")
            appendLine("  <key>StandardErrorPath</key><string>$logDir/agent-error.log</string>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

        val plistPath = launchdPlistPath()
        return try {
            Files.createDirectories(plistPath.parent)
            Files.createDirectories(logDir)
            Files.writeString(plistPath, plist)
            Runtime.getRuntime().exec(arrayOf("launchctl", "load", "-w", plistPath.toString())).waitFor()
            InstallResult(true, "LaunchAgent installed and loaded.", plistPath.toString())
        } catch (e: Exception) {
            InstallResult(false, "Failed to install launchd agent: ${e.message}", plistPath.toString())
        }
    }

    private fun uninstallLaunchd(): InstallResult {
        val plistPath = launchdPlistPath()
        return try {
            if (Files.exists(plistPath)) {
                Runtime.getRuntime().exec(arrayOf("launchctl", "unload", "-w", plistPath.toString())).waitFor()
                Files.deleteIfExists(plistPath)
            }
            InstallResult(true, "LaunchAgent removed.")
        } catch (e: Exception) {
            InstallResult(false, "Failed to uninstall: ${e.message}")
        }
    }

    private fun statusLaunchd(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("launchctl", "list", SERVICE_LABEL))
            p.waitFor()
            if (p.exitValue() == 0) "running" else "not loaded"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun exec(vararg cmd: String) {
        val p = ProcessBuilder(*cmd)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
        val exit = p.waitFor()
        if (exit != 0) throw RuntimeException("Command '${cmd.joinToString(" ")}' exited with $exit")
    }
}
