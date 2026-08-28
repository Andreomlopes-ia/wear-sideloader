package com.andre.wearsideloader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.util.concurrent.Executors

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class State { DISCONNECTED, BUSY, CONNECTED }

    private val executor = Executors.newSingleThreadExecutor()
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

    fun setApk(uri: Uri) {
        apkUri = uri
        _apkLabel.postValue(displayName(uri))
        append("Selected ${displayName(uri)} (${byteSize(uri)} bytes)")
    }

    fun pair(host: String, port: Int, code: String) = runAdb("Pairing with $host:$port") { manager ->
        if (manager.pair(host, port, code)) {
            append("Paired. Now connect using the port shown on the watch's Wireless debugging screen — it is not the pairing port.")
        } else {
            append("Pairing rejected. Check the code and make sure the watch's pairing dialog is still open.")
        }
        false
    }

    fun connect(host: String, port: Int) = runAdb("Connecting to $host:$port") { manager ->
        if (manager.connect(host, port)) {
            prefs.edit().putString(KEY_HOST, host).putString(KEY_PORT, port.toString()).apply()
            append("Connected to ${AdbInstaller.deviceDescription(manager)}")
            true
        } else {
            append("Connection refused. Confirm Wireless debugging is still on and the port is current.")
            false
        }
    }

    fun autoConnect() = runAdb("Searching the network for a paired device") { manager ->
        if (manager.autoConnect(getApplication(), DISCOVERY_TIMEOUT_MS)) {
            append("Connected to ${AdbInstaller.deviceDescription(manager)}")
            true
        } else {
            append("No device found. Enter the IP and port manually.")
            false
        }
    }

    fun install() {
        val uri = apkUri
        if (uri == null) {
            append("Choose an APK first.")
            return
        }
        runAdb("Installing ${displayName(uri)}") { manager ->
            val size = byteSize(uri)
            if (size <= 0) {
                append("Could not determine the APK size, so it cannot be streamed.")
                return@runAdb true
            }
            val resolver = getApplication<Application>().contentResolver
            val result = resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open ${displayName(uri)}" }
                AdbInstaller.install(manager, input, size)
            }
            append(if (result.success) "Install succeeded." else "Install failed: ${result.message}")
            true
        }
    }

    fun listPackages() = runAdb("Listing installed packages") { manager ->
        val packages = AdbInstaller.listPackages(manager)
        if (packages.isEmpty()) {
            append("No third-party packages installed.")
        } else {
            append(packages.joinToString("\n") { "  $it" })
        }
        true
    }

    fun uninstall(packageName: String) = runAdb("Uninstalling $packageName") { manager ->
        val result = AdbInstaller.uninstall(manager, packageName)
        append(if (result.success) "Uninstalled $packageName." else "Uninstall failed: ${result.message}")
        true
    }

    fun disconnect() = runAdb("Disconnecting") { manager ->
        manager.disconnect()
        append("Disconnected.")
        false
    }

    fun clearLog() {
        transcript.setLength(0)
        _log.postValue("")
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdownNow()
    }

    /**
     * Runs [block] on the ADB thread. The block reports whether the connection is live afterwards,
     * which becomes the new UI state.
     */
    private fun runAdb(description: String, block: (AdbManager) -> Boolean) {
        _state.postValue(State.BUSY)
        append("$description...")
        executor.execute {
            val connected = try {
                val manager = AdbManager.getInstance(getApplication())
                block(manager)
            } catch (e: AdbPairingRequiredException) {
                append("This watch requires pairing first. Use the Pair step above.")
                false
            } catch (e: AdbAuthenticationFailedException) {
                append("The watch rejected this phone's key. Re-pair, and accept the prompt on the watch.")
                false
            } catch (e: Exception) {
                append("Error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
                false
            }
            _state.postValue(if (connected) State.CONNECTED else State.DISCONNECTED)
        }
    }

    private fun append(line: String) {
        synchronized(transcript) {
            if (transcript.isNotEmpty()) transcript.append('\n')
            transcript.append(line)
            _log.postValue(transcript.toString())
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
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
    }
}
