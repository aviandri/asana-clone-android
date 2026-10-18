package com.mekari.project.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerConfigurationTest {
    @Test
    fun normalizesAndDerivesUrls() {
        val config = ServerConfiguration.normalize("  project.mekari.io/ ")!!
        assertEquals("https://project.mekari.io", config.serverUrl)
        assertEquals("https://project.mekari.io/api/v1/", config.apiBaseUrl)
        assertEquals("https://project.mekari.io/health", config.healthUrl)
    }

    @Test
    fun keepsAnExplicitHttpScheme() {
        assertEquals("http://10.0.2.2:3000", ServerConfiguration.normalize("http://10.0.2.2:3000/")!!.serverUrl)
    }

    @Test
    fun rejectsEmptyAndOtherSchemes() {
        assertNull(ServerConfiguration.normalize("   "))
        assertNull(ServerConfiguration.normalize("ftp://example.com"))
        assertNull(ServerConfiguration.normalize("https://"))
    }
}
