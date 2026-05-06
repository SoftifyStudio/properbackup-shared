package pl.danielniemiec.properbackup.core.filter

class PrivacyAlertException(val filePath: String) :
    RuntimeException("Sensitive file detected — scan paused: $filePath")
