package pl.danielniemiec.properbackup.core.filename

import java.time.LocalDateTime

data class ParsedFilename(
    val userId: String,
    val timestamp: LocalDateTime,
    val pathId: String,
    val flag: ChangeFlag,
    val shortHash: String,
) {
    init {
        require(userId.isNotBlank()) { "userId blank" }
        require(pathId.length == 8) { "pathId must be 8 chars" }
        require(shortHash.length == 4) { "shortHash must be 4 chars" }
    }
}
