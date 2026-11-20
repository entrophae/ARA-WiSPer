package de.rub.hcd.entrophae.ara_wisper.network

import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

// Needs to be called before anything in the app touches an OkHttpClient
// so that it is guaranteed that Conscrypt is registered
// Otherwise, OkHttp is locked to the Android 6.0 default provider
fun installConscryptSecurity() {
    Security.removeProvider("AraWisperTrust")
    Security.removeProvider("Conscrypt")
    Security.insertProviderAt(Conscrypt.newProvider(), 1)

    val trustManager = buildHandshakeCertificates().trustManager
    Security.insertProviderAt(BundledTrustProvider(trustManager), 1)

    val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
    sslContext.init(null, arrayOf(trustManager), null)
    SSLContext.setDefault(sslContext)
}

private class BundledTrustProvider(
    defaultTrustManager: X509TrustManager
) : Provider("AraWisperTrust", 1.0, "Bundled CA TrustManager") {
    init {
        putService(object : Service(
            this, "TrustManagerFactory", "PKIX", BundledTrustManagerFactorySpi::class.java.name, listOf("X509"), null
        ) {
            override fun newInstance(constructorParameter: Any?): Any =
                BundledTrustManagerFactorySpi(defaultTrustManager)
        })
    }
}

private class BundledTrustManagerFactorySpi(
    private val defaultTrustManager: X509TrustManager
) : TrustManagerFactorySpi() {
    private var delegate: TrustManagerFactory? = null

    override fun engineInit(ks: KeyStore?) {
        if (ks == null) {
            delegate = null
        } else {
            val fallback = Security.getProviders().first { it.name != "AraWisperTrust" }
            delegate = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), fallback)
                .apply { init(ks) }
        }
    }

    override fun engineInit(spec: ManagerFactoryParameters?) {
        val fallback = Security.getProviders().first { it.name != "AraWisperTrust" }
        delegate = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), fallback)
            .apply { init(spec) }
    }

    override fun engineGetTrustManagers(): Array<TrustManager> =
        delegate?.trustManagers ?: arrayOf(defaultTrustManager)
}

@Volatile
private var cachedCertificates: HandshakeCertificates? = null

fun OkHttpClient.Builder.withModernTls(): OkHttpClient.Builder {
    val certs = cachedCertificates ?: synchronized(this) {
        cachedCertificates ?: buildHandshakeCertificates().also { cachedCertificates = it }
    }
    return this.sslSocketFactory(certs.sslSocketFactory(), certs.trustManager)
}

private fun buildHandshakeCertificates(): HandshakeCertificates {
    val pemBundle = AraWisperManager.appAssets.open("cacert.pem")
        .bufferedReader().use { it.readText() }

    val certBlocks = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
        .findAll(pemBundle)
        .map { it.value.decodeCertificatePem() }

    return HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .apply { certBlocks.forEach { addTrustedCertificate(it) } }
        .build()
}