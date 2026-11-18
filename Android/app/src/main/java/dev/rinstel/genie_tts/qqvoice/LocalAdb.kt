package dev.rinstel.genie_tts.qqvoice

import dev.rinstel.genie_tts.R
import dev.rinstel.genie_tts.i18n.UiText
import dev.rinstel.genie_tts.i18n.requireUi
import dev.rinstel.genie_tts.i18n.checkUi

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only the phone's loopback daemon is reachable. No root / Shizuku fallback. */
class LocalAdb(context: Context) : AbsAdbConnectionManager() {
    private val appContext = context.applicationContext
    private val identity = AtomicFile(File(context.noBackupFilesDir, "qq-adb-identity.p12"))
    private val keyStore = KeyStore.getInstance("PKCS12")
    private val password = CharArray(0)

    init {
        if (identity.baseFile.exists()) identity.openRead().use { keyStore.load(it, password) }
        else {
            keyStore.load(null, password)
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Voicebox local ADB")
            val certificate = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()),
                    Date(System.currentTimeMillis() - 86400000),
                    Date(System.currentTimeMillis() + 10L * 365 * 86400000), name, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
            keyStore.setKeyEntry("adb", pair.private, password, arrayOf(certificate))
            val stream = identity.startWrite()
            try { keyStore.store(stream, password); identity.finishWrite(stream) }
            catch (e: Exception) { identity.failWrite(stream); throw e }
        }
        setApi(Build.VERSION.SDK_INT)
        setHostAddress("127.0.0.1")
        setTimeout(12, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey() = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(keyStore.getKey("adb", password).encoded))
    override fun getCertificate(): Certificate = keyStore.getCertificate("adb")
    override fun getDeviceName() = "Voicebox"

    fun pairLocal(port: Int, code: String) {
        requireUi(port in 1024..65535 && code.matches(Regex("[0-9]{6}"))) { UiText(R.string.adb_pairing_input) }
        checkUi(pair("127.0.0.1", port, code)) { UiText(R.string.adb_pairing_failed) }
    }

    fun pairLocal(code: String) {
        requireUi(code.matches(Regex("[0-9]{6}"))) { UiText(R.string.adb_pairing_code_invalid) }
        pairLocal(discoverLocalPort(AdbMdns.SERVICE_TYPE_TLS_PAIRING), code)
    }

    fun connectLocal(): Int {
        val port = discoverLocalPort(AdbMdns.SERVICE_TYPE_TLS_CONNECT)
        connectLocal(port)
        return port
    }

    private fun discoverLocalPort(serviceType: String): Int {
        val found = CountDownLatch(1)
        val port = AtomicInteger(0)
        // AdbMdns accepts only addresses belonging to this phone. Connections still use loopback.
        val discovery = AdbMdns(appContext, serviceType) { host, candidate ->
            if (host != null && candidate in 1024..65535 && port.compareAndSet(0, candidate)) found.countDown()
        }
        discovery.start()
        try {
            checkUi(found.await(10, TimeUnit.SECONDS)) {
                if (serviceType == AdbMdns.SERVICE_TYPE_TLS_PAIRING) UiText(R.string.adb_pairing_dialog_required)
                else UiText(R.string.adb_not_found)
            }
            return port.get()
        } finally { discovery.stop() }
    }

    fun connectLocal(port: Int) {
        requireUi(port in 1024..65535) { UiText(R.string.adb_port_required) }
        disconnect()
        checkUi(connect("127.0.0.1", port)) { UiText(R.string.adb_connect_failed) }
    }

    internal fun shell(command: String, input: ByteArray? = null, timeoutSeconds: Long = 15): String {
        require(timeoutSeconds in 1..75)
        // Interrupt open()/read(), and close the stream if an established command stalls.
        val worker = Thread.currentThread()
        val openingDeadline = deadlines.schedule({ worker.interrupt() }, 15, TimeUnit.SECONDS)
        val stream = try {
            openStream("exec:sh -c ${quote("( $command ); result=\$?; printf '\\nGENIE_EXIT:%s\\n' \"\$result\"")}")
        } finally { openingDeadline.cancel(false) }
        val deadline = deadlines.schedule({ runCatching { stream.close() } }, timeoutSeconds, TimeUnit.SECONDS)
        try {
            return stream.use {
                if (input != null) {
                    var offset = 0
                    while (offset < input.size) {
                        val count = minOf(4096, input.size - offset)
                        stream.write(input, offset, count); stream.flush(); offset += count
                    }
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                stream.openInputStream().use { reader ->
                    while (true) {
                        // LibADB 3.1.1 can report normal peer EOF as IOException after draining
                        // its queue. Accept it only with a complete exit trailer below.
                        val count = try { reader.read(buffer) }
                        catch (e: IOException) { if (stream.isClosed) break else throw e }
                        if (count < 0) break
                        checkUi(output.size() + count <= 2 * 1024 * 1024) { UiText(R.string.adb_output_limit) }
                        output.write(buffer, 0, count)
                    }
                }
                val text = output.toString("UTF-8").replace("\r\n", "\n")
                checkUi(text.endsWith("\nGENIE_EXIT:0\n")) { UiText(R.string.adb_file_operation_failed) }
                text.removeSuffix("\nGENIE_EXIT:0\n").trimEnd()
            }
        } finally { deadline.cancel(false) }
    }

    internal fun readFile(path: String): ByteArray = Base64.decode(shell("base64 ${quote(path)}"), Base64.DEFAULT)

    internal fun writeFile(path: String, data: ByteArray) {
        val encoded = Base64.encode(data, Base64.NO_WRAP)
        shell("umask 007; head -c ${encoded.size} | base64 -d > ${quote(path)}", encoded)
        checkUi(shell("sha256sum ${quote(path)}").substringBefore(' ') == QqDraftPolicy.sha256(data)) {
            UiText(R.string.adb_write_verification_failed)
        }
    }

    companion object {
        private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "qq-adb-deadline").apply { isDaemon = true } }
        internal fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
