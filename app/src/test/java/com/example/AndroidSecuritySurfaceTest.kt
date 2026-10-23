package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidSecuritySurfaceTest {
    @Test fun productionAndroidSourcesContainNoCleartextOrLegacyPairingSurface() {
        val androidRoot = findAndroidModuleRoot()
        val sourceRoot = File(androidRoot, "src/main/java")
        val kotlinSources = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("production Kotlin source tree is missing", kotlinSources.isNotEmpty())
        val allSource = kotlinSources.joinToString("\n") { it.readText() }
        val resourceFiles = File(androidRoot, "src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        val allResources = resourceFiles.joinToString("\n") { it.readText() }

        listOf(
            "http://",
            "/api/handshake",
            "/api/pair/request",
            "/api/pair/status/",
            "PairingClient",
            "MdnsDiscoveryManager",
            "NearbyPeer",
            "useLegacyEndpoint",
            "legacyCandidateAllowed",
            "pendingLegacyPeer",
        ).forEach { forbidden ->
            assertFalse("forbidden Android production surface returned: $forbidden", allSource.contains(forbidden))
        }

        listOf(
            "الأجهزة الموثوقة",
            "الأجهزة القريبة",
            "الاقتران القديم",
            "نسخة الكمبيوتر",
        ).forEach { forbidden ->
            assertFalse("obsolete UI copy returned in Kotlin: $forbidden", allSource.contains(forbidden))
            assertFalse("obsolete UI resource returned: $forbidden", allResources.contains(forbidden))
        }

        val manifest = File(androidRoot, "src/main/AndroidManifest.xml").readText()
        assertTrue("Android must explicitly disable cleartext", manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertFalse("Android must never enable cleartext", manifest.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue("secure QR pairing routes must remain available", allSource.contains("/api/secure/pair/request"))
        assertTrue("only trusted secure discovery may resolve a saved route", allSource.contains("_aurashare-secure._tcp"))
    }

    private fun findAndroidModuleRoot(): File {
        var current: File? = File(".").canonicalFile
        repeat(6) {
            val directory = requireNotNull(current)
            listOf(File(directory, "app"), directory).firstOrNull {
                File(it, "src/main/java").isDirectory
            }?.let { return it }
            current = directory.parentFile
        }
        error("Android app source root could not be found from ${File(".").absolutePath}")
    }
}
