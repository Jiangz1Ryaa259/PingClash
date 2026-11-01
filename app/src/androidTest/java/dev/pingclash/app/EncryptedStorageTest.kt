package dev.pingclash.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()

    @Test
    fun tamperingFailsClosedAndDoesNotOverwriteEvidence() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "security-${UUID.randomUUID()}").apply { mkdirs() }
        val context =
            object : ContextWrapper(app) {
                override fun getFilesDir(): File = directory
            }
        try {
            val repository = ProfileRepository(context)
            repository.importProfile(
                "fixture",
                """{"type":"anytls","server":"example.invalid","port":443,"password":"storage-private-token"}""",
            )
            val file = File(directory, "profiles.enc")
            assertFalse(file.readText(Charsets.ISO_8859_1).contains("storage-private-token"))
            val original = file.readBytes()
            for (index in listOf(1, 13, original.lastIndex)) {
                val attacked =
                    original.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
                file.writeBytes(attacked)
                val rejected = ProfileRepository(context)
                assertTrue(
                    "Modified authenticated ciphertext loaded",
                    rejected.data.value.loadFailed,
                )
                assertTrue(rejected.data.value.nodes.isEmpty())
                assertTrue(
                    runCatching { rejected.settings("1.1.1.1", "https://example.invalid/") }
                        .isFailure
                )
                assertArrayEquals("Corrupt evidence was overwritten", attacked, file.readBytes())
            }
            file.writeBytes(original)
            assertEquals("fixture", ProfileRepository(context).data.value.profiles.single().name)
        } finally {
            check(directory.canonicalPath.startsWith(app.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }
}
