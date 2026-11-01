package com.downls10

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * نظام تحديث تطبيقات النظام - يتطلب صلاحيات Root
 */
class SystemAppUpdater(private val context: Context) {

    companion object {
        private const val TAG = "SystemAppUpdater"
        const val RESULT_OK = 0
        const val RESULT_NO_ROOT = -1
        const val RESULT_CMD_FAILED = -2
        const val RESULT_FILE_NOT_FOUND = -3
        const val RESULT_DOWNLOAD_FAILED = -4
        const val RESULT_JSON_PARSE_ERROR = -5
    }

    data class SystemAppInfo(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val versionCode: Int,
        val apkPath: String,
        val isUpdatedSystemApp: Boolean,
        val flags: Int
    )

    data class InstallResult(
        val success: Boolean,
        val message: String,
        val exitCode: Int
    )

    // ===== Update Check Data Classes =====
    data class UpdateInfo(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val fileSize: Long,
        val sha256: String?,
        val minAndroidVersion: Int,
        val notes: String?,
        val oem: String?
    )

    data class UpdateCheckResult(
        val hasUpdate: Boolean,
        val installedVersionName: String,
        val installedVersionCode: Int,
        val availableVersionName: String,
        val availableVersionCode: Int,
        val updateInfo: UpdateInfo?,
        val message: String
    )

    data class UpdatesManifest(
        val version: Int,
        val lastUpdated: String,
        val source: String,
        val apps: List<UpdateInfo>
    )

