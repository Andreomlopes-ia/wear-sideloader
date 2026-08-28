package com.andre.wearsideloader

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.InputStream

/**
 * Thin wrappers over raw ADB services. Everything here blocks and must run off the main thread.
 */
object AdbInstaller {

    /** Runs a one-shot command and returns its combined output. */
    fun shell(manager: AbsAdbConnectionManager, command: String): String =
        manager.openStream("shell:$command").use { stream ->
            stream.openInputStream().bufferedReader().readText().trim()
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
        val output = manager.openStream(service).use { stream ->
            stream.openOutputStream().use { out ->
                apk.copyTo(out, DEFAULT_BUFFER_SIZE)
                out.flush()
            }
            stream.openInputStream().bufferedReader().readText().trim()
        }
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

    fun deviceDescription(manager: AbsAdbConnectionManager): String {
        val model = shell(manager, "getprop ro.product.model")
        val release = shell(manager, "getprop ro.build.version.release")
        val sdk = shell(manager, "getprop ro.build.version.sdk")
        return "$model (Android $release, API $sdk)"
    }

    data class InstallResult(val success: Boolean, val message: String)

    private const val DEFAULT_BUFFER_SIZE = 64 * 1024
}
