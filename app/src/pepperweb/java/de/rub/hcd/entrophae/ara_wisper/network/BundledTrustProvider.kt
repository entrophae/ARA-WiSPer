package de.rub.hcd.entrophae.ara_wisper.network

import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/**
 * Custom TLS trust manager loading bundled root CA certificates (`res/raw/cacert.pem`).
 *
 * Enables Pepper's tablet OS running under Android 7.0 to verify modern HTTPS
 * endpoints whose root certificates are absent from the system trust store.
 */
internal class BundledTrustProvider(
    defaultTrustManager: X509TrustManager
) : Provider(ProxySettingsRepository.trustProviderName, 1.0, "Bundled CA TrustManager") {
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
            val fallback = Security.getProviders().first { it.name != ProxySettingsRepository.trustProviderName }
            delegate = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), fallback)
                .apply { init(ks) }
        }
    }

    override fun engineInit(spec: ManagerFactoryParameters?) {
        val fallback = Security.getProviders().first { it.name != ProxySettingsRepository.trustProviderName }
        delegate = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), fallback)
            .apply { init(spec) }
    }

    override fun engineGetTrustManagers(): Array<TrustManager> =
        delegate?.trustManagers ?: arrayOf(defaultTrustManager)
}
