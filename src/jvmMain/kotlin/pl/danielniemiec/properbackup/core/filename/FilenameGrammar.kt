package pl.danielniemiec.properbackup.core.filename

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FilenameGrammar {

    private val PATTERN = Regex("^(U\\d+)-(\\d{10})-([0-9A-Za-z]{8})-([AD])-([0-9a-f]{4})\\.enc$")
    private val TS_FMT = DateTimeFormatter.ofPattern("yyMMddHHmm")

    @JvmStatic
    fun build(p: ParsedFilename): String =
        "${p.userId}-${TS_FMT.format(p.timestamp)}-${p.pathId}-${p.flag.name}-${p.shortHash}.enc"

    @JvmStatic
    fun parse(filename: String?): ParsedFilename {
        if (filename == null) throw IllegalArgumentException("filename null")
        val m = PATTERN.matchEntire(filename)
            ?: throw IllegalArgumentException("filename does not match grammar: $filename")
        val ts = try {
            LocalDateTime.parse(m.groupValues[2], TS_FMT)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid timestamp: ${m.groupValues[2]}", e)
        }
        return ParsedFilename(
            m.groupValues[1],
            ts,
            m.groupValues[3],
            ChangeFlag.fromChar(m.groupValues[4][0]),
            m.groupValues[5],
        )
    }

    @JvmStatic
    fun matches(filename: String?): Boolean =
        filename != null && PATTERN.matches(filename)
}
