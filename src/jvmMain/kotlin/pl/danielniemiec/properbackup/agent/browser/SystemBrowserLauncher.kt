package pl.danielniemiec.properbackup.agent.browser

import pl.danielniemiec.properbackup.agent.util.GuiSingletonLock
import pl.danielniemiec.properbackup.agent.util.StartupTrace
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

object SystemBrowserLauncher {

    const val ENV_BROWSER = "PROPERBACKUP_BROWSER"
    const val ENV_DISABLE_CHROMIUM_APP = "PROPERBACKUP_DISABLE_CHROMIUM_APP"

    private const val PANEL_REOPEN_DEDUPE_SECONDS = 45

    @JvmStatic
    @Throws(IOException::class)
    fun openUrl(url: String?) {
        if (url.isNullOrBlank()) {
            StartupTrace.line("openUrl: pusty URL — pomijam.")
            return
        }
        val u = url.trim()
        StartupTrace.line(
            "openUrl: start, headless=${GraphicsEnvironment.isHeadless()}, urlLen=${u.length}",
        )
        if (tryReuseExistingChromiumPanel(u)) {
            StartupTrace.line("PROPERBACKUP_SUCCESS openUrl=reused_existing_chromium_window")
            return
        }
        if (tryChromiumAppWindow(u)) {
            recordPanelOpenDedupe(u)
            StartupTrace.line("PROPERBACKUP_SUCCESS openUrl=chromium_app_window (--app=)")
            return
        }
        if (tryOpenWithOsDefaultHandler(u)) {
            recordPanelOpenDedupe(u)
            StartupTrace.line("PROPERBACKUP_SUCCESS openUrl=native_os_handler (open/start/xdg-open)")
            return
        }
        StartupTrace.line("openUrl: natywne polecenie OS nie zadziałało — próba Desktop.browse.")
        if (!GraphicsEnvironment.isHeadless()
            && Desktop.isDesktopSupported()
            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            browseOnEdt(u)
            recordPanelOpenDedupe(u)
            StartupTrace.line("PROPERBACKUP_SUCCESS openUrl=desktop_browse")
            return
        }
        for (exe in browserExecutableCandidates()) {
            if (exe.isBlank()) continue
            val bin = Path.of(exe)
            if (!Files.isRegularFile(bin) || !Files.isExecutable(bin)) continue
            try {
                ProcessBuilder(exe, u)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                recordPanelOpenDedupe(u)
                StartupTrace.line("PROPERBACKUP_SUCCESS openUrl=browser_exe exe=$exe")
                return
            } catch (ignored: IOException) {
            }
        }
        StartupTrace.line("openUrl: FALLBACK — skopiuj URL do przeglądarki (żadna metoda nie zadziałała).")
        println("Otwórz w przeglądarce: $u")
    }

    @JvmStatic
    fun panelOpenDedupeKey(url: String?): String = url?.trim() ?: ""

    private fun panelDedupeFile(): Path =
        GuiSingletonLock.supportDirectory().resolve("panel-open-dedupe.txt")

