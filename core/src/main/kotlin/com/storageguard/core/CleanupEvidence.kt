package com.storageguard.core

enum class CleanupStatus { TRASHED, DELETED, SKIPPED, FAILED, UNVERIFIED }

/** Request/consent success is not proof that the provider completed a removal. */
object CleanupEvidence {
    fun media(trashed: Boolean?, approved: Boolean?): CleanupStatus = when (trashed) {
        true -> CleanupStatus.TRASHED
        false -> if (approved == false) CleanupStatus.SKIPPED else CleanupStatus.FAILED
        null -> CleanupStatus.UNVERIFIED
    }
    fun document(accepted: Boolean, exists: Boolean?): CleanupStatus =
        if (accepted && exists == false) CleanupStatus.DELETED else CleanupStatus.UNVERIFIED
}
