package dev.epok.replay

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReplayConsentStoreTest {
    @Test fun withdrawalClosesAlreadyOpenedStreamsAndDeletesQueuedBytes() {
        val directory = Files.createTempDirectory("epok-replay-test").toFile()
        try {
            val store = ReplayConsentStore(directory)
            val queued = File(directory, "queued.event")
            val stream = store.encrypt(queued.outputStream())
            stream.write("private replay".toByteArray())
            stream.flush()
            assertTrue(queued.length() > 0)
            store.revokeAndPurge()
            assertFalse(directory.exists())
            assertThrows(IOException::class.java) { stream.write("late snapshot".toByteArray()) }
            stream.close()
            // Simulate an SDK executor opening a queue file after revocation.
            directory.mkdirs()
            assertThrows(IOException::class.java) { store.encrypt(queued.outputStream()) }
            assertFalse(directory.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun aNewConsentGrantCannotReopenAnOldGeneration() {
        val root = Files.createTempDirectory("epok-replay-test").toFile()
        try {
            val oldDir = File(root, "old").apply { mkdirs() }
            val old = ReplayConsentStore(oldDir)
            val stale = old.encrypt(File(oldDir, "old.event").outputStream())
            old.revokeAndPurge()
            val newDir = File(root, "new").apply { mkdirs() }
            val fresh = ReplayConsentStore(newDir)
            fresh.encrypt(File(newDir, "new.event").outputStream()).use { it.write(42) }
            assertThrows(IOException::class.java) { stale.write(42) }
            assertEquals(1L, File(newDir, "new.event").length())
            fresh.revokeAndPurge()
        } finally { root.deleteRecursively() }
    }
}
