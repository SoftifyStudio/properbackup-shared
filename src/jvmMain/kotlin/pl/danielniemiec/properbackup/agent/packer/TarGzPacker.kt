package pl.danielniemiec.properbackup.agent.packer

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

object TarGzPacker {

    @JvmStatic
    @Throws(IOException::class)
    fun pack(rootDir: Path, files: List<Path>, properbackupIdxJson: ByteArray?, out: OutputStream) {
        GzipCompressorOutputStream(out).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                if (properbackupIdxJson != null && properbackupIdxJson.isNotEmpty()) {
                    val idxEntry = TarArchiveEntry(".properbackup-idx")
                    idxEntry.size = properbackupIdxJson.size.toLong()
                    idxEntry.mode = 0b110000000
                    tar.putArchiveEntry(idxEntry)
                    tar.write(properbackupIdxJson)
                    tar.closeArchiveEntry()
                }

                for (file in files) {
                    val entryName = rootDir.relativize(file).toString()
                    val entry = TarArchiveEntry(file.toFile(), entryName)
                    tar.putArchiveEntry(entry)
                    Files.copy(file, tar)
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun pack(rootDir: Path, files: List<Path>, out: OutputStream) {
        pack(rootDir, files, null, out)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun packToBytes(rootDir: Path, files: List<Path>): ByteArray =
        packToBytes(rootDir, files, null)

    @JvmStatic
    @Throws(IOException::class)
    fun packToBytes(rootDir: Path, files: List<Path>, properbackupIdxJson: ByteArray?): ByteArray {
        val buf = ByteArrayOutputStream()
        pack(rootDir, files, properbackupIdxJson, buf)
        return buf.toByteArray()
    }
}