    private fun recordPanelOpenDedupe(url: String) {
        try {
            Files.createDirectories(panelDedupeFile().parent)
            val body = System.currentTimeMillis().toString() + "\n" + panelOpenDedupeKey(url) + "\n"
            Files.writeString(panelDedupeFile(), body, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            StartupTrace.line("recordPanelOpenDedupe: ${e.message}")
        }
    }

    private fun tryReuseExistingChromiumPanel(url: String): Boolean {
        val f = panelDedupeFile()
        if (!Files.isRegularFile(f)) return false
        return try {
            val lines = Files.readAllLines(f, StandardCharsets.UTF_8)
            if (lines.size < 2) return false
            val t = lines[0].trim().toLong()
            val prev = lines[1].trim()
            if (panelOpenDedupeKey(url) != prev) return false
            val ageSec = (System.currentTimeMillis() - t) / 1000L
            if (ageSec > PANEL_REOPEN_DEDUPE_SECONDS) return false
            val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            if (os.contains("mac") || os.contains("darwin")) {
                if (tryActivateFrontmostChromiumOnMac()) {
                    StartupTrace.line("tryReuseExistingChromiumPanel: aktywowano przeglądarkę (dedupe, ageSec=$ageSec).")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            StartupTrace.line("tryReuseExistingChromiumPanel: ${e.message}")
            false
        }
    }

    private fun tryActivateFrontmostChromiumOnMac(): Boolean {
        val apps = arrayOf("Brave Browser", "Google Chrome", "Microsoft Edge", "Chromium")
        for (app in apps) {
            try {
                val pb = ProcessBuilder(
                    "/usr/bin/osascript",
                    "-e",
                    "tell application \"$app\" to activate",
                )
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                val p = pb.start()
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return true
                }
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    private fun tryChromiumAppWindow(url: String): Boolean {
        if (System.getenv(ENV_DISABLE_CHROMIUM_APP).toBoolean()) {
            StartupTrace.line("tryChromiumAppWindow: pominięto (PROPERBACKUP_DISABLE_CHROMIUM_APP).")
            return false
        }
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return false
        }
        val userData = try {
            val d = GuiSingletonLock.supportDirectory().resolve("chromium-panel-profile")
            Files.createDirectories(d)
            d
        } catch (e: IOException) {
            StartupTrace.line("tryChromiumAppWindow: nie można utworzyć profilu: ${e.message}")
            return false
        }
        val userDataArg = userData.toAbsolutePath().normalize().toString()
        for (exe in browserExecutableCandidates()) {
            if (exe.isBlank() || !looksChromiumFamilyExecutable(exe)) continue
            val bin = Path.of(exe)
            if (!Files.isRegularFile(bin) || !Files.isExecutable(bin)) continue
            try {
                val p = ProcessBuilder(
                    exe,
                    "--user-data-dir=$userDataArg",
                    "--no-first-run",
                    "--disable-session-crashed-bubble",
                    "--app=$u",
                )
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                StartupTrace.line("tryChromiumAppWindow: uruchomione pid=${p.pid()} exe=$exe")
                return true
            } catch (e: IOException) {
                StartupTrace.line("tryChromiumAppWindow: IOException dla $exe: ${e.message}")
            }
        }
        return false
    }

    private fun looksChromiumFamilyExecutable(exe: String): Boolean {
        val low = exe.lowercase(Locale.ROOT)
        return low.contains("chrome") || low.contains("chromium") || low.contains("brave") ||
            low.contains("edge") || low.contains("msedge")
    }

    private fun tryOpenWithOsDefaultHandler(url: String): Boolean {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return try {
            val pb = when {
                os.contains("mac") || os.contains("darwin") ->
                    ProcessBuilder("/usr/bin/open", url)
                os.contains("win") ->
                    ProcessBuilder("cmd", "/c", "start", "", url)
                else ->
                    ProcessBuilder("xdg-open", url)
            }
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            val p = pb.start()
            val mac = os.contains("mac") || os.contains("darwin")
            if (mac) {
                StartupTrace.line("tryOpenWithOsDefaultHandler: uruchomiono /usr/bin/open (pid=${p.pid()}).")
                true
            } else {
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    StartupTrace.line("tryOpenWithOsDefaultHandler: timeout po 20s.")
                    false
                } else {
                    val ev = p.exitValue()
                    if (ev != 0) StartupTrace.line("tryOpenWithOsDefaultHandler: exitCode=$ev")
                    ev == 0
                }
            }
        } catch (e: Exception) {
            StartupTrace.line(
                "tryOpenWithOsDefaultHandler: wyjątek: ${e.javaClass.simpleName}: ${e.message}",
            )
            false
        }
    }

    @Throws(IOException::class)
    private fun browseOnEdt(url: String) {
        val uri = URI.create(url)
        class Box {
            var io: IOException? = null
            var err: Error? = null
        }
        val box = Box()
        val task = Runnable {
            try {
                Desktop.getDesktop().browse(uri)
            } catch (e: IOException) {
                box.io = e
            } catch (e: Error) {
                box.err = e
            }
        }
        try {
            if (EventQueue.isDispatchThread()) {
                task.run()
            } else {
                EventQueue.invokeAndWait(task)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Przerwano otwieranie przeglądarki.", e)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val c = e.cause
            when (c) {
                is IOException -> throw c
                is RuntimeException -> throw c
                is Error -> throw c
                else -> throw IOException(c?.message ?: e.message, c)
            }
        }
        box.err?.let { throw it }
        box.io?.let { throw it }
    }

    private fun browserExecutableCandidates(): List<String> {
        val out = ArrayList<String>()
        System.getenv(ENV_BROWSER)?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        when {
            os.contains("mac") || os.contains("darwin") -> {
                out.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
                out.add("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser")
                out.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge")
                out.add("/Applications/Chromium.app/Contents/MacOS/Chromium")
            }
            os.contains("win") -> {
                val pf = System.getenv("ProgramFiles")
                val pf86 = System.getenv("ProgramFiles(x86)")
                val local = System.getenv("LOCALAPPDATA")
                if (pf != null) {
                    out.add("$pf\\Google\\Chrome\\Application\\chrome.exe")
                    out.add("$pf\\BraveSoftware\\Brave-Browser\\Application\\brave.exe")
                    out.add("$pf\\Microsoft\\Edge\\Application\\msedge.exe")
                }
                if (pf86 != null) {
                    out.add("$pf86\\Google\\Chrome\\Application\\chrome.exe")
                    out.add("$pf86\\Microsoft\\Edge\\Application\\msedge.exe")
                }
                if (local != null) {
                    out.add("$local\\Google\\Chrome\\Application\\chrome.exe")
                    out.add("$local\\Microsoft\\Edge\\Application\\msedge.exe")
                }
            }
            else -> {
                out.addAll(
                    listOf(
                        "/usr/bin/google-chrome-stable",
                        "/usr/bin/google-chrome",
                        "/usr/bin/chromium",
                        "/usr/bin/chromium-browser",
                        "/usr/bin/brave-browser",
                        "/usr/bin/microsoft-edge",
                        "/snap/bin/chromium",
                    ),
                )
            }
        }
        return out
    }
}
