package com.storageguard.core

import kotlin.test.*

class CleanupEvidenceTest {
    @Test fun `approval alone never proves trash succeeded`() {
        assertEquals(CleanupStatus.FAILED, CleanupEvidence.media(false, true))
        assertEquals(CleanupStatus.UNVERIFIED, CleanupEvidence.media(null, true))
    }
    @Test fun `denial with unavailable status is not reported as successful`() {
        assertEquals(CleanupStatus.SKIPPED, CleanupEvidence.media(false, false))
        assertEquals(CleanupStatus.UNVERIFIED, CleanupEvidence.media(null, false))
    }
    @Test fun `trash must be observed even after interruption`() {
        assertEquals(CleanupStatus.TRASHED, CleanupEvidence.media(true, null))
        assertEquals(CleanupStatus.UNVERIFIED, CleanupEvidence.media(null, null))
    }
    @Test fun `permanent deletion requires acceptance and independently checked absence`() {
        assertEquals(CleanupStatus.DELETED, CleanupEvidence.document(true, false))
        assertEquals(CleanupStatus.UNVERIFIED, CleanupEvidence.document(true, null))
        assertEquals(CleanupStatus.UNVERIFIED, CleanupEvidence.document(true, true))
        assertEquals(CleanupStatus.UNVERIFIED, CleanupEvidence.document(false, false))
    }
}
