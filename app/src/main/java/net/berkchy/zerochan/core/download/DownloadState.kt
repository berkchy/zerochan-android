package net.berkchy.zerochan.core.download

/** Transient marker for the post screen; the real progress lives in Room + the notification. */
sealed interface DownloadState {
    data object Queued : DownloadState
}
