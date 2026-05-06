package pl.danielniemiec.properbackup.core.filename

enum class ChangeFlag {
    A,
    D,
    ;

    companion object {
        @JvmStatic
        fun fromChar(c: Char): ChangeFlag = when (c) {
            'A' -> A
            'D' -> D
            else -> throw IllegalArgumentException("invalid flag char: $c")
        }
    }
}
