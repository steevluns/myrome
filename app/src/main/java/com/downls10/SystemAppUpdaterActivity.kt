package com.downls10

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class SystemAppUpdaterActivity : AppCompatActivity() {

    private lateinit var updater: SystemAppUpdater
    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: SystemAppAdapter
    private lateinit var allApps: List<SystemAppUpdater.SystemAppInfo>
    private var currentFilter: FilterType = FilterType.ALL
    private var selectedApkFile: File? = null
    private var targetAppForInstall: SystemAppUpdater.SystemAppInfo? = null
    private var updatesManifest: SystemAppUpdater.UpdatesManifest? = null
    private var appsWithUpdates: Set<String> = emptySet()

    // Activity Results
    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handlePickedApk(uri)
            }
        }
    }

    private val requestInstallPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && selectedApkFile != null && targetAppForInstall != null) {
            installSelectedApk()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    enum class FilterType {
        ALL, SYSTEM_ONLY, UPDATED_ONLY, AVAILABLE_UPDATES, NEEDS_UPDATE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_app_updater)

        updater = SystemAppUpdater(this)
        initViews()
        setupToolbar()
        setupRecyclerView()
        setupListeners()
        checkRootAndLoadApps()
    }

    private fun initViews() {
        rvApps = findViewById(R.id.rvApps)
        val emptyState = findViewById<View>(R.id.emptyState)
        emptyState.visibility = View.GONE
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun setupRecyclerView() {
        adapter = SystemAppAdapter()
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter
    }

    private fun setupListeners() {
        // Search
        val etSearch = findViewById<TextInputEditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filter Chips
        val chipAll = findViewById<Chip>(R.id.chipAll)
        val chipSystemOnly = findViewById<Chip>(R.id.chipSystemOnly)
        val chipUpdatedOnly = findViewById<Chip>(R.id.chipUpdatedOnly)
        val chipAvailableUpdates = findViewById<Chip>(R.id.chipAvailableUpdates)
        val chipNeedsUpdate = findViewById<Chip>(R.id.chipNeedsUpdate)

        val chips = listOf(chipAll, chipSystemOnly, chipUpdatedOnly, chipAvailableUpdates, chipNeedsUpdate)
        chips.forEach { chip ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    chips.filter { it != chip }.forEach { it.isChecked = false }
                    when (chip.id) {
                        R.id.chipAll -> currentFilter = FilterType.ALL
                        R.id.chipSystemOnly -> currentFilter = FilterType.SYSTEM_ONLY
                        R.id.chipUpdatedOnly -> currentFilter = FilterType.UPDATED_ONLY
                        R.id.chipAvailableUpdates -> currentFilter = FilterType.AVAILABLE_UPDATES
                        R.id.chipNeedsUpdate -> currentFilter = FilterType.NEEDS_UPDATE
                    }
                    adapter.applyFilter(currentFilter)
                }
            }
        }

        // Buttons
        findViewById<MaterialButton>(R.id.btnRefreshApps).setOnClickListener { checkRootAndLoadApps() }
        findViewById<MaterialButton>(R.id.btnPickApk).setOnClickListener { pickApkFile() }
        findViewById<MaterialButton>(R.id.btnRecheckRoot).setOnClickListener { checkRootAndLoadApps() }
        findViewById<FloatingActionButton>(R.id.fabScanApks).setOnClickListener { scanApkFiles() }
    }

    private fun checkRootAndLoadApps() {
        val rootStatusIcon = findViewById<ImageView>(R.id.rootStatusIcon)
        val rootStatusTitle = findViewById<TextView>(R.id.rootStatusTitle)
        val rootStatusSubtitle = findViewById<TextView>(R.id.rootStatusSubtitle)
        val btnRecheckRoot = findViewById<MaterialButton>(R.id.btnRecheckRoot)
        val fabScanApks = findViewById<FloatingActionButton>(R.id.fabScanApks)

        Executors.newSingleThreadExecutor().execute {
            val hasRoot = updater.hasRootAccess()
            runOnUiThread {
                if (hasRoot) {
                    rootStatusIcon.setImageResource(R.drawable.ic_check_circle_24dp)
                    rootStatusIcon.setColorFilter(getColor(R.color.design_default_color_primary))
                    rootStatusTitle.text = getString(R.string.root_access_granted)
                    rootStatusSubtitle.text = getString(R.string.root_access_granted_desc)
                    btnRecheckRoot.visibility = View.GONE
                    fabScanApks.isVisible = true
                    loadSystemApps()
                } else {
                    rootStatusIcon.setImageResource(R.drawable.ic_warning_24dp)
                    rootStatusIcon.setColorFilter(getColor(R.color.design_default_color_error))
                    rootStatusTitle.text = getString(R.string.root_access_required)
                    rootStatusSubtitle.text = getString(R.string.root_access_required_desc)
                    btnRecheckRoot.visibility = View.VISIBLE
                    fabScanApks.isVisible = false
                    showEmptyState(getString(R.string.root_required_title), getString(R.string.root_required_desc))
                }
            }
        }
    }

    private fun loadSystemApps() {
        Executors.newSingleThreadExecutor().execute {
            val apps = updater.getSystemApps()
            // Load updates manifest
            val manifest = updater.loadUpdatesManifest()
            runOnUiThread {
                allApps = apps
                updatesManifest = manifest
                if (manifest != null) {
                    checkForAvailableUpdates(manifest!!)
                }
                adapter.submitList(apps)
                updateStats(apps.size)
                findViewById<View>(R.id.emptyState).visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun checkForAvailableUpdates(manifest: SystemAppUpdater.UpdatesManifest) {
        val updates = updater.checkAllUpdates(manifest)
        appsWithUpdates = updates.map { (app, _) -> app.packageName }.toSet()
        runOnUiThread {
            val chipAvailableUpdates = findViewById<Chip>(R.id.chipAvailableUpdates)
            if (appsWithUpdates.isNotEmpty()) {
                chipAvailableUpdates.text = getString(R.string.updates_found, appsWithUpdates.size)
                chipAvailableUpdates.isVisible = true
            } else {
                chipAvailableUpdates.text = getString(R.string.no_updates_available)
                chipAvailableUpdates.isVisible = true
            }
            adapter.applyFilter(currentFilter)
        }
    }

    private fun updateStats(count: Int) {
        val tvStats = findViewById<TextView>(R.id.tvStats)
        tvStats.text = getString(R.string.apps_count_format, count)
    }

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Environment.getExternalStorageDirectory().toUri())
        }
        pickApkLauncher.launch(intent)
    }

    private fun handlePickedApk(uri: Uri) {
        try {
            val file = File(uri.path ?: "")
            if (file.exists() && file.extension.lowercase() == "apk") {
                selectedApkFile = file
                val apkInfo = updater.getApkInfo(file)
                if (apkInfo != null) {
                    // Find matching installed app
                    targetAppForInstall = allApps.firstOrNull { it.packageName == apkInfo.packageName }
                    if (targetAppForInstall != null) {
                        showInstallConfirmDialog(apkInfo, targetAppForInstall!!)
                    } else {
                        // Not a system app, ask if they want to install anyway
                        showInstallNewAppDialog(apkInfo)
                    }
                } else {
                    Toast.makeText(this, R.string.invalid_apk_file, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, R.string.select_valid_apk, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("SystemAppUpdater", "Error handling picked APK", e)
            Toast.makeText(this, R.string.error_reading_apk, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInstallConfirmDialog(apkInfo: SystemAppUpdater.SystemAppInfo, installedApp: SystemAppUpdater.SystemAppInfo) {
        val isNewer = apkInfo.versionCode > installedApp.versionCode
        val message = StringBuilder()
            .append(getString(R.string.install_apk_confirm, apkInfo.appName))
            .append("\n\n")
            .append(getString(R.string.current_version, installedApp.versionName, installedApp.versionCode))
            .append("\n")
            .append(getString(R.string.new_version, apkInfo.versionName, apkInfo.versionCode))
            .append("\n\n")
            .append(if (isNewer) getString(R.string.version_newer) else getString(R.string.version_older_or_same))

        AlertDialog.Builder(this)
            .setTitle(R.string.install_update)
            .setMessage(message)
            .setPositiveButton(R.string.install) { _, _ ->
                installSelectedApk()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showInstallNewAppDialog(apkInfo: SystemAppUpdater.SystemAppInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.install_new_app)
            .setMessage(getString(R.string.install_new_app_desc, apkInfo.appName, apkInfo.versionName, apkInfo.versionCode))
            .setPositiveButton(R.string.install) { _, _ ->
                targetAppForInstall = null // Install as new
                installSelectedApk()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun installSelectedApk() {
        selectedApkFile?.let { file ->
            val targetPackage = targetAppForInstall?.packageName ?: ""
            val progressDialog = AlertDialog.Builder(this)
                .setTitle(R.string.installing)
                .setMessage(getString(R.string.installing_apk, file.name))
                .setCancelable(false)
                .create()
            progressDialog.show()

            Executors.newSingleThreadExecutor().execute {
                val result = if (targetAppForInstall != null) {
                    updater.installSystemApk(file, targetAppForInstall!!.packageName)
                } else {
                    updater.installSystemApk(file)
                }

                runOnUiThread {
                    progressDialog.dismiss()
                    showResultSnackbar(result)
                    if (result.success) {
                        selectedApkFile = null
                        targetAppForInstall = null
                        loadSystemApps() // Refresh list
                    }
                }
            }
        }
    }

    private fun showResultSnackbar(result: SystemAppUpdater.InstallResult) {
        val view = findViewById<View>(android.R.id.content)
        Snackbar.make(view, result.message, Snackbar.LENGTH_LONG)
            .setAction(if (result.success) R.string.ok else R.string.retry) { _ ->
                if (!result.success) installSelectedApk()
            }
            .show()
    }

    private fun scanApkFiles() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.scanning)
            .setMessage(R.string.scanning_apk_files)
            .setCancelable(false)
            .create()
        progressDialog.show()

        Executors.newSingleThreadExecutor().execute {
            val apkFiles = updater.findApkFiles()
            runOnUiThread {
                progressDialog.dismiss()
                if (apkFiles.isNotEmpty()) {
                    showApkSelectionDialog(apkFiles)
                } else {
                    Toast.makeText(this, R.string.no_apk_files_found, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showApkSelectionDialog(apkFiles: List<File>) {
        val items = apkFiles.map { file ->
            val info = updater.getApkInfo(file)
            if (info != null) {
                "${info.appName} (v${info.versionName}, code ${info.versionCode})\n${file.parentFile.name}/${file.name}"
            } else {
                file.name
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.select_apk_to_install)
            .setItems(items) { _, which ->
                val file = apkFiles[which]
                selectedApkFile = file
                val apkInfo = updater.getApkInfo(file)
                if (apkInfo != null) {
                    targetAppForInstall = allApps.firstOrNull { it.packageName == apkInfo.packageName }
                    if (targetAppForInstall != null) {
                        showInstallConfirmDialog(apkInfo, targetAppForInstall!!)
                    } else {
                        showInstallNewAppDialog(apkInfo)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstallUpdate(app: SystemAppUpdater.SystemAppInfo, updateInfo: SystemAppUpdater.UpdateInfo) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.downloading_apk)
            .setMessage(getString(R.string.downloading_apk, app.appName))
            .setCancelable(false)
            .create()
        progressDialog.show()

        Executors.newSingleThreadExecutor().execute {
            val result = downloadApk(updateInfo)
            runOnUiThread {
                progressDialog.dismiss()
                if (result.success) {
                    val downloadedFile = result.message // file path
                    showInstallConfirmDialogForDownload(app, updateInfo, File(downloadedFile))
                } else {
                    Toast.makeText(this, getString(R.string.download_failed, result.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadApk(updateInfo: SystemAppUpdater.UpdateInfo): SystemAppUpdater.InstallResult {
        if (!updater.hasRootAccess()) {
            return SystemAppUpdater.InstallResult(false, "يتطلب صلاحيات Root", SystemAppUpdater.RESULT_NO_ROOT)
        }

        val url = updateInfo.downloadUrl
        val fileName = "${updateInfo.packageName}_${updateInfo.versionCode}.apk"
        val destFile = File(cacheDir, fileName)

        return try {
            val connection = java.net.URL(url).openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            
            val inputStream = connection.getInputStream()
            val outputStream = java.io.FileOutputStream(destFile)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Verify SHA256 if provided
            if (updateInfo.sha256 != null) {
                val calculatedSha256 = calculateSha256(destFile)
                if (calculatedSha256 != updateInfo.sha256) {
                    destFile.delete()
                    return SystemAppUpdater.InstallResult(false, "فحص SHA256 فشل", SystemAppUpdater.RESULT_CMD_FAILED)
                }
            }

            SystemAppUpdater.InstallResult(true, destFile.absolutePath, SystemAppUpdater.RESULT_OK)
        } catch (e: Exception) {
            if (destFile.exists()) destFile.delete()
            SystemAppUpdater.InstallResult(false, e.message ?: "تحميل فاشل", SystemAppUpdater.RESULT_DOWNLOAD_FAILED)
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val inputStream = java.io.FileInputStream(file)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        inputStream.close()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun showInstallConfirmDialogForDownload(app: SystemAppUpdater.SystemAppInfo, updateInfo: SystemAppUpdater.UpdateInfo, apkFile: File) {
        val message = StringBuilder()
            .append(getString(R.string.install_from_download))
            .append("\n\n")
            .append(getString(R.string.current_version, app.versionName, app.versionCode))
            .append("\n")
            .append(getString(R.string.new_version, updateInfo.versionName, updateInfo.versionCode))
            .append("\n\n")
            .append("الملف: ${apkFile.name}")
            .append("\nالحجم: ${formatFileSize(apkFile.length())}")

        AlertDialog.Builder(this)
            .setTitle(R.string.install_update)
            .setMessage(message)
            .setPositiveButton(R.string.install) { _, _ ->
                installDownloadedApk(apkFile, app.packageName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun installDownloadedApk(apkFile: File, packageName: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.installing)
            .setMessage(getString(R.string.installing_apk, apkFile.name))
            .setCancelable(false)
            .create()
        progressDialog.show()

        Executors.newSingleThreadExecutor().execute {
            val result = updater.installSystemApk(apkFile, packageName)
            runOnUiThread {
                progressDialog.dismiss()
                showResultSnackbar(result)
                if (result.success) {
                    loadSystemApps()
                }
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 * 1024 -> String.format("%.1f GB", size / (1024.0 * 1024 * 1024))
            size >= 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
            size >= 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> "$size B"
        }
    }

    private fun showEmptyState(title: String, message: String) {
        val emptyState = findViewById<View>(R.id.emptyState)
        emptyState.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        (emptyState.findViewById<TextView>(0) as TextView).text = title
        (emptyState.findViewById<TextView>(1) as TextView).text = message
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_system_app_updater, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_show_user_apps -> {
                // Could toggle to show user apps too
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(R.string.about_system_app_updater)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // Adapter
    inner class SystemAppAdapter : RecyclerView.Adapter<SystemAppAdapter.ViewHolder>() {

        private var filteredApps: List<SystemAppUpdater.SystemAppInfo> = emptyList()
        private var searchQuery = ""

        fun submitList(apps: List<SystemAppUpdater.SystemAppInfo>) {
            allApps = apps
            applyFilter(currentFilter)
        }

        fun filter(query: String) {
            searchQuery = query.lowercase()
            applyFilter(currentFilter)
        }

        fun applyFilter(filter: FilterType) {
            currentFilter = filter
            filteredApps = allApps.filter { app ->
                val matchesSearch = searchQuery.isBlank() || 
                    app.appName.lowercase().contains(searchQuery) ||
                    app.packageName.lowercase().contains(searchQuery)
                
                val matchesFilter = when (filter) {
                    FilterType.ALL -> true
                    FilterType.SYSTEM_ONLY -> !app.isUpdatedSystemApp
                    FilterType.UPDATED_ONLY -> app.isUpdatedSystemApp
                    FilterType.AVAILABLE_UPDATES -> appsWithUpdates.contains(app.packageName)
                    FilterType.NEEDS_UPDATE -> false // TODO: implement when APK scanning is integrated
                }
                
                matchesSearch && matchesFilter
            }
            notifyDataSetChanged()
            findViewById<View>(R.id.emptyState).visibility = if (filteredApps.isEmpty()) View.VISIBLE else View.GONE
            rvApps.visibility = if (filteredApps.isEmpty()) View.GONE else View.VISIBLE
            updateStats(filteredApps.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_system_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(filteredApps[position])
        }

        override fun getItemCount(): Int = filteredApps.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPackage: TextView = itemView.findViewById(R.id.tvPackageName)
            private val tvVersion: TextView = itemView.findViewById(R.id.tvVersion)
            private val chipType: Chip = itemView.findViewById(R.id.chipType)
            private val tvUpdateAvailable: TextView = itemView.findViewById(R.id.tvUpdateAvailable)
            private val btnInstall: MaterialButton = itemView.findViewById(R.id.btnInstall)
            private val btnDisable: MaterialButton = itemView.findViewById(R.id.btnDisable)
            private val btnEnable: MaterialButton = itemView.findViewById(R.id.btnEnable)

            fun bind(app: SystemAppUpdater.SystemAppInfo) {
                tvName.text = app.appName
                tvPackage.text = app.packageName
                tvVersion.text = "v${app.versionName} (${app.versionCode})"

                // App icon
                try {
                    ivIcon.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                } catch (e: PackageManager.NameNotFoundException) {
                    ivIcon.setImageResource(R.drawable.ic_android_24dp)
                }

                // Type chip
                chipType.text = if (app.isUpdatedSystemApp) getString(R.string.updated_system_app) else getString(R.string.system_app)

                // Check for available update
                val hasUpdate = appsWithUpdates.contains(app.packageName)
                val updateInfo = updatesManifest?.apps?.firstOrNull { it.packageName == app.packageName }
                
                // Action buttons
                btnInstall.visibility = View.GONE
                btnDisable.visibility = View.GONE
                btnEnable.visibility = View.GONE
                tvUpdateAvailable.visibility = View.GONE

                if (hasUpdate && updateInfo != null) {
                    tvUpdateAvailable.text = getString(R.string.update_available, updateInfo.versionName)
                    tvUpdateAvailable.visibility = View.VISIBLE
                    
                    // Show install button that will download and install
                    btnInstall.text = getString(R.string.install_update)
                    btnInstall.visibility = View.VISIBLE
                    btnInstall.setOnClickListener {
                        downloadAndInstallUpdate(app, updateInfo)
                    }
                }

                itemView.setOnClickListener {
                    showAppDetailsDialog(app, updateInfo)
                }
            }

            private fun showAppDetailsDialog(app: SystemAppUpdater.SystemAppInfo, updateInfo: SystemAppUpdater.UpdateInfo?) {
                val messageBuilder = StringBuilder()
                    .append("Package: ${app.packageName}\n")
                    .append("Version: ${app.versionName} (${app.versionCode})\n")
                    .append("Path: ${app.apkPath}\n")
                    .append("Type: ${if (app.isUpdatedSystemApp) "Updated System App" else "System App"}\n")
                    .append("Flags: 0x${Integer.toHexString(app.flags)}")
                
                if (updateInfo != null) {
                    messageBuilder.append("\n\n--- تحديث متاح ---\n")
                        .append("إصدار جديد: ${updateInfo.versionName} (${updateInfo.versionCode})\n")
                        .append("الحجم: ${formatFileSize(updateInfo.fileSize)}\n")
                        .append("الملاحظات: ${updateInfo.notes ?: "لا توجد"}")
                }

                val builder = AlertDialog.Builder(itemView.context)
                    .setTitle(app.appName)
                    .setMessage(messageBuilder)
                    .setPositiveButton(R.string.close, null)

                // Add actions if root
                if (updater.hasRootAccess()) {
                    builder.setNeutralButton(if (app.isUpdatedSystemApp) R.string.disable else R.string.enable) { _, _ ->
                        val enabled = !app.isUpdatedSystemApp
                        val result = updater.setSystemAppEnabled(app.packageName, enabled)
                        Toast.makeText(itemView.context, result.message, Toast.LENGTH_SHORT).show()
                        if (result.success) loadSystemApps()
                    }
                }

                builder.show()
            }
        }
    }
}
