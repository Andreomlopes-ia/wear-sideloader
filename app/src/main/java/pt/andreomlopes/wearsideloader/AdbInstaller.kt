package pt.andreomlopes.wearsideloader

import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
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
                stream.openInputStream().bufferedReader().readText().trim()
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

    data class InstallResult(val success: Boolean, val message: String)

    private fun openAndRead(manager: AbsAdbConnectionManager, service: String): String =
        manager.openStream(service).use { stream ->
            stream.openInputStream().bufferedReader().readText().trim()
        }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.currentTimeMillis()
        val result = block()
        return result to (System.currentTimeMillis() - start)
    }

    private const val DEFAULT_BUFFER_SIZE = 64 * 1024
}
