package org.example.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * PreferencesRepository provides a single entry point to read/update application user preferences
 * using Jetpack DataStore Preferences. It persists SSH configuration (host, port, username,
 * and command template), selected app package names, "run only when unlocked" flag, and
 * last-run timestamps for results and status updates.
 *
 * Sensitive values such as passwords are NOT stored here; use SecureStorage instead.
 *
 * Thread-safety: DataStore ensures atomicity for edit transactions. All public setters are suspend
 * and perform a single edit{} per operation to keep updates atomic and consistent.
 */
class PreferencesRepository private constructor(
    private val context: Context
) {

    // region DataStore/keys

    private val Context.dataStore by preferencesDataStore(
        name = DATASTORE_NAME
    )

    // Placeholders/constants for keys are defined here; these should be the only source of truth.
    // SSH configuration keys
    private object Keys {
        val SSH_HOST: Preferences.Key<String> = stringPreferencesKey("ssh_host")
        val SSH_PORT: Preferences.Key<Int> = intPreferencesKey("ssh_port")
        val SSH_USERNAME: Preferences.Key<String> = stringPreferencesKey("ssh_username")
        val SSH_COMMAND_TEMPLATE: Preferences.Key<String> = stringPreferencesKey("ssh_command_template")

        // Selection of monitored app package names
        val SELECTED_PACKAGES: Preferences.Key<Set<String>> = stringSetPreferencesKey("selected_packages")

        // Behavior flags
        val RUN_ONLY_WHEN_UNLOCKED: Preferences.Key<Boolean> = booleanPreferencesKey("run_only_when_unlocked")

        // Timestamps (epoch millis)
        val LAST_RESULT_TIMESTAMP: Preferences.Key<Long> = longPreferencesKey("last_result_timestamp")
        val LAST_STATUS_TIMESTAMP: Preferences.Key<Long> = longPreferencesKey("last_status_timestamp")
    }

    // endregion

    // region Public models

    /**
     * Simple SSH config value object exposed via Flows and accessed by callers.
     */
    data class SshConfig(
        val host: String,
        val port: Int,
        val username: String,
        val commandTemplate: String
    )

    // endregion

    // region Flows

    // PUBLIC_INTERFACE
    /**
     * Flow emitting the current SSH configuration. Defaults:
     * host="", port=22, username="", commandTemplate="".
     */
    val sshConfigFlow: Flow<SshConfig> = context.dataStore.data.map { prefs ->
        SshConfig(
            host = prefs[Keys.SSH_HOST].orEmpty(),
            port = prefs[Keys.SSH_PORT] ?: DEFAULT_SSH_PORT,
            username = prefs[Keys.SSH_USERNAME].orEmpty(),
            commandTemplate = prefs[Keys.SSH_COMMAND_TEMPLATE].orEmpty()
        )
    }

    // PUBLIC_INTERFACE
    /**
     * Flow emitting the current set of selected app package names that should be monitored.
     */
    val selectedPackagesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_PACKAGES] ?: emptySet()
    }

    // PUBLIC_INTERFACE
    /**
     * Flow emitting whether to run only when device is unlocked.
     */
    val runOnlyWhenUnlockedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.RUN_ONLY_WHEN_UNLOCKED] ?: DEFAULT_RUN_ONLY_WHEN_UNLOCKED
    }

    // PUBLIC_INTERFACE
    /**
     * Flow emitting the last result timestamp (epoch millis) or 0 if not set.
     */
    val lastResultTimestampFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_RESULT_TIMESTAMP] ?: 0L
    }

    // PUBLIC_INTERFACE
    /**
     * Flow emitting the last status timestamp (epoch millis) or 0 if not set.
     */
    val lastStatusTimestampFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_STATUS_TIMESTAMP] ?: 0L
    }

    // endregion

    // region Suspend updates (atomic per call)

    // PUBLIC_INTERFACE
    /**
     * Atomically updates the SSH host. Validation ensures non-empty and trimmed.
     * Throws IllegalArgumentException on invalid input.
     */
    suspend fun setSshHost(host: String) {
        val normalized = host.trim()
        require(isValidHost(normalized)) { "Invalid host" }
        context.dataStore.edit { it[Keys.SSH_HOST] = normalized }
    }

    // PUBLIC_INTERFACE
    /**
     * Atomically updates the SSH port. Validation ensures port is within 1..65535.
     * Throws IllegalArgumentException on invalid input.
     */
    suspend fun setSshPort(port: Int) {
        require(isValidPort(port)) { "Invalid port" }
        context.dataStore.edit { it[Keys.SSH_PORT] = port }
    }

    // PUBLIC_INTERFACE
    /**
     * Atomically updates the SSH username. Validation ensures non-empty and trimmed.
     * Throws IllegalArgumentException on invalid input.
     */
    suspend fun setSshUsername(username: String) {
        val normalized = username.trim()
        require(isValidUsername(normalized)) { "Invalid username" }
        context.dataStore.edit { it[Keys.SSH_USERNAME] = normalized }
    }

    // PUBLIC_INTERFACE
    /**
     * Atomically updates the SSH command template string (free-form; can be empty).
     * Avoid logging value because it could include sensitive context.
     */
    suspend fun setSshCommandTemplate(commandTemplate: String) {
        // No strict validation; caller/feature will validate tokens/placeholders as needed.
        context.dataStore.edit { it[Keys.SSH_COMMAND_TEMPLATE] = commandTemplate }
    }

    // PUBLIC_INTERFACE
    /**
     * Atomically updates all SSH config values. Validates host/port/username.
     * Fails fast if any value is invalid.
     */
    suspend fun setSshConfig(host: String, port: Int, username: String, commandTemplate: String) {
        val h = host.trim()
        val u = username.trim()
        require(isValidHost(h)) { "Invalid host" }
        require(isValidPort(port)) { "Invalid port" }
        require(isValidUsername(u)) { "Invalid username" }

        context.dataStore.edit {
            it[Keys.SSH_HOST] = h
            it[Keys.SSH_PORT] = port
            it[Keys.SSH_USERNAME] = u
            it[Keys.SSH_COMMAND_TEMPLATE] = commandTemplate
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Replaces the full selected packages set atomically.
     */
    suspend fun setSelectedPackages(packages: Set<String>) {
        // Normalize: trim entries and remove blanks.
        val normalized = packages.mapNotNull { p ->
            p?.trim()?.takeIf { it.isNotEmpty() }
        }.toSet()
        context.dataStore.edit { it[Keys.SELECTED_PACKAGES] = normalized }
    }

    // PUBLIC_INTERFACE
    /**
     * Adds a single package to the selected set atomically.
     */
    suspend fun addSelectedPackage(pkg: String) {
        val normalized = pkg.trim()
        if (normalized.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_PACKAGES] ?: emptySet()
            prefs[Keys.SELECTED_PACKAGES] = current + normalized
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Removes a single package from the selected set atomically.
     */
    suspend fun removeSelectedPackage(pkg: String) {
        val normalized = pkg.trim()
        if (normalized.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SELECTED_PACKAGES] ?: emptySet()
            prefs[Keys.SELECTED_PACKAGES] = current - normalized
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Sets whether the SSH execution should run only when the device is unlocked.
     */
    suspend fun setRunOnlyWhenUnlocked(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RUN_ONLY_WHEN_UNLOCKED] = enabled }
    }

    // PUBLIC_INTERFACE
    /**
     * Updates the last result timestamp (epoch millis).
     */
    suspend fun setLastResultTimestamp(timestampMillis: Long) {
        context.dataStore.edit { it[Keys.LAST_RESULT_TIMESTAMP] = timestampMillis }
    }

    // PUBLIC_INTERFACE
    /**
     * Updates the last status timestamp (epoch millis).
     */
    suspend fun setLastStatusTimestamp(timestampMillis: Long) {
        context.dataStore.edit { it[Keys.LAST_STATUS_TIMESTAMP] = timestampMillis }
    }

    // endregion

    // region Validation utilities

    // PUBLIC_INTERFACE
    /**
     * Returns true if the host is a non-empty trimmed string. This does not validate
     * full domain/IP correctness to keep it permissive and avoid rejecting local hosts.
     */
    fun isValidHost(host: String?): Boolean {
        if (host == null) return false
        return host.trim().isNotEmpty()
    }

    // PUBLIC_INTERFACE
    /**
     * Returns true if the port is within the valid TCP port range 1..65535.
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    // PUBLIC_INTERFACE
    /**
     * Returns true if the username is a non-empty trimmed string.
     */
    fun isValidUsername(username: String?): Boolean {
        if (username == null) return false
        return username.trim().isNotEmpty()
    }

    // endregion

    companion object {
        private const val DATASTORE_NAME = "user_prefs"
        private const val DEFAULT_SSH_PORT = 22
        private const val DEFAULT_RUN_ONLY_WHEN_UNLOCKED = true

        @Volatile
        private var instance: PreferencesRepository? = null

        // PUBLIC_INTERFACE
        /**
         * Returns the singleton instance of PreferencesRepository using the application context.
         */
        fun getInstance(context: Context): PreferencesRepository {
            return instance ?: synchronized(this) {
                instance ?: PreferencesRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
