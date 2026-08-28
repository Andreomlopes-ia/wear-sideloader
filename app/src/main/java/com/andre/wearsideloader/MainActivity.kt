package com.andre.wearsideloader

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andre.wearsideloader.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.setApk(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectHost.setText(viewModel.lastHost)
        binding.pairHost.setText(viewModel.lastHost)
        binding.connectPort.setText(viewModel.lastPort)

        binding.pairButton.setOnClickListener {
            val host = binding.pairHost.required(getString(R.string.hint_pair_host)) ?: return@setOnClickListener
            val port = binding.pairPort.port() ?: return@setOnClickListener
            val code = binding.pairCode.required(getString(R.string.hint_pair_code)) ?: return@setOnClickListener
            viewModel.pair(host, port, code)
        }

        binding.connectButton.setOnClickListener {
            val host = binding.connectHost.required(getString(R.string.hint_connect_host)) ?: return@setOnClickListener
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

        viewModel.state.observe(this) { render(it) }
        viewModel.log.observe(this) {
            binding.logView.text = it
            binding.root.post { binding.root.fullScroll(View.FOCUS_DOWN) }
        }
        viewModel.apkLabel.observe(this) {
            binding.apkLabel.text = it ?: getString(R.string.no_apk_selected)
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
        binding.connectButton.isEnabled = !busy && !connected
        binding.autoConnectButton.isEnabled = !busy && !connected
        binding.disconnectButton.isEnabled = connected
        binding.chooseApkButton.isEnabled = !busy
        binding.installButton.isEnabled = connected
        binding.listPackagesButton.isEnabled = connected
        binding.uninstallButton.isEnabled = connected
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
