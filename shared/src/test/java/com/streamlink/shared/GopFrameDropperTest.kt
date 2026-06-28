package com.streamlink.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GopFrameDropperTest {

    @Test
    fun `verify I-Frame is NEVER dropped even under extreme thermal or latency stress`() {
        // Simulating extreme queue depth which drops all P/B frames
        val shouldDropIFrame = GopFrameDropper.shouldDrop(isKeyframe = true, queueDepth = 50)
        
        assertFalse(shouldDropIFrame, "Critical Failure: I-Frame was marked for dropping!")
        
        val shouldDropPFrame = GopFrameDropper.shouldDrop(isKeyframe = false, queueDepth = 50)
        assertTrue(shouldDropPFrame, "Failure: P-Frame was NOT marked for dropping under extreme stress!")
    }
}
