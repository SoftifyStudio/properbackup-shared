package pl.danielniemiec.properbackup.agent.scanner

data class FileMetadata(
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val xxHash: Long,
)
