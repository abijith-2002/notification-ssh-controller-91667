package org.example.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.data.PreferencesRepository
import org.example.app.data.SecureStorage
import org.example.app.ssh.ISshClient
import org.example.app.ssh.JSchSshClient
import org.example.app.ssh.SshResult
import java.util.concurrent.TimeUnit

/**
 * NotifSshListenerService listens to system notifications and triggers an SSH command execution
 * based on user configuration and selected monitored app packages.
 *
 * Behavior:
 * - Loads selected app packages and SSH configuration from PreferencesRepository and SecureStorage.
 * - Filters incoming notifications by selected package names.
 * - Builds a sanitized command from a template supporting ${package}, ${title}, ${text}, ${postTime}.
 * - Executes the command via ISshClient on Dispatchers.IO with a timeout.
 * - Persists last result timestamp and status timestamp to PreferencesRepository.
 * - Optionally posts a local notification when POST_NOTIFICATIONS is granted.
 *
 * Security:
 * - No sensitive data (password, full command) is logged.
 * - Uses StrictHostKeyChecking=no per MVP (see TODO in SSH client for hardening).
 *
 * The service is enabled when the user grants Notification Listener permission in system settings.
 */
class NotifSshListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Dependencies (could be injected in a DI setup; created lazily here)
    private val prefs by lazy { PreferencesRepository.getInstance(applicationContext) }
    private val secure by lazy { SecureStorage.getInstance(applicationContext) }
    private val sshClient: ISshClient by lazy { JSchSshClient() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Avoid logging sensitive details; just note that listener is connected.
        // We do not post a local notification to avoid noise at connect.
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Cleanup or background tasks could be paused here if needed.
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /**
     * Called by the system when a new notification is posted.
     * This is potentially invoked on the main thread; heavy work is dispatched to a background coroutine.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Offload to background
        serviceScope.launch {
            handleNotification(sbn)
        }
    }

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        // Get selected packages and configs atomically (single snapshot)
        val selectedPkgs: Set<String> = prefs.selectedPackagesFlow.first()
        if (selectedPkgs.isEmpty()) {
            // Nothing to monitor; no-op
            return
        }

        val pkg = sbn.packageName ?: return
        if (!selectedPkgs.contains(pkg)) {
            // Not a monitored package
            return
        }

        val sshConfig = prefs.sshConfigFlow.first()
        val password = secure.getPassword()

        // Validate config presence; do not proceed if incomplete
        if (!prefs.isValidHost(sshConfig.host) ||
            !prefs.isValidPort(sshConfig.port) ||
            !prefs.isValidUsername(sshConfig.username) ||
            password.isNullOrEmpty() ||
            sshConfig.commandTemplate.isBlank()
        ) {
            // Missing or invalid config; update status timestamp and return quietly
            prefs.setLastStatusTimestamp(System.currentTimeMillis())
            maybeNotify(
                title = getString(R.string.ssh_status_incomplete_title),
                text = getString(R.string.ssh_status_incomplete_body)
            )
            return
        }

        // Extract notification content safely
        val title = safeCharSeq(sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)).orEmpty()
        val text = safeCharSeq(sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)).orEmpty()
        val postTime = sbn.postTime

        // Build command from template with sanitized placeholders
        val command = buildCommandFromTemplate(
            sshConfig.commandTemplate,
            pkg,
            title,
            text,
            postTime
        )

        // Execute SSH in IO context with timeout logic inside ssh client
        val timeoutMs = DEFAULT_TIMEOUT_MS
        val result: SshResult = runSsh(
            host = sshConfig.host,
            port = sshConfig.port,
            username = sshConfig.username,
            password = password!!,
            command = command,
            timeoutMs = timeoutMs
        )

        // Persist timestamps (status is always updated; result only on completion)
        val now = System.currentTimeMillis()
        prefs.setLastStatusTimestamp(now)
        if (result.success) {
            prefs.setLastResultTimestamp(now)
        }

        // Optionally display a local notification briefly summarizing success/failure
        val summaryTitle = if (result.success) {
            getString(R.string.ssh_result_success_title)
        } else {
            getString(R.string.ssh_result_failure_title)
        }
        val summaryBody = summarizeResultForNotification(result)
        maybeNotify(title = summaryTitle, text = summaryBody)
    }

    private suspend fun runSsh(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Int
    ): SshResult = withContext(Dispatchers.IO) {
        try {
            sshClient.execute(
                host = host,
                port = port,
                username = username,
                password = password,
                command = command,
                timeoutMs = timeoutMs
            )
        } catch (_: Throwable) {
            // Return generic error without leaking details
            SshResult(
                success = false,
                exitStatus = -1,
                stdout = "",
                stderr = "",
                errorMessage = "Unhandled execution error"
            )
        }
    }

    /**
     * Builds the command string by replacing placeholders in the user-provided template.
     * Supported placeholders:
     *  - ${package}
     *  - ${title}
     *  - ${text}
     *  - ${postTime}
     *
     * Sanitization:
     *  - Replaces any newline characters with spaces to avoid multiline injection.
     *  - Trims outer whitespace.
     *  - For title/text, a simple escaping of backticks and dollar braces to reduce shell interpolation risk.
     *    This is an MVP mitigation; stronger quoting can be done in future iterations.
     */
    private fun buildCommandFromTemplate(
        template: String,
        pkg: String,
        title: String,
        text: String,
        postTime: Long
    ): String {
        fun sanitizeValue(v: String): String {
            val singleLine = v.replace('\n', ' ').replace('\r', ' ')
            // Basic escaping of backticks and ${ to minimize shell interpolation risks.
            return singleLine
                .replace("`", "\\`")
                .replace("\${", "\\\${")
                .trim()
        }

        val replacements = mapOf(
            "\${package}" to sanitizeValue(pkg),
            "\${title}" to sanitizeValue(title),
            "\${text}" to sanitizeValue(text),
            "\${postTime}" to postTime.toString()
        )

        var cmd = template
        replacements.forEach { (key, value) ->
            cmd = cmd.replace(key, value)
        }
        // Ensure single-line command
        cmd = cmd.replace('\n', ' ').replace('\r', ' ').trim()
        return cmd
    }

    private fun safeCharSeq(cs: CharSequence?): String? {
        return cs?.toString()
    }

    /**
     * Posts a local notification summarizing the SSH result when permitted.
     * It avoids including sensitive details. Only brief success/failure with limited text is shown.
     */
    private fun maybeNotify(title: String, text: String) {
        if (!isPostNotificationsGranted()) return

        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        ensureChannel(nm)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title.take(MAX_LOCAL_TITLE))
            .setContentText(text.take(MAX_LOCAL_BODY))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(MAX_LOCAL_BIG_BODY)))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIF_ID_RESULT, notif)
    }

    private fun isPostNotificationsGranted(): Boolean {
        // POST_NOTIFICATIONS runtime on Android 13+; on lower SDKs it's install-time.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("InlinedApi")
    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name_results),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc_results)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Builds a short and safe summary for local notifications without exposing secrets.
     */
    private fun summarizeResultForNotification(result: SshResult): String {
        if (result.success) {
            return getString(R.string.ssh_result_success_body)
        }
        // Truncate error message to avoid verbosity
        val err = result.errorMessage?.take(128) ?: getString(R.string.ssh_result_unknown_error)
        return getString(R.string.ssh_result_failure_body, err)
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000 // 10 seconds MVP
        private const val CHANNEL_ID = "ssh_results"
        private const val NOTIF_ID_RESULT = 1001

        private const val MAX_LOCAL_TITLE = 48
        private const val MAX_LOCAL_BODY = 120
        private const val MAX_LOCAL_BIG_BODY = 400
    }
}
