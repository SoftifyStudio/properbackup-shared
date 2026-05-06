package pl.danielniemiec.properbackup.core.tombstone

enum class ChangeFlag {
    A,
    D,
    ;

    companion object {
        @JvmStatic
        fun of(s: String): ChangeFlag = when (s.uppercase()) {
            "A" -> A
            "D" -> D
            else -> throw IllegalArgumentException("Unknown flag: $s")
        }
    }
}
