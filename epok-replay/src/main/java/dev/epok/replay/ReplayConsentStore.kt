package dev.epok.replay

import com.posthog.PostHogEncryption
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A revocable disk-write gate using PostHog's queue stream hook (not encryption).
 * Each consent grant gets a NEW instance. Revoked streams can never write again,
 * even if an SDK serializer was already running when consent was withdrawn.
 */
internal class ReplayConsentStore(private val directory: File) : PostHogEncryption {
    private val lock = Any()
    private var allowed = true
    private val outputs = mutableSetOf<OutputStream>()

    override fun encrypt(outputStream: OutputStream): OutputStream = synchronized(lock) {
        if (!allowed) {
            outputStream.close()
            directory.deleteRecursively()
            throw IOException("Replay consent withdrawn")
        }
        outputs.add(outputStream)
        object : OutputStream() {
            override fun write(value: Int) = synchronized(lock) {
                if (!allowed) throw IOException("Replay consent withdrawn")
                outputStream.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) = synchronized(lock) {
                if (!allowed) throw IOException("Replay consent withdrawn")
                outputStream.write(bytes, offset, count)
            }
            override fun flush() = synchronized(lock) {
                if (!allowed) throw IOException("Replay consent withdrawn")
                outputStream.flush()
            }
            override fun close() = synchronized(lock) {
                outputs.remove(outputStream)
                outputStream.close()
            }
        }
    }

    override fun decrypt(inputStream: InputStream): InputStream = synchronized(lock) {
        if (!allowed) {
            inputStream.close()
            throw IOException("Replay consent withdrawn")
        }
        inputStream
    }

    fun revokeAndPurge() = synchronized(lock) {
        allowed = false
        outputs.forEach { runCatching { it.close() } }
        outputs.clear()
        check(!directory.exists() || directory.deleteRecursively()) { "Replay queue purge failed" }
    }
}
