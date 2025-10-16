package org.example.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.data.PreferencesRepository
import org.example.app.data.SecureStorage
import org.example.app.ssh.FakeSshClient
import org.example.app.ssh.ISshClient
import org.example.app.ssh.JSchSshClient
import org.example.app.ssh.SshResult

/**
 * MainActivity shows:
 * - Notification Listener permission status with a CTA to grant access.
 * - Last SSH result timestamp and status timestamp.
 * - Buttons to open SettingsActivity (placeholder intent) and AppSelectionActivity (placeholder intent).
 * - A "Test SSH" button which runs a sample command using real JSchSshClient or FakeSshClient when password is missing.
 *
 * It uses Material 3 themed views from XML, Reddit Sans font via styles, and dark theme support.
 */
class MainActivity : Activity() {

    // Scopes: tie to Activity lifecycle
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    // Data repositories
    private val prefs by lazy { PreferencesRepository.getInstance(applicationContext) }
    private val secure by lazy { SecureStorage.getInstance(applicationContext) }

    // SSH clients
    private val realSsh: ISshClient by lazy { JSchSshClient() }
    private val fakeSsh: ISshClient by lazy { FakeSshClient() }

    // Request code for runtime permission result
    private val REQ_POST_NOTIFICATIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Wire UI handlers
        findViewById<View>(R.id.btnOpenSettings)?.setOnClickListener { openSettingsActivity() }
        findViewById<View>(R.id.btnOpenAppSelection)?.setOnClickListener { openAppSelectionActivity() }
        findViewById<View>(R.id.btnGrantNotifAccess)?.setOnClickListener { openNotificationAccessSettings() }
        findViewById<View>(R.id.btnRequestPostNotifications)?.setOnClickListener { requestPostNotificationsIfNeededLegacy() }
        findViewById<View>(R.id.btnTestSsh)?.setOnClickListener { launchTestSsh() }

        // Bind flows to UI
        bindUi()
        refreshPermissionUi()
    }

    override fun onResume() {
        super.onResume()
        // Update permission UI in case user changed settings externally
        refreshPermissionUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun bindUi() {
        // Combine needed flows: last result and last status timestamps, and ssh config for showing test hint
        val lastResultFlow = prefs.lastResultTimestampFlow
        val lastStatusFlow = prefs.lastStatusTimestampFlow
        val sshConfigFlow = prefs.sshConfigFlow

        uiScope.launch {
            combine(lastResultFlow, lastStatusFlow, sshConfigFlow) { lastResult, lastStatus, ssh ->
                Triple(lastResult, lastStatus, ssh)
            }.stateIn(uiScope, SharingStarted.Eagerly, Triple(0L, 0L, null)).collect { (lastResult, lastStatus, sshConfig) ->
                val txtLastResult = findViewById<TextView>(R.id.txtLastResult)
                val txtLastStatus = findViewById<TextView>(R.id.txtLastStatus)
                txtLastResult.text = if (lastResult > 0) {
                    getString(R.string.last_result_at, DateUtils.getRelativeTimeSpanString(lastResult))
                } else {
                    getString(R.string.last_result_never)
                }
                txtLastStatus.text = if (lastStatus > 0) {
                    getString(R.string.last_status_at, DateUtils.getRelativeTimeSpanString(lastStatus))
                } else {
                    getString(R.string.last_status_never)
                }

                // Enable/disable test button if minimal config available
                val btnTest = findViewById<Button>(R.id.btnTestSsh)
                btnTest.isEnabled = sshConfig != null
            }
        }
    }

    private fun refreshPermissionUi() {
        val hasListener = isNotificationListenerEnabled()
        val statusIcon = findViewById<ImageView>(R.id.imgPermStatus)
        val statusText = findViewById<TextView>(R.id.txtPermStatus)
        val ctaBtn = findViewById<Button>(R.id.btnGrantNotifAccess)

        if (hasListener) {
            statusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            statusText.text = getString(R.string.notif_access_granted)
            ctaBtn.visibility = View.GONE
        } else {
            statusIcon.setImageResource(android.R.drawable.ic_delete)
            statusText.text = getString(R.string.notif_access_not_granted)
            ctaBtn.visibility = View.VISIBLE
        }

        val postNotifBtn = findViewById<Button>(R.id.btnRequestPostNotifications)
        postNotifBtn.visibility = if (needsPostNotificationsPermission()) View.VISIBLE else View.GONE
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val cn = ComponentName(this, NotificationListenerService::class.java)
        return enabledListeners.split(":").any { it.contains(packageName) }
                || NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun needsPostNotificationsPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: ActivityNotFoundException) {
            // Fallback to app details as last resort
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun requestPostNotificationsIfNeededLegacy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (needsPostNotificationsPermission()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            // Update UI regardless of grant/deny
            refreshPermissionUi()
        }
    }

    private fun openSettingsActivity() {
        // Placeholder: navigate to settings screen if implemented; fall back to app details
        try {
            val intent = Intent(this, Class.forName("org.example.app.SettingsActivity"))
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppSelectionActivity() {
        // Placeholder: navigate to AppSelectionActivity if implemented; fall back to app details
        try {
            val intent = Intent(this, Class.forName("org.example.app.AppSelectionActivity"))
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun launchTestSsh() {
        val btn = findViewById<Button>(R.id.btnTestSsh)
        btn.isEnabled = false
        val txtTestResult = findViewById<TextView>(R.id.txtTestResult)

        uiScope.launch {
            val sshCfg = prefs.sshConfigFlow.stateIn(this, SharingStarted.Eagerly, null).value
                ?: prefs.sshConfigFlow.map { it }.stateIn(this, SharingStarted.Eagerly, null).value
            val password = secure.getPassword()

            val client = if (password.isNullOrEmpty()) fakeSsh else realSsh
            val result: SshResult = withContext(Dispatchers.IO) {
                try {
                    val host = sshCfg?.host ?: ""
                    val port = sshCfg?.port ?: 22
                    val user = sshCfg?.username ?: "user"
                    val cmd = if (sshCfg?.commandTemplate.isNullOrBlank()) "echo hello" else sshCfg!!.commandTemplate
                    client.execute(
                        host = host,
                        port = port,
                        username = user,
                        password = password ?: "fake",
                        command = cmd,
                        timeoutMs = 8_000
                    )
                } catch (t: Throwable) {
                    SshResult(false, -1, "", "", t.message ?: "error")
                }
            }

            val now = System.currentTimeMillis()
            prefs.setLastStatusTimestamp(now)
            if (result.success) {
                prefs.setLastResultTimestamp(now)
            }

            val text = if (result.success) {
                getString(R.string.test_ssh_success)
            } else {
                getString(R.string.test_ssh_failed, result.errorMessage ?: "-")
            }
            txtTestResult.text = text
            btn.isEnabled = true
        }
    }
}
