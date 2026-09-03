package pt.andreomlopes.wearsideloader

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import pt.andreomlopes.wearsideloader.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var hasApk = false
    private var currentState = MainViewModel.State.DISCONNECTED

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.setApk(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.watchHost.setText(viewModel.lastHost)
        binding.connectPort.setText(viewModel.lastPort)

        binding.setupHelpToggle.setOnClickListener { binding.setupHelpBody.toggleVisibility() }
        binding.pairingToggle.setOnClickListener { binding.pairingGroup.toggleVisibility() }
        binding.toolsToggle.setOnClickListener { binding.toolsGroup.toggleVisibility() }

        binding.pairButton.setOnClickListener {
            val host = binding.watchHost.required(getString(R.string.hint_watch_host)) ?: return@setOnClickListener
            val port = binding.pairPort.port() ?: return@setOnClickListener
            val code = binding.pairCode.required(getString(R.string.hint_pair_code)) ?: return@setOnClickListener
            viewModel.pair(host, port, code)
        }

        binding.connectButton.setOnClickListener {
            val host = binding.watchHost.required(getString(R.string.hint_watch_host)) ?: return@setOnClickListener
            val port = binding.connectPort.port() ?: return@setOnClickListener
            viewModel.connect(host, port)
        }

        binding.autoConnectButton.setOnClickListener { viewModel.autoConnect() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnect() }
        binding.chooseApkButton.setOnClickListener { pickApk.launch(APK_MIME_TYPES) }
        binding.installButton.setOnClickListener { viewModel.install() }
        binding.listPackagesButton.setOnClickListener { viewModel.listPackages() }
        binding.uninstallButton.setOnClickListener {
            val pkg = binding.uninstallPackage.required(getString(R.string.hint_uninstall_package))
                ?: return@setOnClickListener
            viewModel.uninstall(pkg)
        }
        binding.clearLogButton.setOnClickListener { viewModel.clearLog() }

        viewModel.state.observe(this) { state ->
            currentState = state
            render(state)
            updateInstallHint()
        }
        viewModel.log.observe(this) { text ->
            binding.logView.text = text
            if (text.isEmpty()) return@observe
            // scrollTo rather than fullScroll: the latter moves focus, which makes the outer
            // NestedScrollView jump the log into view.
            binding.logScroll.post { binding.logScroll.scrollTo(0, binding.logView.bottom) }
        }
        viewModel.apkLabel.observe(this) {
            hasApk = it != null
            binding.apkLabel.text = it ?: getString(R.string.no_apk_selected)
            render(currentState)
            updateInstallHint()
        }
    }

    private fun render(state: MainViewModel.State) {
        val busy = state == MainViewModel.State.BUSY
        val connected = state == MainViewModel.State.CONNECTED

        binding.status.setText(
            when (state) {
                MainViewModel.State.DISCONNECTED -> R.string.status_disconnected
                MainViewModel.State.BUSY -> R.string.status_busy
                MainViewModel.State.CONNECTED -> R.string.status_connected
            }
        )
        binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE

        binding.pairButton.isEnabled = !busy
        binding.connectButton.isEnabled = !busy
        binding.autoConnectButton.isEnabled = !busy
        binding.disconnectButton.isEnabled = connected && !busy
        binding.chooseApkButton.isEnabled = !busy
        // Install/List/Uninstall no longer gate on "connected": they reconnect on their own
        // (MainViewModel.ensureConnected), so the only real precondition to check here is
        // whether there is something to act on, plus not already mid-command.
        binding.installButton.isEnabled = !busy && hasApk
        binding.listPackagesButton.isEnabled = !busy
        binding.uninstallButton.isEnabled = !busy
    }

    /** Keeps the reason for Install's state visible instead of just greying it out silently. */
    private fun updateInstallHint() {
        val target = viewModel.watchTarget
        binding.installHint.text = when {
            !hasApk -> getString(R.string.install_hint_no_apk)
            currentState == MainViewModel.State.CONNECTED -> getString(R.string.install_hint_ready, target)
            target.isNotEmpty() -> getString(R.string.install_hint_will_reconnect, target)
            else -> getString(R.string.install_hint_no_target)
        }
    }

    private fun View.toggleVisibility() {
        visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun EditText.required(label: String): String? {
        val value = text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.field_required, label), Snackbar.LENGTH_SHORT).show()
            return null
        }
        return value
    }

    private fun EditText.port(): Int? {
        val value = text?.toString()?.trim()?.toIntOrNull()
        if (value == null || value !in 1..65535) {
            Snackbar.make(binding.root, R.string.invalid_port, Snackbar.LENGTH_SHORT).show()
            return null
        }
        return value
    }

    companion object {
        private val APK_MIME_TYPES = arrayOf(
            "application/vnd.android.package-archive",
            "application/octet-stream",
            "*/*"
        )
    }
}
