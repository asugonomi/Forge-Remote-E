package jp.local.forgedesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JobControlTest {
    @Test fun publicIdMatchesCompanionAndKeepsProofPrivate() {
        val proof = "de12a955-972c-4f80-8963-857445d24976"
        val publicId = forgeRemoteTaskId(proof)
        assertEquals("task(fr-c019b5c548da20673678cd222bab5722aa81e51bc6eab8f2bcb014ec18fdffd4)", publicId)
        assertFalse(publicId.contains(proof))
    }
    @Test fun dialogTicketsExpireAcrossBothUnitsAndEpochs() {
        val original = ForgeControlTicket("local", 1, "epoch")
        assertNotEquals(original, original.copy(unit=2))
        assertNotEquals(original, original.copy(epoch="new"))
        assertNotEquals(original, original.copy(id="other"))
    }
}
