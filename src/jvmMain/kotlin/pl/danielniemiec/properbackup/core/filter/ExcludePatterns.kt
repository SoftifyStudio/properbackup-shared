package pl.danielniemiec.properbackup.core.filter

object ExcludePatterns {

    @JvmField
    val WARNING_PATTERNS: List<String> = listOf(
        ".env",
        "**/.env",
        ".env.*",
        "**/.env.*",
        "**/*.pem",
        "**/*.key",
        "**/*.p12",
        "**/*.pfx",
        "**/id_rsa",
        "**/id_rsa.pub",
        "**/id_ed25519",
        "**/id_ed25519.pub",
        "**/shadow",
        "**/passwd",
        "**/wp-config.php",
        "**/config.php",
        "**/secrets/**",
        "**/.netrc",
        "**/*.keystore",
        "**/*.jks",
    )

    @JvmField
    val DEFAULT_EXCLUDES: List<String> = listOf(
        "**/.DS_Store",
        "**/Thumbs.db",
        "**/node_modules/**",
        "**/.git/**",
        "**/target/**",
        "**/build/**",
        "**/__pycache__/**",
        "**/*.pyc",
        "**/*.class",
        "**/logs/**",
        "**/*.log",
        "**/cache/**",
        "**/.cache/**",
    )
}
