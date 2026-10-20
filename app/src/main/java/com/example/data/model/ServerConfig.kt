package com.example.data.model

import android.net.Uri

data class ServerConfig(
    val host: String = "",
    val port: Int = 54321,
    val token: String? = null,
    val requiresPairing: Boolean = false,
    val useLegacyUpload: Boolean = false
)

object PairingUriParser {
    /**
     * Parses URI in format: aurashare://pair?host=IP&port=PORT&token=TOKEN[&legacy=true/false]
     */
    fun parse(uriString: String): ServerConfig? {
        return try {
            val trimmed = uriString.trim()
            val uri = Uri.parse(trimmed)
            if (uri.scheme != "aurashare" || uri.host != "pair") {
                return null
            }
            val host = uri.getQueryParameter("host")?.trim() ?: return null
            if (host.isEmpty()) return null
            val port = uri.getQueryParameter("port")?.trim()?.toIntOrNull() ?: 54321
            val token = uri.getQueryParameter("token")?.trim()?.ifEmpty { null }
            val legacy = uri.getQueryParameter("legacy")?.toBoolean() ?: false
            ServerConfig(
                host = host,
                port = port,
                token = token,
                useLegacyUpload = legacy
            )
        } catch (_: Exception) {
            null
        }
    }
}
