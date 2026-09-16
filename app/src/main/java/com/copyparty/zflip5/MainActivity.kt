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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    /** Usable ifaces for multi-choice (after Auto + 0.0.0.0 rows). */
    private var nicChoices: List<LanInterface> = emptyList()

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
        refreshNicChoices()
        updateNicSummary()

        binding.btnNicSelect.setOnClickListener { showNicMultiChoiceDialog() }
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
        refreshNicChoices()
        updateNicSummary()
        refreshUi()
    }

    override fun onStop() {
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    private fun refreshNicChoices() {
        nicChoices = NetworkUtils.listUsableIpv4(this)
    }

    private fun updateNicSummary() {
        binding.tvNicSummary.text = when {
            prefs.isBindAuto() -> getString(R.string.nic_summary_auto)
            prefs.isBindAll() -> getString(R.string.nic_summary_all)
            else -> {
                val ips = prefs.selectedBindIpList()
                val labels = ips.map { ip ->
                    val name = nicChoices.firstOrNull { it.ip == ip }?.name
                    if (name != null) getString(R.string.nic_item_fmt, name, ip) else ip
                }
                getString(R.string.nic_summary_ips, labels.joinToString(", "))
            }
        }
    }

    private fun showNicMultiChoiceDialog() {
        refreshNicChoices()
        val labels = mutableListOf(
            getString(R.string.nic_auto),
            getString(R.string.nic_all)
        )
        for (item in nicChoices) {
            labels.add(getString(R.string.nic_item_fmt, item.name, item.ip))
        }
        val checked = BooleanArray(labels.size)
        when {
            prefs.isBindAuto() -> checked[0] = true
            prefs.isBindAll() -> checked[1] = true
            else -> {
                val selected = prefs.selectedBindIpList().toSet()
                for (i in nicChoices.indices) {
                    if (nicChoices[i].ip in selected) checked[i + 2] = true
                }
                if (checked.none { it }) checked[0] = true
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nic_dialog_title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { d, which, isChecked ->
                val list = (d as AlertDialog).listView ?: return@setMultiChoiceItems
                if (isChecked) {
                    when (which) {
                        0 -> { // Auto exclusive
                            for (i in checked.indices) {
                                checked[i] = (i == 0)
                                list.setItemChecked(i, checked[i])
                            }
                        }
                        1 -> { // 0.0.0.0 exclusive with specific IPs
                            checked[0] = false
                            checked[1] = true
                            list.setItemChecked(0, false)
                            list.setItemChecked(1, true)
                            for (i in 2 until checked.size) {
                                checked[i] = false
                                list.setItemChecked(i, false)
                            }
                        }
                        else -> {
                            checked[0] = false
                            checked[1] = false
                            checked[which] = true
                            list.setItemChecked(0, false)
                            list.setItemChecked(1, false)
                            list.setItemChecked(which, true)
                        }
                    }
                } else {
                    checked[which] = false
                    if (checked.none { it }) {
                        checked[0] = true
                        list.setItemChecked(0, true)
                    }
                }
            }
            .setPositiveButton(R.string.nic_ok) { _, _ ->
                persistNicSelection(checked)
                updateNicSummary()
                refreshUi()
                WidgetUpdateHelper.requestUpdate(this)
            }
            .setNegativeButton(R.string.nic_cancel, null)
            .create()
        dialog.show()
    }

    private fun persistNicSelection(checked: BooleanArray) {
        when {
            checked.getOrElse(0) { false } -> {
                prefs.bindIps = ""
                prefs.bindIface = null
            }
            checked.getOrElse(1) { false } -> {
                prefs.bindIps = ServerPreferences.BIND_ALL
                prefs.bindIface = null
            }
            else -> {
                val ips = mutableListOf<String>()
                for (i in nicChoices.indices) {
                    if (checked.getOrElse(i + 2) { false }) {
                        ips.add(nicChoices[i].ip)
                    }
                }
                if (ips.isEmpty()) {
                    prefs.bindIps = ""
                    prefs.bindIface = null
                } else {
                    prefs.bindIps = ips.joinToString(",")
                    // Keep first iface name for legacy field when single
                    prefs.bindIface = if (ips.size == 1) {
                        nicChoices.firstOrNull { it.ip == ips[0] }?.name
                    } else {
                        null
                    }
                }
            }
        }
    }

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
        // bindIps already persisted via dialog; summary refresh only
        binding.etPort.setText(prefs.port.toString())
        updateNicSummary()
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

        // Specific binds: show all selected URLs; auto/all: one primary (service lastUrl when running)
        val displayUrls = if (!prefs.isBindAuto() && !prefs.isBindAll()) {
            NetworkUtils.baseUrls(this, prefs)
        } else if (running) {
            listOf(FileServerService.lastUrl ?: NetworkUtils.baseUrl(this, prefs))
        } else {
            NetworkUtils.baseUrls(this, prefs)
        }
        binding.tvLanUrl.text = displayUrls.joinToString("\n")

        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        // Changing NIC while running requires restart to re-bind
        binding.btnNicSelect.isEnabled = !running
        binding.tvAllFilesStatus.text = if (hasAllFilesAccess()) {
            getString(R.string.all_files_granted)
        } else {
            getString(R.string.all_files_missing)
        }
        val err = FileServerService.lastError
        binding.tvError.text = err ?: ""
        binding.tvError.visibility =
            if (err.isNullOrBlank()) View.GONE else View.VISIBLE

        updateNicSummary()

        try {
            // QR encodes primary URL only
            val primary = displayUrls.firstOrNull()
                ?: NetworkUtils.baseUrl(this, prefs)
            val bmp = makeQr(primary, 512)
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