    /**
     * التحقق من توفر صلاحيات Root
     */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    /**
     * الحصول على قائمة جميع تطبيقات النظام
     */
    fun getSystemApps(): List<SystemAppInfo> {
        val pm = context.packageManager
        val apps = mutableListOf<SystemAppInfo>()

        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            for (app in installedApps) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                
                if (isSystem || isUpdatedSystem) {
                    try {
                        val pkgInfo = pm.getPackageInfo(app.packageName, 0)
                        apps.add(SystemAppInfo(
                            packageName = app.packageName,
                            appName = pm.getApplicationLabel(app).toString(),
                            versionName = pkgInfo.versionName ?: "Unknown",
                            versionCode = pkgInfo.versionCode,
                            apkPath = app.sourceDir,
                            isUpdatedSystemApp = isUpdatedSystem,
                            flags = app.flags
                        ))
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Package not found: ${app.packageName}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting system apps", e)
        }

        return apps.sortedBy { it.appName.lowercase() }
    }

    /**
     * قراءة وقراءة ملف updates.json من Assets
     */
    fun loadUpdatesManifest(): UpdatesManifest? {
        return try {
            val inputStream: InputStream = context.assets.open("updates.json")
            val jsonString = inputStream.readBytes().decodeToString(StandardCharsets.UTF_8)
            inputStream.close()
            parseUpdatesManifest(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load updates.json from assets", e)
            null
        }
    }

    /**
     * قراءة ملف updates.json من مسار مخصص
     */
    fun loadUpdatesManifestFromFile(file: File): UpdatesManifest? {
        return try {
            val jsonString = file.readText(StandardCharsets.UTF_8)
            parseUpdatesManifest(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load updates.json from file", e)
            null
        }
    }

    private fun parseUpdatesManifest(jsonString: String): UpdatesManifest? {
        return try {
            val json = JSONObject(jsonString)
            val version = json.optInt("version", 1)
            val lastUpdated = json.optString("lastUpdated", "")
            val source = json.optString("source", "local")
            val appsArray = json.optJSONArray("apps") ?: JSONArray()
            val apps = mutableListOf<UpdateInfo>()

            for (i in 0 until appsArray.length()) {
                val appJson = appsArray.getJSONObject(i)
                apps.add(UpdateInfo(
                    packageName = appJson.getString("packageName"),
                    appName = appJson.getString("appName"),
                    versionName = appJson.getString("versionName"),
                    versionCode = appJson.getInt("versionCode"),
                    downloadUrl = appJson.getString("downloadUrl"),
                    fileSize = appJson.optLong("fileSize", 0),
                    sha256 = appJson.optString("sha256", null).takeIf { it.isNotBlank() },
                    minAndroidVersion = appJson.optInt("minAndroidVersion", 21),
                    notes = appJson.optString("notes", null).takeIf { it.isNotBlank() },
                    oem = appJson.optString("oem", null).takeIf { it.isNotBlank() }
                ))
            }

            UpdatesManifest(version, lastUpdated, source, apps)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
            null
        }
    }

    /**
     * فحص التحديثات المتاحة لتطبيق معين
     */
    fun checkForUpdate(app: SystemAppInfo, manifest: UpdatesManifest): UpdateCheckResult {
        val updateInfo = manifest.apps.firstOrNull { it.packageName == app.packageName }
        
        return if (updateInfo != null) {
            val hasUpdate = updateInfo.versionCode > app.versionCode
            val message = if (hasUpdate) {
                "تحديث متاح: ${app.versionName} (${app.versionCode}) → ${updateInfo.versionName} (${updateInfo.versionCode})"
            } else {
                "لا يوجد تحديث (المثبت: ${app.versionName}, الأحدث: ${updateInfo.versionName})"
            }
            
            UpdateCheckResult(
                hasUpdate = hasUpdate,
                installedVersionName = app.versionName,
                installedVersionCode = app.versionCode,
                availableVersionName = updateInfo.versionName,
                availableVersionCode = updateInfo.versionCode,
                updateInfo = if (hasUpdate) updateInfo else null,
                message = message
            )
        } else {
            UpdateCheckResult(
                hasUpdate = false,
                installedVersionName = app.versionName,
                installedVersionCode = app.versionCode,
                availableVersionName = "N/A",
                availableVersionCode = 0,
                updateInfo = null,
                message = "لا يوجد معلومات تحديث لهذا التطبيق"
            )
        }
    }

    /**
     * فحص جميع التحديثات المتاحة
     */
    fun checkAllUpdates(manifest: UpdatesManifest): List<Pair<SystemAppInfo, UpdateCheckResult>> {
        val systemApps = getSystemApps()
        return systemApps.map { app ->
            app to checkForUpdate(app, manifest)
        }.filter { (_, result) -> result.hasUpdate }
            .sortedByDescending { (_, result) -> result.availableVersionCode - result.installedVersionCode }
    }

    /**
     * الحصول على قائمة التطبيقات التي يتوفر لها تحديث (للواجهة)
     */
    fun getAppsWithUpdates(manifest: UpdatesManifest): List<SystemAppInfo> {
        return checkAllUpdates(manifest).map { (app, _) -> app }
    }

    /**
     * البحث عن ملف APK في التخزين المشترك
     */
    fun findApkFiles(searchDir: String = Environment.getExternalStorageDirectory().absolutePath): List<File> {
        val apkFiles = mutableListOf<File>()
        val rootDir = File(searchDir)
        
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return apkFiles
        }

        fun scanDirectory(dir: File) {
            try {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory) {
                        // تجنب المجلدات النظامية والكبيرة
                        if (!file.name.startsWith(".") && 
                            file.name !in setOf("Android", "DCIM", "Pictures", "Movies", "Music", "Download")) {
                            scanDirectory(file)
                        }
                    } else if (file.extension.lowercase() == "apk") {
                        apkFiles.add(file)
                    }
                }
            } catch (e: Exception) {
                // تجاهل أخطاء الوصول
            }
        }

        scanDirectory(rootDir)
        return apkFiles.sortedBy { it.name.lowercase() }
    }

