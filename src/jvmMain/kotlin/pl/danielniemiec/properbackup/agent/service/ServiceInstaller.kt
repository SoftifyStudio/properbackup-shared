package pl.danielniemiec.properbackup.agent.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale

/**
 * Generates and installs OS-level service definitions so the ProperBackup agent
 * starts automatically on boot and restarts on failure.
 *
 * Supported:
 * - Linux (systemd)
 * - macOS (launchd via LaunchAgent plist)
 */
object ServiceInstaller {

    private const val SERVICE_NAME = "properbackup-agent"
    private const val SERVICE_LABEL = "com.properbackup.agent"

    data class InstallResult(val success: Boolean, val message: String, val path: String?)

    fun install(agentJar: Path, configDir: Path, javaPath: String? = null): InstallResult {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") -> installSystemd(agentJar, configDir, javaPath)
            os.contains("mac") || os.contains("darwin") -> installLaunchd(agentJar, configDir, javaPath)
            else -> InstallResult(false, "Unsupported OS: ${System.getProperty("os.name")}", null)
        }
    }

    fun uninstall(): InstallResult {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") -> uninstallSystemd()
            os.contains("mac") || os.contains("darwin") -> uninstallLaunchd()
            else -> InstallResult(false, "Unsupported OS: ${System.getProperty("os.name")}", null)
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

    // ── systemd (Linux) ──────────────────────────────────────────────────────

    private fun systemdUnitPath(): Path = Path.of("/etc/systemd/system/$SERVICE_NAME.service")
    private fun systemdUserUnitPath(): Path {
        val home = System.getProperty("user.home") ?: "/root"
        return Path.of("$home/.config/systemd/user/$SERVICE_NAME.service")
    }

    private fun installSystemd(agentJar: Path, configDir: Path, javaPath: String?): InstallResult {
        val java = javaPath ?: resolveJava()
        val jarAbsolute = agentJar.toAbsolutePath().normalize()
        val confAbsolute = configDir.toAbsolutePath().normalize()
        val workDir = confAbsolute.parent ?: confAbsolute

        val isRoot = System.getProperty("user.name") == "root" || System.getenv("EUID") == "0"
        val unitPath = if (isRoot) systemdUnitPath() else systemdUserUnitPath()
        val user = System.getProperty("user.name") ?: "root"

        val unit = buildString {
            appendLine("[Unit]")
            appendLine("Description=ProperBackup Agent — encrypted backup service")
            appendLine("After=network-online.target")
            appendLine("Wants=network-online.target")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=simple")
            appendLine("WorkingDirectory=$workDir")
            appendLine("ExecStart=$java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -Xmx512m -jar $jarAbsolute --headless --config $confAbsolute/global.yml --conf-dir $confAbsolute")
            appendLine("Restart=on-failure")
            appendLine("RestartSec=30")
            appendLine("StandardOutput=journal")
            appendLine("StandardError=journal")
            if (isRoot) {
                appendLine("User=$user")
            }
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=${if (isRoot) "multi-user.target" else "default.target"}")
        }

        return try {
            Files.createDirectories(unitPath.parent)
            Files.writeString(unitPath, unit)

            if (isRoot) {
                Runtime.getRuntime().exec(arrayOf("systemctl", "daemon-reload")).waitFor()
                Runtime.getRuntime().exec(arrayOf("systemctl", "enable", SERVICE_NAME)).waitFor()
                Runtime.getRuntime().exec(arrayOf("systemctl", "start", SERVICE_NAME)).waitFor()
            } else {
                Runtime.getRuntime().exec(arrayOf("systemctl", "--user", "daemon-reload")).waitFor()
                Runtime.getRuntime().exec(arrayOf("systemctl", "--user", "enable", SERVICE_NAME)).waitFor()
                Runtime.getRuntime().exec(arrayOf("systemctl", "--user", "start", SERVICE_NAME)).waitFor()
            }
            InstallResult(true, "systemd service installed and started", unitPath.toString())
        } catch (e: Exception) {
            InstallResult(false, "Failed to install systemd service: ${e.message}", unitPath.toString())
        }
    }

    private fun uninstallSystemd(): InstallResult {
        val isRoot = System.getProperty("user.name") == "root"
        return try {
            if (isRoot) {
                Runtime.getRuntime().exec(arrayOf("systemctl", "stop", SERVICE_NAME)).waitFor()
                Runtime.getRuntime().exec(arrayOf("systemctl", "disable", SERVICE_NAME)).waitFor()
                Files.deleteIfExists(systemdUnitPath())
                Runtime.getRuntime().exec(arrayOf("systemctl", "daemon-reload")).waitFor()
            } else {
                Runtime.getRuntime().exec(arrayOf("systemctl", "--user", "stop", SERVICE_NAME)).waitFor()
                Runtime.getRuntime().exec(arrayOf("systemctl", "--user", "disable", SERVICE_NAME)).waitFor()
                Files.deleteIfExists(systemdUserUnitPath())
                Runtime.getRuntime().exec(arrayOf("systemctl", "--user", "daemon-reload")).waitFor()
            }
            InstallResult(true, "systemd service removed", null)
        } catch (e: Exception) {
            InstallResult(false, "Failed to uninstall: ${e.message}", null)
        }
    }

    private fun statusSystemd(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("systemctl", "is-active", SERVICE_NAME))
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            output
        } catch (_: Exception) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("systemctl", "--user", "is-active", SERVICE_NAME))
                val output = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                output
            } catch (_: Exception) {
                "unknown"
            }
        }
    }

    // ── launchd (macOS) ──────────────────────────────────────────────────────

    private fun launchdPlistPath(): Path {
        val home = System.getProperty("user.home") ?: "/Users/unknown"
        return Path.of("$home/Library/LaunchAgents/$SERVICE_LABEL.plist")
    }

    private fun installLaunchd(agentJar: Path, configDir: Path, javaPath: String?): InstallResult {
        val java = javaPath ?: resolveJava()
        val jarAbsolute = agentJar.toAbsolutePath().normalize()
        val confAbsolute = configDir.toAbsolutePath().normalize()
        val workDir = confAbsolute.parent ?: confAbsolute
        val logDir = Path.of(System.getProperty("user.home"), "Library/Logs/ProperBackup")

        val plist = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
            appendLine("""<plist version="1.0">""")
            appendLine("<dict>")
            appendLine("  <key>Label</key>")
            appendLine("  <string>$SERVICE_LABEL</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            appendLine("    <string>$java</string>")
            appendLine("    <string>--add-opens</string>")
            appendLine("    <string>java.base/sun.nio.ch=ALL-UNNAMED</string>")
            appendLine("    <string>-Xmx512m</string>")
            appendLine("    <string>-jar</string>")
            appendLine("    <string>$jarAbsolute</string>")
            appendLine("    <string>--headless</string>")
            appendLine("    <string>--config</string>")
            appendLine("    <string>$confAbsolute/global.yml</string>")
            appendLine("    <string>--conf-dir</string>")
            appendLine("    <string>$confAbsolute</string>")
            appendLine("  </array>")
            appendLine("  <key>WorkingDirectory</key>")
            appendLine("  <string>$workDir</string>")
            appendLine("  <key>RunAtLoad</key>")
            appendLine("  <true/>")
            appendLine("  <key>KeepAlive</key>")
            appendLine("  <dict>")
            appendLine("    <key>SuccessfulExit</key>")
            appendLine("    <false/>")
            appendLine("  </dict>")
            appendLine("  <key>ThrottleInterval</key>")
            appendLine("  <integer>30</integer>")
            appendLine("  <key>StandardOutPath</key>")
            appendLine("  <string>$logDir/agent.log</string>")
            appendLine("  <key>StandardErrorPath</key>")
            appendLine("  <string>$logDir/agent-error.log</string>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

        val plistPath = launchdPlistPath()
        return try {
            Files.createDirectories(plistPath.parent)
            Files.createDirectories(logDir)
            Files.writeString(plistPath, plist)

            Runtime.getRuntime().exec(arrayOf("launchctl", "load", "-w", plistPath.toString())).waitFor()
            InstallResult(true, "launchd agent installed and loaded", plistPath.toString())
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
            InstallResult(true, "launchd agent removed", null)
        } catch (e: Exception) {
            InstallResult(false, "Failed to uninstall: ${e.message}", null)
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun resolveJava(): String {
        val javaHome = System.getProperty("java.home")
        if (javaHome != null) {
            val bin = Path.of(javaHome, "bin", "java")
            if (Files.exists(bin)) return bin.toString()
        }
        return "java"
    }
}
