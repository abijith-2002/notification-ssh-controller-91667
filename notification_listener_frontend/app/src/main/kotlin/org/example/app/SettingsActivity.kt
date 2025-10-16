package org.example.app

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.example.app.data.PreferencesRepository
import org.example.app.data.SecureStorage

/**
 * SettingsActivity provides a form to configure SSH settings:
 * - Host, port, username, and command template are stored in PreferencesRepository.
 * - Password is stored securely via SecureStorage.
 *
 * The layout uses Material 3 widgets/styles defined in XML with Reddit Sans typography and dark theme support.
 * Inputs are validated on save and errors are shown inline. Save persists to repositories.
 */
class SettingsActivity : Activity() {

    private lateinit var viewModel: SettingsViewModel

    // Activity-scoped coroutine scope (since we extend Activity, not ComponentActivity)
    private val activityScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var collectJob: Job? = null

    private lateinit var edtHost: EditText
    private lateinit var edtPort: EditText
    private lateinit var edtUsername: EditText
    private lateinit var edtPassword: EditText
    private lateinit var edtCmdTemplate: EditText
    private lateinit var btnSave: Button
    private lateinit var btnClearPassword: Button
    private lateinit var txtPasswordHint: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Initialize ViewModel using ViewModelProvider with custom Factory
        viewModel = ViewModelProvider(
            this as ViewModelStoreOwner,
            SettingsViewModel.Factory(
                PreferencesRepository.getInstance(applicationContext),
                SecureStorage.getInstance(applicationContext)
            )
        ).get(SettingsViewModel::class.java)

        edtHost = findViewById(R.id.edtHost)
        edtPort = findViewById(R.id.edtPort)
        edtUsername = findViewById(R.id.edtUsername)
        edtPassword = findViewById(R.id.edtPassword)
        edtCmdTemplate = findViewById(R.id.edtCmdTemplate)
        btnSave = findViewById(R.id.btnSave)
        btnClearPassword = findViewById(R.id.btnClearPassword)
        txtPasswordHint = findViewById(R.id.txtPasswordHint)
        progressBar = findViewById(R.id.progressBar)

        btnSave.setOnClickListener { onSaveClicked() }
        btnClearPassword.setOnClickListener { onClearPasswordClicked() }

