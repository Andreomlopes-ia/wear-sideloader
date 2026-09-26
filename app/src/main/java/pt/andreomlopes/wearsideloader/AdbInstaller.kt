package pt.andreomlopes.wearsideloader

import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Thin wrappers over raw ADB services. Everything here blocks and must run off the main thread.
 */
object AdbInstaller {

    private const val TAG = "AdbInstaller"

    /**
     * Runs a one-shot command and returns its combined output.
     *
     * Whether `shell:` actually carries stream data to a given watch is unverified — the only
     * environment available while building this never let it be tested end to end. If `shell:`
     * comes back empty, this retries once over `exec:` (which avoids the pty a shell stream runs
     * through) and logs which transport actually produced output, so the next real run settles it.
     */
    fun shell(manager: AbsAdbConnectionManager, command: String): String {
        val (primary, primaryMs) = timed { openAndRead(manager, "shell:$command") }
        if (primary.isNotEmpty()) {
            Log.d(TAG, "shell '$command' -> shell: (${primaryMs}ms, ${primary.length} chars)")
            return primary
        }
        val (fallback, fallbackMs) = timed { openAndRead(manager, "exec:$command") }
        Log.d(
            TAG,
            "shell '$command' -> shell: empty (${primaryMs}ms), exec: (${fallbackMs}ms, ${fallback.length} chars)"
        )
        return fallback
    }

    /**
     * Streams an APK straight into the package manager's stdin, the way `adb install` does.
     *
     * Uses `exec:` rather than `shell:` because a shell stream runs through a pty that mangles
     * binary payloads. [size] must be exact — the daemon reads precisely that many bytes and
     * will block forever if it is short.
     */
    fun install(
        manager: AbsAdbConnectionManager,
        apk: InputStream,
        size: Long,
        // -t is on by default because debug builds carry android:testOnly="true", which otherwise
        // fails with INSTALL_FAILED_TEST_ONLY — the usual first stumble when sideloading.
        extraArgs: String = "-r -t"
    ): InstallResult {
        val service = "exec:cmd package install $extraArgs -S $size"
        val (output, ms) = timed {
            manager.openStream(service).use { stream ->
                stream.openOutputStream().use { out ->
                    apk.copyTo(out, DEFAULT_BUFFER_SIZE)
                    out.flush()
                }
                readToEnd(stream).toString(Charsets.UTF_8).trim()
            }
        }
        Log.d(TAG, "install $size bytes in ${ms}ms: $output")
        return InstallResult(output.startsWith("Success"), output.ifEmpty { "No response from device" })
    }

    fun uninstall(manager: AbsAdbConnectionManager, packageName: String): InstallResult {
        val output = shell(manager, "pm uninstall $packageName")
        return InstallResult(output.startsWith("Success"), output.ifEmpty { "No response from device" })
    }

    /** Third-party packages only — the full list is hundreds of system entries on a watch. */
    fun listPackages(manager: AbsAdbConnectionManager): List<String> =
        shell(manager, "pm list packages -3")
            .lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .toList()

    /** One round trip rather than three — every extra stream is another chance to stall. */
    fun deviceDescription(manager: AbsAdbConnectionManager): String {
        val props = shell(
            manager,
            "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk"
        ).lines().map { it.trim() }
        val model = props.getOrNull(0).orEmpty().ifEmpty { "unknown device" }
        val release = props.getOrNull(1).orEmpty().ifEmpty { "?" }
        val sdk = props.getOrNull(2).orEmpty().ifEmpty { "?" }
        return "$model (Android $release, API $sdk)"
    }

    /** PNG bytes of the watch's current screen. Throws with the watch's own error text otherwise. */
    fun screenshot(manager: AbsAdbConnectionManager): ByteArray {
        val (bytes, ms) = timed {
            manager.openStream("exec:screencap -p").use { readToEnd(it) }
        }
        Log.d(TAG, "screencap ${bytes.size} bytes in ${ms}ms")
        if (!bytes.startsWith(PNG_SIGNATURE)) {
            error(bytes.toString(Charsets.US_ASCII).trim().take(200).ifEmpty { "No image returned" })
        }
        // readToEnd can't tell a finished stream from a dropped one, so check the PNG's own end marker.
        if (!bytes.hasPngEnd()) error("Screenshot was cut off after ${bytes.size} bytes. Try again.")
        return bytes
    }

    /**
     * libadb-android signals end-of-stream by throwing IOException("Stream closed.") instead of
     * returning -1 whenever the reader drained the queue before the peer's close arrived — which
     * is the normal case for any output bigger than a packet or two. The data already read is
     * complete; only that final read is wrong.
     */
    private fun readToEnd(stream: AdbStream): ByteArray {
        val out = ByteArrayOutputStream()
        val input = stream.openInputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (e.message?.startsWith("Stream closed") == true) -1 else throw e
            }
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** A complete PNG ends with an IEND chunk: the type "IEND" followed by its 4-byte CRC. */
    private fun ByteArray.hasPngEnd(): Boolean =
        size >= 12 && String(this, size - 8, 4, Charsets.US_ASCII) == "IEND"

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    data class InstallResult(val success: Boolean, val message: String)

    private fun openAndRead(manager: AbsAdbConnectionManager, service: String): String =
        manager.openStream(service).use { stream ->
            readToEnd(stream).toString(Charsets.UTF_8).trim()
        }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.currentTimeMillis()
        val result = block()
        return result to (System.currentTimeMillis() - start)
    }

    private const val DEFAULT_BUFFER_SIZE = 64 * 1024
}
