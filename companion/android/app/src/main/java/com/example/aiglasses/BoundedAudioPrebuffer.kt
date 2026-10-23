package com.example.aiglasses

import java.util.ArrayDeque

/** FIFO mic buffer used only while a physical-button session is obtaining credentials. */
internal class BoundedAudioPrebuffer(private val maxBytes: Int) {
    private val chunks = ArrayDeque<ByteArray>()
    private var byteCount = 0

    val sizeBytes: Int get() = byteCount
    val isEmpty: Boolean get() = chunks.isEmpty()

    fun add(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val retained = if (chunk.size <= maxBytes) chunk else chunk.copyOfRange(chunk.size - maxBytes, chunk.size)
        chunks.addLast(retained)
        byteCount += retained.size
        while (byteCount > maxBytes && chunks.isNotEmpty()) {
            byteCount -= chunks.removeFirst().size
        }
    }

    fun drain(): List<ByteArray> {
        val result = chunks.toList()
        clear()
        return result
    }

    fun clear() {
        chunks.clear()
        byteCount = 0
    }
}
