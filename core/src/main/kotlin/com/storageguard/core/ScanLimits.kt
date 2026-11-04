package com.storageguard.core

/** Limits apply before retaining provider-controlled metadata, including unknown-size files. */
data class ScanLimits(
    val maxFiles: Int = 30_000,
    val maxMetadataChars: Long = 4_000_000,
    val maxDirectories: Int = 4_096,
    val maxTraversalChars: Long = 1_000_000,
    val maxProviderRows: Int = 100_000,
    val maxFieldChars: Int = 4_096,
) {
    init {
        require(maxFiles > 0 && maxMetadataChars > 0 && maxDirectories > 0 &&
            maxTraversalChars > 0 && maxProviderRows > 0 && maxFieldChars > 0)
    }
}

class ScanLimitExceeded(message: String) : IllegalStateException(message)

class InventoryBudget(private val limits: ScanLimits) {
    private var files = 0
    private var chars = 0L

    fun retain(asset: Asset, previous: Asset?) {
        val fields = listOf(asset.uri, asset.identity, asset.name, asset.mime, asset.location)
        if (fields.any { it != null && it.length > limits.maxFieldChars }) {
            throw ScanLimitExceeded("A provider returned oversized file metadata. Scan coverage is partial.")
        }
        val nextFiles = files + if (previous == null) 1 else 0
        val nextChars = chars - (previous?.metadataChars() ?: 0) + asset.metadataChars()
        if (nextFiles > limits.maxFiles || nextChars > limits.maxMetadataChars) {
            throw ScanLimitExceeded("The scan reached its metadata limit. Choose smaller folders to scan the remaining files.")
        }
        files = nextFiles
        chars = nextChars
    }

    private fun Asset.metadataChars(): Long = listOf(uri, identity, name, mime, location)
        .sumOf { it?.length?.toLong() ?: 0 }
}

/** Deduplicate before queueing so repeated rows and cycles cannot grow the queue. */
class DirectoryTraversal(root: String, private val limits: ScanLimits = ScanLimits()) {
    private val pending = ArrayDeque<String>()
    private val scheduled = mutableSetOf<String>()
    private var chars = 0L
    private var rows = 0

    init { enqueue(root) }

    fun next(): String? = pending.removeFirstOrNull()

    fun recordRow() {
        if (rows >= limits.maxProviderRows) {
            throw ScanLimitExceeded("A folder returned too many entries. Scan coverage is partial; choose a smaller folder.")
        }
        rows++
    }

    fun enqueue(id: String) {
        if (id.length > limits.maxFieldChars) throw ScanLimitExceeded("A provider returned an oversized document ID. Scan coverage is partial.")
        if (id in scheduled) return
        if (scheduled.size >= limits.maxDirectories || id.length.toLong() > limits.maxTraversalChars - chars) {
            throw ScanLimitExceeded("The folder traversal reached its limit. Choose smaller folders to scan the remaining files.")
        }
        scheduled.add(id)
        pending.add(id)
        chars += id.length
    }
}
