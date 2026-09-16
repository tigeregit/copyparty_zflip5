package com.copyparty.zflip5

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.copyparty.zflip5.databinding.ActivityMainBinding
import com.copyparty.zflip5.widget.WidgetUpdateHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ServerPreferences

    /** Spinner index 0 = Auto; 1..n = listUsableIpv4 entries */
    private var nicChoices: List<LanInterface> = emptyList()
    private var nicSpinnerReady = false

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            prefs.treeUri = uri.toString()
            val path = UriPathResolver.resolve(this, uri)
            binding.tvShareRoot.text = if (path != null) {
                getString(R.string.share_root_resolved, path)
            } else {
                getString(R.string.share_root_unresolved, uri.toString())
            }
            Toast.makeText(this, R.string.toast_root_selected, Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore */ }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = ServerPreferences(this)

        try {
            CopypartyController.ensurePython(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Python: ${e.message}", Toast.LENGTH_LONG).show()
        }

        binding.etPort.setText(prefs.port.toString())
        binding.switchReadOnly.isChecked = prefs.readOnly
        binding.etPassword.setText(prefs.password)
        updateShareRootLabel()
        setupNicSpinner()

        binding.btnPickRoot.setOnClickListener { openTree.launch(null) }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnStart.setOnClickListener { startServer() }
        binding.btnStop.setOnClickListener {
            FileServerService.stop(this)
            refreshUi()
        }
        binding.btnAllFiles.setOnClickListener { requestAllFilesAccess() }

        ensureNotificationPermission()
        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FileServerService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        setupNicSpinner()
        refreshUi()
    }

    override fun onStop() {
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    private fun setupNicSpinner() {
        nicChoices = NetworkUtils.listUsableIpv4(this)
        val labels = mutableListOf(getString(R.string.nic_auto))
        for (item in nicChoices) {
            labels.add(getString(R.string.nic_item_fmt, item.name, item.ip))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        nicSpinnerReady = false
        binding.spinnerNic.adapter = adapter

        // Restore selection
        val savedIp = prefs.bindIp
        var idx = 0
        if (!savedIp.isNullOrBlank()) {
            val found = nicChoices.indexOfFirst { it.ip == savedIp }
            if (found >= 0) idx = found + 1
        }
        binding.spinnerNic.setSelection(idx.coerceIn(0, labels.size - 1), false)

        binding.spinnerNic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!nicSpinnerReady) return
                applyNicSelection(position, persist = true)
                refreshUi()
                WidgetUpdateHelper.requestUpdate(this@MainActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        nicSpinnerReady = true
    }

    private fun applyNicSelection(position: Int, persist: Boolean) {
        if (position <= 0) {
            if (persist) {
                prefs.bindIp = null
                prefs.bindIface = null
            }
        } else {
            val item = nicChoices.getOrNull(position - 1) ?: return
            if (persist) {
                prefs.bindIp = item.ip
                prefs.bindIface = item.name
            }
        }
    }

    private fun currentNicPosition(): Int = binding.spinnerNic.selectedItemPosition

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun updateShareRootLabel() {
        val uri = prefs.treeUriOrNull()
        if (uri == null) {
            binding.tvShareRoot.text = getString(R.string.share_root_none)
            return
        }
        val path = UriPathResolver.resolve(this, uri)
        binding.tvShareRoot.text = if (path != null) {
            getString(R.string.share_root_resolved, path)
        } else {
            getString(R.string.share_root_unresolved, uri.toString())
        }
    }

    private fun saveSettings() {
        val port = binding.etPort.text.toString().toIntOrNull() ?: ServerPreferences.DEFAULT_PORT
        prefs.port = port
        prefs.readOnly = binding.switchReadOnly.isChecked
        prefs.password = binding.etPassword.text.toString().trim()
        applyNicSelection(currentNicPosition(), persist = true)
        binding.etPort.setText(prefs.port.toString())
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        WidgetUpdateHelper.requestUpdate(this)
        refreshUi()
    }

    private fun startServer() {
        saveSettings()
        if (prefs.treeUri == null) {
            Toast.makeText(this, R.string.toast_need_root, Toast.LENGTH_LONG).show()
            return
        }
        if (!hasAllFilesAccess()) {
            Toast.makeText(this, R.string.toast_need_all_files, Toast.LENGTH_LONG).show()
            requestAllFilesAccess()
            return
        }
        val path = UriPathResolver.resolve(this, prefs.treeUriOrNull()!!)
        if (path == null) {
            Toast.makeText(this, R.string.toast_path_resolve_fail, Toast.LENGTH_LONG).show()
            return
        }
        FileServerService.start(this)
        binding.root.postDelayed({ refreshUi() }, 800)
    }

    private fun refreshUi() {
        val running = FileServerService.running || CopypartyController.isRunning(this)
        binding.tvStatus.text = if (running) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
        binding.tvStatus.setTextColor(
            if (running) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        )
        val url = if (running) {
            FileServerService.lastUrl ?: NetworkUtils.baseUrl(this, prefs)
        } else {
            NetworkUtils.baseUrl(this, prefs)
        }
        binding.tvLanUrl.text = url
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        // Changing NIC while running requires restart to re-bind
        binding.spinnerNic.isEnabled = !running
        binding.tvAllFilesStatus.text = if (hasAllFilesAccess()) {
            getString(R.string.all_files_granted)
        } else {
            getString(R.string.all_files_missing)
        }
        val err = FileServerService.lastError
        binding.tvError.text = err ?: ""
        binding.tvError.visibility =
            if (err.isNullOrBlank()) View.GONE else View.VISIBLE

        try {
            val bmp = makeQr(url, 512)
            binding.ivQr.setImageBitmap(bmp)
        } catch (_: Exception) {
            binding.ivQr.setImageDrawable(null)
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