    /**
     * تثبيت/تحديث APK كتطبيق نظام عبر Root
     * يستخدم: pm install -r -d --user 0
     * -r: استبدال التطبيق الموجود
     * -d: السماح بتخفيض الإصدار (downgrade)
     * --user 0: للمستخدم الرئيسي
     */
    fun installSystemApk(apkFile: File, packageName: String? = null): InstallResult {
        if (!hasRootAccess()) {
            return InstallResult(false, "يتطلب صلاحيات Root", RESULT_NO_ROOT)
        }

        if (!apkFile.exists()) {
            return InstallResult(false, "ملف APK غير موجود: ${apkFile.absolutePath}", RESULT_FILE_NOT_FOUND)
        }

        val apkPath = apkFile.absolutePath.replace("'", "'\\''") // escape single quotes
        val cmd = "su -c \"pm install -r -d --user 0 '$apkPath'\""

        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val exitCode = process.waitFor()
            val output = process.inputStream.readText()
            val error = process.errorStream.readText()

            Log.d(TAG, "Install cmd: $cmd")
            Log.d(TAG, "Exit code: $exitCode")
            Log.d(TAG, "Output: $output")
            Log.d(TAG, "Error: $error")

            val success = exitCode == 0 && (output.contains("Success") || error.contains("Success") || output.isNotBlank())
            val message = if (success) "تم التثبيت بنجاح" else "فشل التثبيت: ${error.ifBlank { output }}".trim()

            InstallResult(success, message, exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            InstallResult(false, "خطأ في التنفيذ: ${e.message}", RESULT_CMD_FAILED)
        }
    }

    /**
     * إلغاء تثبيت تطبيق نظام (يتطلب Root)
     */
    fun uninstallSystemApp(packageName: String): InstallResult {
        if (!hasRootAccess()) {
            return InstallResult(false, "يتطلب صلاحيات Root", RESULT_NO_ROOT)
        }

        val cmd = "su -c \"pm uninstall --user 0 $packageName\""

        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val exitCode = process.waitFor()
            val output = process.inputStream.readText()
            val error = process.errorStream.readText()

            val success = exitCode == 0 && (output.contains("Success") || error.contains("Success"))
            val message = if (success) "تم الإلغاء بنجاح" else "فشل الإلغاء: ${error.ifBlank { output }}".trim()

            InstallResult(success, message, exitCode)
        } catch (e: Exception) {
            InstallResult(false, "خطأ: ${e.message}", RESULT_CMD_FAILED)
        }
    }

    /**
     * تمكين/تعطيل تطبيق نظام
     */
    fun setSystemAppEnabled(packageName: String, enabled: Boolean): InstallResult {
        if (!hasRootAccess()) {
            return InstallResult(false, "يتطلب صلاحيات Root", RESULT_NO_ROOT)
        }

        val state = if (enabled) "enabled" else "disabled"
        val cmd = "su -c \"pm $state --user 0 $packageName\""

        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val exitCode = process.waitFor()
            val output = process.inputStream.readText()
            val error = process.errorStream.readText()

            val success = exitCode == 0
            val message = if (success) "تم ${if (enabled) "التفعيل" else "التعطيل"} بنجاح" else "فشل: ${error.ifBlank { output }}".trim()

            InstallResult(success, message, exitCode)
        } catch (e: Exception) {
            InstallResult(false, "خطأ: ${e.message}", RESULT_CMD_FAILED)
        }
    }

    /**
     * الحصول على معلومات إصدار APK من الملف
     */
    fun getApkInfo(apkFile: File): SystemAppInfo? {
        return try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (pkgInfo == null) return null

            val appInfo = pkgInfo.applicationInfo
            val appName = appInfo.loadLabel(pm).toString()

            SystemAppInfo(
                packageName = pkgInfo.packageName,
                appName = appName,
                versionName = pkgInfo.versionName ?: "Unknown",
                versionCode = pkgInfo.versionCode,
                apkPath = apkFile.absolutePath,
                isUpdatedSystemApp = false,
                flags = appInfo.flags
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading APK info", e)
            null
        }
    }

    /**
     * التحقق مما إذا كان APK أحدث من التطبيق المثبت
     */
    fun isApkNewer(apkFile: File, installedVersionCode: Int): Boolean {
        val apkInfo = getApkInfo(apkFile)
        return apkInfo != null && apkInfo.versionCode > installedVersionCode
    }
}
