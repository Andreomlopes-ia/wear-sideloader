package pt.andreomlopes.wearsideloader

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class State { DISCONNECTED, BUSY, CONNECTED }

    private val executor = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val prefs = app.getSharedPreferences("sideloader", Application.MODE_PRIVATE)
    private val transcript = StringBuilder()

    private val _state = MutableLiveData(State.DISCONNECTED)
    val state: LiveData<State> = _state

    private val _log = MutableLiveData("")
    val log: LiveData<String> = _log

    private val _apkLabel = MutableLiveData<String?>(null)
    val apkLabel: LiveData<String?> = _apkLabel

    private var apkUri: Uri? = null

    val lastHost: String get() = prefs.getString(KEY_HOST, "") ?: ""
    val lastPort: String get() = prefs.getString(KEY_PORT, "") ?: ""

    /**
     * "host:port" when both are known, else just the host, else empty — used to describe what
     * Install will do. The port can be missing after autoConnect, which does not expose one.
     */
    val watchTarget: String
        get() {
            val host = lastHost
            if (host.isEmpty()) return ""
            val port = lastPort
            return if (port.isEmpty()) host else "$host:$port"
        }

    init {
        restorePersistedApk()
    }

    fun setApk(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        // Without this the grant expires with the activity result callback, and a later
        // openInputStream (after process death, or just next launch) throws SecurityException.
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        apkUri = uri
        prefs.edit().putString(KEY_APK_URI, uri.toString()).apply()
        _apkLabel.postValue(displayName(uri))
        append("Selected ${displayName(uri)} (${byteSize(uri)} bytes)")
    }

    fun pair(host: String, port: Int, code: String) = runAdb("Pairing with $host:$port") { manager ->
        if (manager.pair(host, port, code)) {
            prefs.edit().putString(KEY_HOST, host).apply()
            append("Paired. Now connect using the port shown on the watch's Wireless debugging screen — it is not the pairing port.")
        } else {
            append("Pairing rejected. Check the code and make sure the watch's pairing dialog is still open.")
        }
    }

    fun connect(host: String, port: Int) = runAdb("Connecting to $host:$port") { manager ->
        if (manager.connect(host, port)) {
            prefs.edit().putString(KEY_HOST, host).putString(KEY_PORT, port.toString()).apply()
            announceConnected(manager)
        } else {
            append("Connection refused. Confirm Wireless debugging is still on and the port is current.")
        }
    }

    fun autoConnect() = runAdb("Searching the network for a paired device") { manager ->
        if (manager.autoConnect(getApplication(), DISCOVERY_TIMEOUT_MS)) {
            manager.hostAddress.takeIf { it.isNotEmpty() }
                ?.let { prefs.edit().putString(KEY_HOST, it).apply() }
            announceConnected(manager)
        } else {
            append("No device found. Enter the IP and port manually.")
        }
    }

    fun install() {
        val uri = apkUri
        if (uri == null) {
            append("Choose an APK first.")
            return
        }
        runAdb("Installing ${displayName(uri)}", INSTALL_TIMEOUT_SECONDS) { manager ->
            if (!ensureConnected(manager)) return@runAdb
            val size = byteSize(uri)
            if (size <= 0) {
                append("Could not determine the APK size, so it cannot be streamed.")
                return@runAdb
            }
            val resolver = getApplication<Application>().contentResolver
            val result = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open ${displayName(uri)}" }
                AdbInstaller.install(manager, input, size)
            }
            append(if (result.success) "Install succeeded." else "Install failed: ${result.message}")
        }
    }

    fun listPackages() = runAdb("Listing installed packages") { manager ->
        if (!ensureConnected(manager)) return@runAdb
        val packages = AdbInstaller.listPackages(manager)
        if (packages.isEmpty()) {
            append("No third-party packages installed.")
        } else {
            append(packages.joinToString("\n") { "  $it" })
        }
    }

    fun uninstall(packageName: String) = runAdb("Uninstalling $packageName") { manager ->
        if (!ensureConnected(manager)) return@runAdb
        val result = AdbInstaller.uninstall(manager, packageName)
        append(if (result.success) "Uninstalled $packageName." else "Uninstall failed: ${result.message}")
    }

    fun disconnect() = runAdb("Disconnecting") { manager ->
        manager.disconnect()
        append("Disconnected.")
    }

    fun clearLog() {
        transcript.setLength(0)
        _log.postValue("")
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdownNow()
        watchdog.shutdownNow()
    }

    /** A getprop hiccup must not make an otherwise good connection look like a failure. */
    private fun announceConnected(manager: AdbManager) {
        val description = runCatching { AdbInstaller.deviceDescription(manager) }
            .getOrElse { "device (details unavailable: ${it.javaClass.simpleName})" }
        append("Connected to $description")
    }

    /**
     * Reconnects using the saved host/port if the socket is not currently live. This is what lets
     * Install, List and Uninstall stay tappable instead of dead-ending behind a stale DISCONNECTED
     * state — the previous version required a manual Connect after any transient failure.
     */
    private fun ensureConnected(manager: AdbManager): Boolean {
        if (manager.isConnected) return true
        val host = lastHost
        val port = lastPort.toIntOrNull()
        if (host.isEmpty() || port == null) {
            append("Not connected, and no watch is saved yet. Connect or pair first.")
            return false
        }
        append("Reconnecting to $host:$port…")
        if (manager.connect(host, port)) {
            announceConnected(manager)
            return true
        }
        append("Reconnect failed. Confirm Wireless debugging is still on and the port is current.")
        return false
    }

    /**
     * Runs [block] on the ADB thread. Connection state is read from the socket
     * (`manager.isConnected`) after the block finishes, never from whether the block itself
     * succeeded — a failing command must not make a live connection look dead and lock out
     * every other action behind it.
     *
     * Every call is bounded by a watchdog. A watch that sleeps or drops off Wi-Fi mid-stream leaves
     * the ADB reader blocked forever with no error, so without this the UI would stick on "Working…"
     * with every control disabled and no way out.
     */
    private fun runAdb(
        description: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        block: (AdbManager) -> Unit
    ) {
        _state.postValue(State.BUSY)
        append("$description…")
        val settled = AtomicBoolean(false)

        val task = FutureTask {
            val manager = AdbManager.getInstance(getApplication())
            try {
                block(manager)
            } catch (e: InterruptedException) {
                // Cancelled by the watchdog below, which already reported it.
            } catch (e: AdbPairingRequiredException) {
                append("This watch requires pairing first. Use the Pair step above.")
            } catch (e: AdbAuthenticationFailedException) {
                append("The watch rejected this phone's key. Re-pair, and accept the prompt on the watch.")
            } catch (e: Exception) {
                append("Error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            if (settled.compareAndSet(false, true)) {
                _state.postValue(if (manager.isConnected) State.CONNECTED else State.DISCONNECTED)
            }
        }

        executor.execute(task)

        watchdog.schedule({
            if (settled.compareAndSet(false, true)) {
                append("Timed out after ${timeoutSeconds}s with no response. The watch may have gone to sleep, dropped off Wi-Fi, or turned Wireless debugging off.")
                task.cancel(true)
                // Closing the socket also unblocks the reader still parked on it. The next action
                // will reconnect automatically via ensureConnected().
                runCatching { AdbManager.getInstance(getApplication()).disconnect() }
                _state.postValue(State.DISCONNECTED)
            }
        }, timeoutSeconds, TimeUnit.SECONDS)
    }

    private fun append(line: String) {
        synchronized(transcript) {
            if (transcript.isNotEmpty()) transcript.append('\n')
            transcript.append(line)
            _log.postValue(transcript.toString())
        }
    }

    private fun restorePersistedApk() {
        val saved = prefs.getString(KEY_APK_URI, null) ?: return
        val uri = Uri.parse(saved)
        val resolver = getApplication<Application>().contentResolver
        val stillValid = runCatching { resolver.openInputStream(uri)?.close() }.isSuccess
        if (stillValid) {
            apkUri = uri
            _apkLabel.postValue(displayName(uri))
        } else {
            prefs.edit().remove(KEY_APK_URI).apply()
        }
    }

    private fun displayName(uri: Uri): String = queryColumn(uri, OpenableColumns.DISPLAY_NAME)
        ?.toString() ?: uri.lastPathSegment ?: "app.apk"

    private fun byteSize(uri: Uri): Long = (queryColumn(uri, OpenableColumns.SIZE) as? Long) ?: -1L

    private fun queryColumn(uri: Uri, column: String): Any? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(column), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
                if (column == OpenableColumns.SIZE) cursor.getLong(0) else cursor.getString(0)
            }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_APK_URI = "apk_uri"
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val DEFAULT_TIMEOUT_SECONDS = 15L
        // Streaming a large APK to a watch over Wi-Fi is legitimately slow.
        private const val INSTALL_TIMEOUT_SECONDS = 600L
    }
}
