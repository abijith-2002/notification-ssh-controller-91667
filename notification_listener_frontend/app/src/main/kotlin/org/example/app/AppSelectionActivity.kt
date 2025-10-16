package org.example.app

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.example.app.data.PreferencesRepository

/**
 * AppSelectionActivity displays a searchable list of installed apps with a checkbox
 * to select which packages should be monitored. Selection is persisted to PreferencesRepository.
 *
 * UI: Material 3 styled layout using Reddit Sans typography and dark theme support.
 */
class AppSelectionActivity : Activity() {

    private lateinit var viewModel: AppSelectionViewModel
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private lateinit var edtSearch: EditText
    private lateinit var btnClearSearch: Button
    private lateinit var txtSelectedCount: TextView
    private lateinit var btnApply: Button
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        viewModel = ViewModelProvider(
            this as androidx.lifecycle.ViewModelStoreOwner,
            AppSelectionViewModel.Factory(
                applicationContext.packageManager,
                PreferencesRepository.getInstance(applicationContext)
            )
        )[AppSelectionViewModel::class.java]

        edtSearch = findViewById(R.id.edtSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        txtSelectedCount = findViewById(R.id.txtSelectedCount)
        btnApply = findViewById(R.id.btnApply)
        recycler = findViewById(R.id.recyclerApps)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppsAdapter { pkg, checked -> viewModel.onTogglePackage(pkg, checked) }
        recycler.adapter = adapter

        btnApply.setOnClickListener { onApply() }
        btnClearSearch.setOnClickListener { edtSearch.text?.clear() }
        edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.onSearchQueryChanged(s?.toString().orEmpty())
            }
        })
    }

    override fun onStart() {
        super.onStart()
        collectJob = activityScope.launch {
            viewModel.uiState.collectLatest { state ->
                txtSelectedCount.text = getString(R.string.selected_count, state.selectedPackages.size)
                adapter.submitList(state.visibleApps)
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

    private fun onApply() {
        activityScope.launch {
            val ok = viewModel.persistSelection()
            if (ok) {
                Toast.makeText(this@AppSelectionActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@AppSelectionActivity, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * PUBLIC_INTERFACE
 * AppSelectionViewModel provides state for listing installed apps, filtering by search query,
 * tracking selected package names, and persisting selection to PreferencesRepository.
 */
class AppSelectionViewModel(
    private val pm: PackageManager,
    private val prefs: PreferencesRepository
) : ViewModel() {

    data class AppRow(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val selected: Boolean
    )

    data class UiState(
        val allApps: List<AppRow> = emptyList(),
        val visibleApps: List<AppRow> = emptyList(),
        val selectedPackages: Set<String> = emptySet(),
        val query: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _state

    init {
        // Load installed apps and initial selection tied to ViewModel lifecycle
        viewModelScope.launch(Dispatchers.Default) {
            val initialSelected = prefs.selectedPackagesFlow.first()
            val apps = loadInstalledApps(initialSelected)
            withContext(Dispatchers.Main) {
                _state.value = UiState(
                    allApps = apps,
                    visibleApps = apps,
                    selectedPackages = initialSelected,
                    query = ""
                )
            }
        }
    }

    private fun loadInstalledApps(selected: Set<String>): List<AppRow> {
        val flags = PackageManager.GET_META_DATA
        val pkgs = pm.getInstalledApplications(flags)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // user-installed
        val mapped = pkgs.map { ai ->
            val label = ai.loadLabel(pm)?.toString().orEmpty()
            val icon = try {
                ai.loadIcon(pm)
            } catch (_: Exception) {
                null
            }
            AppRow(
                packageName = ai.packageName,
                label = label,
                icon = icon,
                selected = selected.contains(ai.packageName)
            )
        }
        return mapped.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun onSearchQueryChanged(query: String) {
        _state.update { st ->
            val filtered = if (query.isBlank()) {
                st.allApps
            } else {
                val q = query.trim().lowercase()
                st.allApps.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
            st.copy(visibleApps = filtered, query = query)
        }
    }

    fun onTogglePackage(pkg: String, checked: Boolean) {
        _state.update { st ->
            val newSelected = if (checked) st.selectedPackages + pkg else st.selectedPackages - pkg
            val updateRow: (AppSelectionViewModel.AppRow) -> AppSelectionViewModel.AppRow = { row ->
                if (row.packageName == pkg) row.copy(selected = checked) else row
            }
            val allUpd = st.allApps.map(updateRow)
            val visUpd = st.visibleApps.map(updateRow)
            st.copy(allApps = allUpd, visibleApps = visUpd, selectedPackages = newSelected)
        }
    }

    /**
     * Persist the current selection atomically to PreferencesRepository.
     */
    suspend fun persistSelection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val current = _state.value.selectedPackages
            prefs.setSelectedPackages(current)
            true
        } catch (_: Throwable) {
            false
        }
    }

    class Factory(
        private val pm: PackageManager,
        private val prefs: PreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppSelectionViewModel(pm, prefs) as T
        }
    }
}

/**
 * RecyclerView Adapter for installed apps list with multi-select checkboxes.
 */
private class AppsAdapter(
    private val onToggle: (pkg: String, checked: Boolean) -> Unit
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    private var items: List<AppSelectionViewModel.AppRow> = emptyList()

    fun submitList(newList: List<AppSelectionViewModel.AppRow>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].packageName == newList[newItemPosition].packageName
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = items[oldItemPosition]
                val n = newList[newItemPosition]
                return o.label == n.label && o.selected == n.selected
            }
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false)
        return VH(v, onToggle)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View, private val onToggle: (pkg: String, checked: Boolean) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val imgIcon: ImageView = itemView.findViewById(R.id.imgIcon)
        private val txtLabel: TextView = itemView.findViewById(R.id.txtLabel)
        private val txtPackage: TextView = itemView.findViewById(R.id.txtPackage)
        private val check: CheckBox = itemView.findViewById(R.id.checkSelected)

        fun bind(row: AppSelectionViewModel.AppRow) {
            txtLabel.text = row.label.ifBlank { row.packageName }
            txtPackage.text = row.packageName
            // Set icon if available
            if (row.icon != null) {
                imgIcon.setImageDrawable(row.icon)
            } else {
                imgIcon.setImageDrawable(null)
                imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            // Avoid triggering listener when programmatically updating
            check.setOnCheckedChangeListener(null)
            check.isChecked = row.selected
            check.setOnCheckedChangeListener { _, isChecked ->
                onToggle(row.packageName, isChecked)
            }
            // Row click toggles checkbox for convenience
            itemView.setOnClickListener { check.performClick() }
        }
    }
}
