package de.rub.hcd.entrophae.ara_wisper.network

import ch.fhnw.pepper_realtime.R
import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.conscrypt.Conscrypt
import java.security.Security
import javax.net.ssl.SSLContext

/**
 * Security provider bootstrapper installing Conscrypt.
 *
 * Upgrades the JVM's default SSL/TLS stack at process startup so modern TLS 1.2/1.3
 * cipher suites work reliably for external API and WebSocket connections.
 */

// Needs to be called before anything in the app touches an OkHttpClient
// so that it is guaranteed that Conscrypt is registered
// Otherwise, OkHttp is locked to the Android 6.0 default provider
fun installConscryptSecurity() {
    Security.removeProvider(ProxySettingsRepository.trustProviderName)
    Security.removeProvider("Conscrypt")
    Security.insertProviderAt(Conscrypt.newProvider(), 1)

    val trustManager = buildHandshakeCertificates().trustManager
    Security.insertProviderAt(BundledTrustProvider(trustManager), 1)

    val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
    sslContext.init(null, arrayOf(trustManager), null)
    SSLContext.setDefault(sslContext)
}

private fun buildHandshakeCertificates(): HandshakeCertificates {
    val pemBundle = AraWisperManager.app.resources.openRawResource(R.raw.cacert)
        .bufferedReader().use { it.readText() }

    val certBlocks = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
        .findAll(pemBundle)
        .map { it.value.decodeCertificatePem() }

    return HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .apply { certBlocks.forEach { addTrustedCertificate(it) } }
        .build()
}