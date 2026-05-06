package pl.danielniemiec.properbackup.core.client

interface BackupClient {

    data class FileInfo(val size: Long, val mtimeEpochSeconds: Long, val contentHash: String)

    @Throws(Exception::class)
    fun listFiles(rootPath: String): Map<String, FileInfo>

    @Throws(Exception::class)
    fun readFile(absolutePath: String): ByteArray

    @Throws(Exception::class)
    fun upload(encryptedChunk: ByteArray, pathId: String, originalPath: String, flag: String)
}
