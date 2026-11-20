package de.rub.hcd.entrophae.ara_wisper.network

import de.rub.hcd.entrophae.ara_wisper.manager.AraWisperManager
import de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.Provider
import java.security.Security

fun getOrCreateDynamicKeyStore(): KeyStore {
    val filesDir = AraWisperManager.app.filesDir
    val password = ProxySettingsRepository.ksPassword

    val localIps = collectLocalIps()
    val hostnames = collectHostnames(localIps)
    val fingerprint = (localIps.map { it.hostAddress } + hostnames).sorted().joinToString("_")

    val metaFile = File(filesDir, "keystore_meta.txt")
    val ksFile = File(filesDir, "${ProxySettingsRepository.keyAlias}_dynamic.p12")

    loadCachedKeyStore(ksFile, metaFile, fingerprint, password)?.let { return it }

    ensureAndroidJksAliasProvider()
    return generateAndSaveKeyStore(ksFile, metaFile, fingerprint, hostnames, localIps, password)
}

private fun collectLocalIps(): List<InetAddress> =
    NetworkInterface.getNetworkInterfaces()?.toList()
        ?.filter { it.isUp && !it.isLoopback }
        ?.flatMap { it.inetAddresses.toList() }
        ?.filterIsInstance<Inet4Address>()
        ?.filter { it.isSiteLocalAddress }
        .orEmpty() + InetAddress.getByName("127.0.0.1")

private fun collectHostnames(localIps: List<InetAddress>): List<String> =
    buildSet {
        add("localhost")
        add("*.hcd-lab.ruhr-uni-bochum.de")
        localIps.forEach { addr ->
            val host = addr.canonicalHostName
            if (!host.isNullOrBlank() && host != addr.hostAddress) {
                add(host)
            }
        }
    }.toList()

private fun loadCachedKeyStore(
    ksFile: File,
    metaFile: File,
    fingerprint: String,
    password: String
): KeyStore? {
    if (!ksFile.exists() || !metaFile.exists() || metaFile.readText() != fingerprint) {
        return null
    }

    return runCatching {
        KeyStore.getInstance("PKCS12").apply {
            ksFile.inputStream().use { load(it, password.toCharArray()) }
        }
    }.getOrElse {
        ksFile.delete()
        metaFile.delete()
        null
    }
}

private fun ensureAndroidJksAliasProvider() {
    if (Security.getProvider("AndroidJksAlias") != null) return

    val pkcs12Service = KeyStore.getInstance("PKCS12").provider
        .getService("KeyStore", "PKCS12")
    val aliasProvider = object : Provider("AndroidJksAlias", 1.0, "Maps JKS to PKCS12") {
        init {
            put("KeyStore.JKS", pkcs12Service.className)
        }
    }
    Security.insertProviderAt(aliasProvider, 1)
}

private fun generateAndSaveKeyStore(
    ksFile: File,
    metaFile: File,
    fingerprint: String,
    hostnames: List<String>,
    localIps: List<InetAddress>,
    password: String
): KeyStore {
    val keyStore = buildKeyStore {
        certificate(ProxySettingsRepository.keyAlias) {
            this.password = password
            this.domains = hostnames
            this.ipAddresses = localIps
        }
    }

    val tmpFile = File(ksFile.parentFile, "${ksFile.name}.tmp")
    keyStore.saveToFile(tmpFile, password)
    if (!tmpFile.renameTo(ksFile)) {
        tmpFile.copyTo(ksFile, overwrite = true)
        tmpFile.delete()
    }
    metaFile.writeText(fingerprint)
    return keyStore
}