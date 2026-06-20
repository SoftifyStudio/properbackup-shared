package pl.danielniemiec.properbackup.core.restore

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SnapshotDiffTest {

    @Test
    fun `files only in snapshot are marked for restore`() {
        val snapshot = mapOf(
            "/app/file1.txt" to SnapshotDiff.FileEntry("/app/file1.txt", "abc123", 100),
        )
        val local = emptyMap<String, SnapshotDiff.FileEntry>()
        val diff = SnapshotDiff.compute(snapshot, local)
        assertEquals(1, diff.filesToRestore.size)
        assertEquals("/app/file1.txt", diff.filesToRestore[0].path)
        assertEquals(0, diff.filesToDelete.size)
        assertEquals(0, diff.filesUnchanged.size)
    }

    @Test
    fun `files only on local are marked for delete`() {
        val snapshot = emptyMap<String, SnapshotDiff.FileEntry>()
        val local = mapOf(
            "/app/extra.txt" to SnapshotDiff.FileEntry("/app/extra.txt", "def456", 200),
        )
        val diff = SnapshotDiff.compute(snapshot, local)
        assertEquals(0, diff.filesToRestore.size)
        assertEquals(1, diff.filesToDelete.size)
        assertEquals("/app/extra.txt", diff.filesToDelete[0].path)
    }

    @Test
    fun `files with matching sha256 are unchanged`() {
        val entry = SnapshotDiff.FileEntry("/app/same.txt", "hash1", 100)
        val snapshot = mapOf("/app/same.txt" to entry)
        val local = mapOf("/app/same.txt" to entry)
        val diff = SnapshotDiff.compute(snapshot, local)
        assertEquals(0, diff.filesToRestore.size)
        assertEquals(0, diff.filesToDelete.size)
        assertEquals(1, diff.filesUnchanged.size)
    }

    @Test
    fun `files with different sha256 are marked for restore`() {
        val snapshot = mapOf(
            "/app/changed.txt" to SnapshotDiff.FileEntry("/app/changed.txt", "new_hash", 150),
        )
        val local = mapOf(
            "/app/changed.txt" to SnapshotDiff.FileEntry("/app/changed.txt", "old_hash", 100),
        )
        val diff = SnapshotDiff.compute(snapshot, local)
        assertEquals(1, diff.filesToRestore.size)
        assertEquals(0, diff.filesToDelete.size)
    }

    @Test
    fun `critical paths on local are protected from deletion`() {
        val snapshot = emptyMap<String, SnapshotDiff.FileEntry>()
        val local = mapOf(
            "/proc/1/status" to SnapshotDiff.FileEntry("/proc/1/status", null, 0),
            "/app/data.txt" to SnapshotDiff.FileEntry("/app/data.txt", "hash", 100),
        )
        val diff = SnapshotDiff.compute(snapshot, local)
        assertEquals(1, diff.filesToDelete.size)
        assertEquals(1, diff.criticalProtected.size)
        assertEquals("/proc/1/status", diff.criticalProtected[0])
    }

    @Test
    fun `totalRestoreBytes sums correctly`() {
        val snapshot = mapOf(
            "/app/a.txt" to SnapshotDiff.FileEntry("/app/a.txt", "h1", 100),
            "/app/b.txt" to SnapshotDiff.FileEntry("/app/b.txt", "h2", 200),
        )
        val diff = SnapshotDiff.compute(snapshot, emptyMap())
        assertEquals(300L, diff.totalRestoreBytes)
    }

    @Test
    fun `sha256Hex produces correct hex string`() {
        val hash = SnapshotDiff.sha256Hex("hello".toByteArray())
        assertEquals(64, hash.length)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun `complex scenario with all operation types`() {
        val snapshot = mapOf(
            "/app/unchanged.txt" to SnapshotDiff.FileEntry("/app/unchanged.txt", "h1", 100),
            "/app/modified.txt" to SnapshotDiff.FileEntry("/app/modified.txt", "new", 200),
            "/app/new_file.txt" to SnapshotDiff.FileEntry("/app/new_file.txt", "h3", 300),
        )
        val local = mapOf(
            "/app/unchanged.txt" to SnapshotDiff.FileEntry("/app/unchanged.txt", "h1", 100),
            "/app/modified.txt" to SnapshotDiff.FileEntry("/app/modified.txt", "old", 150),
            "/app/deleted_in_snapshot.txt" to SnapshotDiff.FileEntry("/app/deleted_in_snapshot.txt", "h4", 50),
        )
        val diff = SnapshotDiff.compute(snapshot, local)
        assertEquals(2, diff.filesToRestore.size)
        assertEquals(1, diff.filesToDelete.size)
        assertEquals(1, diff.filesUnchanged.size)
        assertEquals(500L, diff.totalRestoreBytes)
    }
}
