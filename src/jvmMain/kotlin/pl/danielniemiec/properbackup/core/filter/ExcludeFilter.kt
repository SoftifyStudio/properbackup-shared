package pl.danielniemiec.properbackup.core.filter

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.regex.PatternSyntaxException

class ExcludeFilter(userExcludes: List<String>?) {

    private val excludeMatchers: List<PathMatcher>
    private val warningMatchers: List<PathMatcher>

    init {
        val fs = FileSystems.getDefault()
        excludeMatchers = compile(fs, userExcludes ?: emptyList())
        warningMatchers = compile(fs, ExcludePatterns.WARNING_PATTERNS)
    }

    fun shouldExclude(relativePath: Path): Boolean {
        val posix = relativePath.toString().replace('\\', '/')

        for (m in warningMatchers) {
            if (m.matches(relativePath) || m.matches(Paths.get(posix))) {
                throw PrivacyAlertException(relativePath.toString())
            }
        }

        for (m in excludeMatchers) {
            if (m.matches(relativePath) || m.matches(Paths.get(posix))) {
                return true
            }
        }
        return false
    }

    fun isUserExcluded(relativePath: Path): Boolean {
        for (m in excludeMatchers) {
            if (m.matches(relativePath)) return true
        }
        return false
    }

    private fun compile(fs: java.nio.file.FileSystem, patterns: List<String>): List<PathMatcher> {
        val result = ArrayList<PathMatcher>(patterns.size)
        for (p in patterns) {
            val glob = if (p.startsWith("glob:")) p else "glob:$p"
            try {
                result.add(fs.getPathMatcher(glob))
            } catch (e: PatternSyntaxException) {
                System.err.println("[ExcludeFilter] Invalid pattern skipped: $p")
            }
        }
        return result
    }
}