        addRealtimeValidationHelpers()
    }

    override fun onStart() {
        super.onStart()
        // Bind current values to UI
        collectJob = activityScope.launch {
            viewModel.uiState.collectLatest { state ->
                progressBar.visibility = if (state.loading) View.VISIBLE else View.GONE

                if (state.prefilled.not()) {
                    // Prefill once when data is first available
                    edtHost.setText(state.host)
                    edtPort.setText(if (state.port > 0) state.port.toString() else "")
                    edtUsername.setText(state.username)
                    edtCmdTemplate.setText(state.commandTemplate)

                    // Password hint (never show actual password)
                    txtPasswordHint.text = if (state.hasPassword) {
                        getString(R.string.password_saved_hint)
                    } else {
                        getString(R.string.password_not_set_hint)
                    }
                    viewModel.markPrefilled()
                } else {
                    // Update password hint if it changes
                    txtPasswordHint.text = if (state.hasPassword) {
                        getString(R.string.password_saved_hint)
                    } else {
                        getString(R.string.password_not_set_hint)
                    }
                }

                // Show validation messages if present
                setError(edtHost, state.hostError)
                setError(edtPort, state.portError)
                setError(edtUsername, state.usernameError)
                setError(edtCmdTemplate, state.commandTemplateError)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        collectJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun onSaveClicked() {
        val host = edtHost.text?.toString()?.trim().orEmpty()
        val portText = edtPort.text?.toString()?.trim().orEmpty()
        val username = edtUsername.text?.toString()?.trim().orEmpty()
        val password = edtPassword.text?.toString() ?: ""
        val commandTemplate = edtCmdTemplate.text?.toString() ?: ""

        viewModel.save(host, portText, username, password, commandTemplate) { success ->
            if (success) {
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                // Clear password field after save to avoid lingering in memory/UI
                edtPassword.text?.clear()
                finish()
            } else {
                Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onClearPasswordClicked() {
        viewModel.clearPassword {
            Toast.makeText(this, R.string.password_cleared, Toast.LENGTH_SHORT).show()
            edtPassword.text?.clear()
        }
    }

    private fun setError(view: EditText, error: String?) {
        view.error = error
    }

    private fun addRealtimeValidationHelpers() {
        edtHost.addTextChangedListener(simpleWatcher { viewModel.clearHostError() })
        edtPort.addTextChangedListener(simpleWatcher { viewModel.clearPortError() })
        edtUsername.addTextChangedListener(simpleWatcher { viewModel.clearUsernameError() })
        edtCmdTemplate.addTextChangedListener(simpleWatcher { viewModel.clearCommandTemplateError() })
    }

    private fun simpleWatcher(after: () -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { after() }
        }
}

/**
 * PUBLIC_INTERFACE
 * SettingsViewModel exposes state for SettingsActivity and handles validation and persistence.
 */
class SettingsViewModel(
    private val prefs: PreferencesRepository,
    private val secure: SecureStorage
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val prefilled: Boolean = false,
        val host: String = "",
        val port: Int = 0,
        val username: String = "",
        val commandTemplate: String = "",
        val hasPassword: Boolean = false,
        val hostError: String? = null,
        val portError: String? = null,
        val usernameError: String? = null,
        val commandTemplateError: String? = null
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(UiState(loading = true))
    val uiState: kotlinx.coroutines.flow.StateFlow<UiState> = _state

    init {
        // Load existing values
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val sshCfg = prefs.sshConfigFlow.first()
                val hasPwd = secure.getPassword()?.isNotEmpty() == true
                val newState = UiState(
                    loading = false,
                    prefilled = false,
                    host = sshCfg.host,
                    port = sshCfg.port,
                    username = sshCfg.username,
                    commandTemplate = sshCfg.commandTemplate,
                    hasPassword = hasPwd
                )
                // Post to state on main
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _state.value = newState
                }
            } catch (_: Throwable) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _state.value = UiState(loading = false)
                }
            }
        }
    }

    fun markPrefilled() {
        _state.value = _state.value.copy(prefilled = true)
    }

    fun clearHostError() {
        if (_state.value.hostError != null) _state.value = _state.value.copy(hostError = null)
    }

    fun clearPortError() {
        if (_state.value.portError != null) _state.value = _state.value.copy(portError = null)
    }

    fun clearUsernameError() {
        if (_state.value.usernameError != null) _state.value = _state.value.copy(usernameError = null)
    }

    fun clearCommandTemplateError() {
        if (_state.value.commandTemplateError != null) _state.value = _state.value.copy(commandTemplateError = null)
    }

    /**
     * Validate inputs and persist. Password is saved only when non-empty.
     */
    fun save(
        host: String,
        portText: String,
        username: String,
        password: String,
        commandTemplate: String,
        onDone: (Boolean) -> Unit
    ) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            var ok = true
            var hostErr: String? = null
            var portErr: String? = null
            var usernameErr: String? = null
            var cmdErr: String? = null

            val port = portText.toIntOrNull() ?: -1
            if (!prefs.isValidHost(host)) {
                ok = false
                hostErr = "Invalid host"
            }
            if (!prefs.isValidPort(port)) {
                ok = false
                portErr = "Invalid port (1..65535)"
            }
            if (!prefs.isValidUsername(username)) {
                ok = false
                usernameErr = "Invalid username"
            }
            // Command can be blank per repository contract; warn but allow
            if (commandTemplate.length > 2000) {
                ok = false
                cmdErr = "Command too long"
            }

            // Switch to main to update state
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    hostError = hostErr,
                    portError = portErr,
                    usernameError = usernameErr,
                    commandTemplateError = cmdErr
                )
            }

            if (!ok) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(false) }
                return@launch
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) { _state.value = _state.value.copy(loading = true) }
            val resultSuccess = try {
                // Persist preferences
                prefs.setSshConfig(host, port, username, commandTemplate)
                // Persist password only if provided
                if (password.isNotEmpty()) {
                    secure.setPassword(password)
                }
                true
            } catch (_: Throwable) {
                false
            }
            val hasPwd = secure.getPassword()?.isNotEmpty() == true
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(loading = false, hasPassword = hasPwd)
                onDone(resultSuccess)
            }
        }
    }

    /**
     * Clears the stored password.
     */
    fun clearPassword(onDone: () -> Unit) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            secure.clear()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(hasPassword = false)
                onDone()
            }
        }
    }

    class Factory(
        private val prefs: PreferencesRepository,
        private val secure: SecureStorage
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(prefs, secure) as T
        }
    }
}
