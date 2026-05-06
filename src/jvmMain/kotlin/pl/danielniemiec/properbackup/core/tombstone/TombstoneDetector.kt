package pl.danielniemiec.properbackup.core.tombstone

object TombstoneDetector {

    @JvmStatic
    fun detectDeleted(prevState: Map<String, *>, currentPaths: Set<String>): Set<String> {
        val deleted = HashSet(prevState.keys)
        deleted.removeAll(currentPaths)
        return deleted
    }

    @JvmStatic
    fun effectiveFlag(changedCount: Int, deletedCount: Int): ChangeFlag = when {
        changedCount > 0 -> ChangeFlag.A
        deletedCount > 0 -> ChangeFlag.D
        else -> ChangeFlag.A
    }
}
