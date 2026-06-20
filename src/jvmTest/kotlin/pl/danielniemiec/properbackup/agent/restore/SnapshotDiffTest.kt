package pl.danielniemiec.properbackup.agent.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SnapshotDiffTest {

    private val diff = SnapshotDiff()

    @Test
    fun `files in snapshot but not current are RESTORE`() {
        val result = diff.compute(
            snapshotFiles = mapOf("/a.txt" to SnapshotFileInfo("abc123", 100)),
            currentFiles = emptyMap(),
        )
        assertEquals(1, result.filesToRestore)
        assertEquals(0, result.filesToDelete)
        assertEquals(0, result.filesUnchanged)
        assertEquals(100, result.totalRestoreBytes)
        assertEquals(FileDiffEntry.DiffAction.RESTORE, result.entries[0].action)
    }

    @Test
    fun `files in current but not snapshot are DELETE`() {
        val result = diff.compute(
            snapshotFiles = emptyMap(),
            currentFiles = mapOf("/b.txt" to CurrentFileInfo("xyz789")),
        )
        assertEquals(0, result.filesToRestore)
        assertEquals(1, result.filesToDelete)
        assertEquals(0, result.filesUnchanged)
        assertEquals(FileDiffEntry.DiffAction.DELETE, result.entries[0].action)
    }

    @Test
    fun `files with same SHA-256 are UNCHANGED`() {
        val sha = "aabbccdd"
        val result = diff.compute(
            snapshotFiles = mapOf("/c.txt" to SnapshotFileInfo(sha, 50)),
            currentFiles = mapOf("/c.txt" to CurrentFileInfo(sha)),
        )
        assertEquals(0, result.filesToRestore)
        assertEquals(0, result.filesToDelete)
        assertEquals(1, result.filesUnchanged)
    }

    @Test
    fun `files with different SHA-256 are RESTORE`() {
        val result = diff.compute(
            snapshotFiles = mapOf("/d.txt" to SnapshotFileInfo("aaa", 200)),
            currentFiles = mapOf("/d.txt" to CurrentFileInfo("bbb")),
        )
        assertEquals(1, result.filesToRestore)
        assertEquals(0, result.filesToDelete)
        assertEquals(200, result.totalRestoreBytes)
    }

    @Test
    fun `mixed scenario computes correctly`() {
        val result = diff.compute(
            snapshotFiles = mapOf(
                "/keep.txt" to SnapshotFileInfo("same", 10),
                "/restore.txt" to SnapshotFileInfo("new", 300),
                "/new-file.txt" to SnapshotFileInfo("fresh", 500),
            ),
            currentFiles = mapOf(
                "/keep.txt" to CurrentFileInfo("same"),
                "/restore.txt" to CurrentFileInfo("old"),
                "/delete-me.txt" to CurrentFileInfo("gone"),
            ),
        )
        assertEquals(2, result.filesToRestore)
        assertEquals(1, result.filesToDelete)
        assertEquals(1, result.filesUnchanged)
        assertEquals(800, result.totalRestoreBytes)
    }

    @Test
    fun `sampleRestore and sampleDelete return up to 20 entries`() {
        val snapFiles = (1..25).associate { "/restore-$it.txt" to SnapshotFileInfo("hash$it", 10L) }
        val curFiles = (30..55).associate { "/delete-$it.txt" to CurrentFileInfo("hash$it") }
        val result = diff.compute(snapFiles, curFiles)
        assertEquals(20, result.sampleRestore.size)
        assertEquals(20, result.sampleDelete.size)
    }
}
