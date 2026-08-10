package com.example.aiglasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAudioPrebufferTest {
    @Test
    fun `drain preserves microphone chunk order`() {
        val buffer = BoundedAudioPrebuffer(20)
        buffer.add(byteArrayOf(1, 2))
        buffer.add(byteArrayOf(3, 4, 5))

        val drained = buffer.drain()

        assertEquals(2, drained.size)
        assertArrayEquals(byteArrayOf(1, 2), drained[0])
        assertArrayEquals(byteArrayOf(3, 4, 5), drained[1])
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `oldest complete chunks are dropped at the bound`() {
        val buffer = BoundedAudioPrebuffer(6)
        buffer.add(byteArrayOf(1, 2, 3, 4))
        buffer.add(byteArrayOf(5, 6, 7, 8))

        val drained = buffer.drain()

        assertEquals(1, drained.size)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), drained.single())
    }

    @Test
    fun `oversized chunk retains its newest bytes`() {
        val buffer = BoundedAudioPrebuffer(4)
        buffer.add(byteArrayOf(1, 2, 3, 4, 5, 6))

        assertArrayEquals(byteArrayOf(3, 4, 5, 6), buffer.drain().single())
    }
}
