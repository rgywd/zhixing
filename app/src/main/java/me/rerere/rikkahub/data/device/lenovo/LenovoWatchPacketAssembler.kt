package me.rerere.rikkahub.data.device.lenovo

internal sealed interface LenovoWatchPacketAssembly {
    data object Incomplete : LenovoWatchPacketAssembly

    data class Complete(val frame: ByteArray) : LenovoWatchPacketAssembly

    data class Rejected(val reason: String) : LenovoWatchPacketAssembly
}

/** Reassembles the official 20-byte first chunk + sequence-prefixed continuation format. */
internal class LenovoWatchPacketAssembler {
    private var buffer: ByteArray? = null
    private var received = 0
    private var nextSequence = 0

    fun accept(chunk: ByteArray): LenovoWatchPacketAssembly {
        val current = buffer
        if (current == null) return acceptFirstChunk(chunk)

        if (chunk.size < 2) return reject("continuation chunk has no payload")
        val sequence = chunk[0].toInt() and 0xFF
        if (sequence != nextSequence) {
            return reject("expected continuation $nextSequence but received $sequence")
        }
        val payloadSize = chunk.size - 1
        if (received + payloadSize > current.size) return reject("continuation exceeds declared frame size")

        chunk.copyInto(current, destinationOffset = received, startIndex = 1)
        received += payloadSize
        nextSequence += 1
        if (received != current.size) return LenovoWatchPacketAssembly.Incomplete

        val completed = current.copyOf()
        reset()
        return LenovoWatchPacketAssembly.Complete(completed)
    }

    fun reset() {
        buffer = null
        received = 0
        nextSequence = 0
    }

    private fun acceptFirstChunk(chunk: ByteArray): LenovoWatchPacketAssembly {
        val expected = LenovoWatchProtocol.declaredFrameSize(chunk)
            ?: return LenovoWatchPacketAssembly.Rejected("first chunk is shorter than the frame header")
        if (chunk.size > expected) return LenovoWatchPacketAssembly.Rejected("first chunk exceeds declared frame size")
        if (chunk.size == expected) return LenovoWatchPacketAssembly.Complete(chunk.copyOf())

        buffer = ByteArray(expected).also { chunk.copyInto(it) }
        received = chunk.size
        nextSequence = 0
        return LenovoWatchPacketAssembly.Incomplete
    }

    private fun reject(reason: String): LenovoWatchPacketAssembly.Rejected {
        reset()
        return LenovoWatchPacketAssembly.Rejected(reason)
    }
}
