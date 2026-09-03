package pt.andreomlopes.wearsideloader

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * The ADB identity of this phone. The keypair must survive restarts: the watch remembers the
 * public key after pairing, so regenerating it silently invalidates every previous pairing.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val adbKey: PrivateKey
    private val adbCert: Certificate

    init {
        api = Build.VERSION.SDK_INT

        val keyFile = File(context.filesDir, KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)

        val existingKey = readKey(keyFile)
        val existingCert = readCert(certFile)

        if (existingKey != null && existingCert != null) {
            adbKey = existingKey
            adbCert = existingCert
        } else {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = generator.generateKeyPair()
            adbKey = pair.private
            adbCert = selfSign(pair.public, pair.private)
            keyFile.writeBytes(adbKey.encoded)
            certFile.writeBytes(encodePem(adbCert))
        }
    }

    override fun getPrivateKey(): PrivateKey = adbKey

    override fun getCertificate(): Certificate = adbCert

    override fun getDeviceName(): String = "WearSideloader"

    companion object {
        private const val KEY_FILE = "adb_private.key"
        private const val CERT_FILE = "adb_cert.pem"
        private const val SIGNATURE_ALGORITHM = "SHA512withRSA"

        @Volatile
        private var instance: AdbManager? = null

        fun getInstance(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }

        private fun readKey(file: File): PrivateKey? = runCatching {
            if (!file.exists()) return null
            java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
        }.getOrNull()

        private fun readCert(file: File): Certificate? = runCatching {
            if (!file.exists()) return null
            file.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        }.getOrNull()

        private fun selfSign(publicKey: java.security.PublicKey, privateKey: PrivateKey): Certificate {
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
            val subject = X500Name("CN=Wear Sideloader")

            val extensions = CertificateExtensions().apply {
                set(
                    "SubjectKeyIdentifier",
                    SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier)
                )
                set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            }

            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(SIGNATURE_ALGORITHM)))
                set("subject", CertificateSubjectName(subject))
                set("key", CertificateX509Key(publicKey))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(subject))
                set("extensions", extensions)
            }

            return X509CertImpl(info).apply { sign(privateKey, SIGNATURE_ALGORITHM) }
        }

        private fun encodePem(certificate: Certificate): ByteArray {
            val body = java.io.ByteArrayOutputStream()
            BASE64Encoder().encode(certificate.encoded, body)
            return buildString {
                append(X509Factory.BEGIN_CERT).append('\n')
                append(body.toString("UTF-8")).append('\n')
                append(X509Factory.END_CERT)
            }.toByteArray()
        }
    }
}
