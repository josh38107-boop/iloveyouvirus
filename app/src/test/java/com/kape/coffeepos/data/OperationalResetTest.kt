package com.kape.coffeepos.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalResetTest {
    @Test
    fun newerCloudGenerationIsApplied() {
        assertTrue(shouldApplyOperationalReset(localGeneration = 2, remoteGeneration = 3))
    }

    @Test
    fun sameOrOlderGenerationIsIdempotentlyIgnored() {
        assertFalse(shouldApplyOperationalReset(localGeneration = 3, remoteGeneration = 3))
        assertFalse(shouldApplyOperationalReset(localGeneration = 3, remoteGeneration = 2))
    }

    @Test
    fun missingResetEndpointIsRecognizedAsLegacyBackend() {
        assertTrue(isLegacyResetStateResponse(404))
        assertFalse(isLegacyResetStateResponse(401))
        assertFalse(isLegacyResetStateResponse(500))
    }
}
