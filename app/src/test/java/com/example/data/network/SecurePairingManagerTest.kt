package com.example.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecurePairingManagerTest {
    @Test fun `address policy accepts private LAN only`() {
        assertTrue(SecurePairingManager.isAllowedLanIpv4("10.0.0.4"))
        assertTrue(SecurePairingManager.isAllowedLanIpv4("172.16.5.9"))
        assertTrue(SecurePairingManager.isAllowedLanIpv4("192.168.1.20"))
        assertFalse(SecurePairingManager.isAllowedLanIpv4("127.0.0.1"))
        assertFalse(SecurePairingManager.isAllowedLanIpv4("224.0.0.1"))
        assertFalse(SecurePairingManager.isAllowedLanIpv4("8.8.8.8"))
        assertFalse(SecurePairingManager.isAllowedLanIpv4("0.0.0.0"))
    }

    @Test fun `atomic storage replaces only after complete temp write`() {
        val dir = createTempDir(prefix = "secure-store-test")
        try {
            val target = File(dir, "secure.blob")
            val storage = AtomicNoBackupStorage(target)
            storage.writeAtomically(byteArrayOf(1, 2, 3))
            assertTrue(target.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            storage.writeAtomically(byteArrayOf(9, 8))
            assertTrue(target.readBytes().contentEquals(byteArrayOf(9, 8)))
            assertFalse(File(dir, "secure.blob.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
