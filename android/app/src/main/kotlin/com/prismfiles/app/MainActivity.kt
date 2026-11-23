package com.prismfiles.app

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvFileList: RecyclerView
    private lateinit var tvActiveFolder: TextView
    private lateinit var headerPathContainer: View
    private lateinit var fabMain: FloatingActionButton
    private lateinit var tvFabBadge: TextView
    private lateinit var searchBarContainer: View
    private lateinit var etSearchQuery: EditText
    private lateinit var selectionActionBar: View
    private lateinit var tvSelectionCount: TextView

    private var currentPath: String = StorageManager.PRIMARY_STORAGE_PATH
    private var isVirtualArchive: Boolean = false
    private var virtualArchivePath: String = ""
    private var virtualInnerDir: String = ""
    private var virtualPassword: String = ""

    private var sortField: String = "name"
    private var sortDescending: Boolean = false
    private var showHidden: Boolean = false
    private var searchQuery: String = ""

    // Multi-select state
    private var isSelectionMode: Boolean = false
    private val selectedItems = mutableSetOf<String>()

    // Navigation and animation state
    enum class NavDirection { FORWARD, BACKWARD, REFRESH }
    private var navDirection = NavDirection.REFRESH
    private var wasClipboardActive: Boolean = false

    // Clipboard state (for morphing FAB)
    private var clipboardCutMode: Boolean = false
    private val clipboardPaths = mutableListOf<String>()

    // Items list
    private val displayEntries = mutableListOf<RowItem>()
    private lateinit var adapter: FileListAdapter

    // Active task progress dialog
    private var progressDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("prismfiles_sticky_prefs", Context.MODE_PRIVATE)
        loadStickyPreferences()

        initViews()
        setupDrawer()
        setupRecyclerView()
        setupBackNavigation()

        // Handle intent if launched from external VIEW archive action
        handleIncomingArchiveIntent(intent)
        intent?.getStringExtra("navigate_to_path")?.let { p ->
            if (File(p).exists()) {
                currentPath = p
            }
        }

        checkAndRequestStorage()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingArchiveIntent(intent)
        intent?.getStringExtra("navigate_to_path")?.let { p ->
            if (File(p).exists()) {
                exitVirtualArchive()
                navigateToDirectory(p)
            }
        }
    }

    private fun handleIncomingArchiveIntent(targetIntent: Intent?) {
        val uri = targetIntent?.data ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val resolvedPath: String? = when (uri.scheme) {
                "file" -> uri.path?.let { p -> if (File(p).exists()) p else null }
                "content" -> {
                    try {
                        val rawName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
                        } ?: "incoming_archive.zip"
                        val safeName = File(rawName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val tempFile = File(cacheDir, "incoming_$safeName")
                        contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) tempFile.absolutePath else null
                    } catch (_: Exception) {
                        null
                    }
                }
                else -> uri.path?.let { p -> if (File(p).exists()) p else null }
            }

            if (resolvedPath != null) {
                withContext(Dispatchers.Main) {
                    val name = File(resolvedPath).name.lowercase()
                    if (name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".rar") || name.endsWith(".apk")) {
                        openVirtualArchive(resolvedPath, "")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (StorageManager.hasFullStoragePermission(this)) {
            if (currentPath.isEmpty() || !File(currentPath).exists()) {
                currentPath = StorageManager.PRIMARY_STORAGE_PATH
            }
            refreshDirectory()
        }
        updateClipboardFab()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            progressDialog?.dismiss()
        } catch (_: Exception) {}
        progressDialog = null
    }

    private fun loadStickyPreferences() {
        sortField = prefs.getString("sort_field", "name") ?: "name"
        sortDescending = prefs.getBoolean("sort_descending", false)
        showHidden = prefs.getBoolean("show_hidden", false)
    }

    private fun saveStickyPreferences() {
        prefs.edit()
            .putString("sort_field", sortField)
            .putBoolean("sort_descending", sortDescending)
            .putBoolean("show_hidden", showHidden)
            .apply()
    }

    private fun checkAndRequestStorage() {
        if (!StorageManager.hasFullStoragePermission(this)) {
            StorageManager.requestFullStoragePermission(this)
        } else {
            currentPath = StorageManager.PRIMARY_STORAGE_PATH
            refreshDirectory()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == StorageManager.REQUEST_CODE_MANAGE_STORAGE ||
            requestCode == StorageManager.REQUEST_CODE_LEGACY_STORAGE) {
            if (StorageManager.hasFullStoragePermission(this)) {
                currentPath = StorageManager.PRIMARY_STORAGE_PATH
                refreshDirectory()
            } else {
                Toast.makeText(this, "Storage access is required to manage files", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == StorageManager.REQUEST_CODE_LEGACY_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                currentPath = StorageManager.PRIMARY_STORAGE_PATH
                refreshDirectory()
            } else {
                Toast.makeText(this, "Storage permission is required to view files", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        rvFileList = findViewById(R.id.rv_file_list)
        tvActiveFolder = findViewById(R.id.tv_active_folder)
        headerPathContainer = findViewById(R.id.header_path_container)
        fabMain = findViewById(R.id.fab_main)
        tvFabBadge = findViewById(R.id.tv_fab_badge)
        searchBarContainer = findViewById(R.id.search_bar_container)
        etSearchQuery = findViewById(R.id.et_search_query)
        selectionActionBar = findViewById(R.id.selection_action_bar)
        tvSelectionCount = findViewById(R.id.tv_selection_count)

        findViewById<View>(R.id.btn_drawer_toggle).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        headerPathContainer.setOnClickListener {
            showPathDropdownMenu()
        }

        findViewById<View>(R.id.btn_search).setOnClickListener {
            toggleSearchBar()
        }

        findViewById<View>(R.id.btn_search_close).setOnClickListener {
            toggleSearchBar()
        }

        etSearchQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q != searchQuery) {
                    searchQuery = q
                    refreshDirectory()
                }
            }
        })

        etSearchQuery.setOnEditorActionListener { _, _, _ ->
            searchQuery = etSearchQuery.text.toString().trim()
            refreshDirectory()
            true
        }

        findViewById<View>(R.id.btn_sort).setOnClickListener {
            showSortDialog()
        }

        findViewById<View?>(R.id.btn_overflow_menu)?.setOnClickListener { v ->
            showTopOverflowMenu(v)
        }

        findViewById<View>(R.id.btn_drawer_tools).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, ToolsActivity::class.java))
        }

        findViewById<View>(R.id.btn_multi_select).setOnClickListener {
            if (isSelectionMode) {
                exitSelectionMode()
            } else {
                isSelectionMode = true
                showSelectionActionBar()
                tvSelectionCount.text = "${selectedItems.size} selected"
                adapter.notifyDataSetChanged()
            }
        }

        findViewById<View>(R.id.btn_select_all).setOnClickListener {
            selectAllItems()
        }

        findViewById<View>(R.id.btn_selection_cancel).setOnClickListener {
            exitSelectionMode()
        }

        findViewById<View>(R.id.btn_selection_copy).setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                clipboardPaths.clear()
                clipboardPaths.addAll(selectedItems)
                clipboardCutMode = false
                val count = clipboardPaths.size
                updateClipboardFab()
                exitSelectionMode()
                Toast.makeText(this, "Copied $count items to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btn_selection_cut).setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                clipboardPaths.clear()
                clipboardPaths.addAll(selectedItems)
                clipboardCutMode = true
                val count = clipboardPaths.size
                updateClipboardFab()
                exitSelectionMode()
                Toast.makeText(this, "Cut $count items to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btn_selection_compress).setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                val paths = selectedItems.toList()
                exitSelectionMode()
                showCompressDialog(paths)
            }
        }

        findViewById<View>(R.id.btn_selection_delete).setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                val count = selectedItems.size
                val paths = selectedItems.toList()
                AlertDialog.Builder(this)
                    .setTitle("Delete $count items?")
                    .setMessage("Are you sure you want to permanently delete the selected items?")
                    .setPositiveButton("Delete") { _, _ ->
                        exitSelectionMode()
                        val json = JSONArray(paths).toString()
                        val taskJson = RustCore.startDeleteTask(json)
                        val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
                        parsed.onSuccess { id ->
                            TaskForegroundService.startTask(this, id, "Deleting $count items", currentPath)
                            showTaskProgressDialog(id, "Deleting items...")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        fabMain.setOnClickListener {
            fabMain.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(60)
                .withEndAction {
                    fabMain.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(80)
                        .start()
                    handleFabClick()
                }
                .start()
        }
    }

    private fun setupRecyclerView() {
        rvFileList.layoutManager = LinearLayoutManager(this)
        rvFileList.itemAnimator = null // Instant single-frame rendering
        adapter = FileListAdapter(
            items = displayEntries,
            isSelected = { path -> selectedItems.contains(path) },
            onIconClick = { item ->
                if (item.isParentRow) {
                    navigateUp()
                } else {
                    toggleItemSelection(item.path)
                }
            },
            onRowClick = { item ->
                if (isSelectionMode) {
                    if (!item.isParentRow) toggleItemSelection(item.path)
                } else {
                    if (item.isParentRow) {
                        navigateUp()
                    } else if (item.isDir) {
                        if (isVirtualArchive) {
                            openVirtualArchive(virtualArchivePath, item.virtualSubDir ?: "")
                        } else {
                            navigateToDirectory(item.path)
                        }
                    } else {
                        handleFileTap(item)
                    }
                }
            },
            onRowLongClick = { item ->
                if (!item.isParentRow) {
                    toggleItemSelection(item.path)
                }
            },
            onRowMoreClick = { item ->
                if (!item.isParentRow) {
                    showFileActionSheet(item)
                }
            }
        )
        rvFileList.adapter = adapter
    }

    private fun showTopOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "Search").setIcon(R.drawable.ic_search)
        popup.menu.add(0, 2, 1, "Sort by...").setIcon(R.drawable.ic_sort)
        popup.menu.add(0, 3, 2, if (isSelectionMode) "Exit Multi-select" else "Multi-select").setIcon(R.drawable.ic_checklist)
        popup.menu.add(0, 4, 3, "Cushion Treemap").setIcon(R.drawable.ic_storage)
        popup.menu.add(0, 5, 4, "New Folder").setIcon(R.drawable.ic_folder)
        popup.menu.add(0, 6, 5, "Refresh").setIcon(R.drawable.ic_refresh)
        popup.menu.add(0, 7, 6, "Utilities").setIcon(R.drawable.ic_build)

        popup.setForceShowIcon(true)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (searchBarContainer.visibility != View.VISIBLE) {
                        toggleSearchBar()
                    } else {
                        etSearchQuery.requestFocus()
                    }
                    true
                }
                2 -> {
                    showSortDialog()
                    true
                }
                3 -> {
                    if (isSelectionMode) {
                        exitSelectionMode()
                    } else {
                        isSelectionMode = true
                        showSelectionActionBar()
                        tvSelectionCount.text = "${selectedItems.size} selected"
                        adapter.notifyDataSetChanged()
                    }
                    true
                }
                4 -> {
                    startActivity(Intent(this, TreemapActivity::class.java).apply {
                        putExtra("target_path", currentPath)
                    })
                    true
                }
                5 -> {
                    showCreateNewDialog()
                    true
                }
                6 -> {
                    refreshDirectory()
                    true
                }
                7 -> {
                    startActivity(Intent(this, ToolsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun getStorageSpaceString(dirPath: String): String {
        return try {
            val stat = StatFs(dirPath)
            val blockSize = stat.blockSizeLong
            val avail = stat.availableBlocksLong * blockSize
            val total = stat.blockCountLong * blockSize
            "${formatSize(avail)} free / ${formatSize(total)}"
        } catch (_: Exception) {
            ""
        }
    }

    private fun calculateFolderStats(dir: File, maxDepth: Int = 3): Pair<Long, Int> {
        var totalBytes = 0L
        var count = 0
        fun walk(d: File, depth: Int) {
            if (depth > maxDepth) return
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isFile) {
                    totalBytes += f.length()
                    count++
                } else if (f.isDirectory && !f.name.startsWith(".")) {
                    walk(f, depth + 1)
                }
            }
        }
        walk(dir, 0)
        return totalBytes to count
    }

    private fun setupDrawer() {
        val rvDrawer = findViewById<RecyclerView>(R.id.rv_drawer_items)
        rvDrawer.layoutManager = LinearLayoutManager(this)

        val items = mutableListOf<DrawerEntry>()
        for (shortcut in StorageManager.getQuickJumpShortcuts()) {
            val isPrimary = shortcut.second == StorageManager.PRIMARY_STORAGE_PATH
            val iconRes = when (shortcut.first) {
                "Internal Storage" -> R.drawable.ic_storage
                "Download" -> R.drawable.ic_download
                "DCIM", "Pictures" -> R.drawable.ic_image
                "Music" -> R.drawable.ic_audio
                "Documents" -> R.drawable.ic_doc
                else -> R.drawable.ic_folder
            }
            val initialSubtitle = if (isPrimary) {
                getStorageSpaceString(shortcut.second)
            } else {
                try {
                    val cnt = File(shortcut.second).list()?.size
                    if (cnt != null) "$cnt items" else ""
                } catch (_: Exception) { "" }
            }
            items.add(
                DrawerEntry(
                    name = shortcut.first,
                    path = shortcut.second,
                    iconRes = iconRes,
                    subtitle = initialSubtitle,
                    isStorageVolume = isPrimary
                )
            )
        }

        for (v in StorageManager.getAvailableStorageVolumes(this)) {
            if (!v.isPrimary && !items.any { it.path == v.path }) {
                items.add(
                    DrawerEntry(
                        name = v.name,
                        path = v.path,
                        iconRes = R.drawable.ic_storage,
                        subtitle = getStorageSpaceString(v.path),
                        isStorageVolume = true
                    )
                )
            }
        }

        val drawerAdapter = DrawerAdapter(items) { path ->
            drawerLayout.closeDrawer(GravityCompat.START)
            exitVirtualArchive()
            navigateToDirectory(path)
        }
        rvDrawer.adapter = drawerAdapter

        lifecycleScope.launch(Dispatchers.IO) {
            for (i in items.indices) {
                val entry = items[i]
                if (entry.isStorageVolume) continue
                val f = File(entry.path)
                if (f.exists() && f.isDirectory) {
                    val (bytes, count) = calculateFolderStats(f, maxDepth = 3)
                    val sub = if (count > 0) "${formatSize(bytes)} • $count items" else "Empty"
                    withContext(Dispatchers.Main) {
                        entry.subtitle = sub
                        drawerAdapter.notifyItemChanged(i)
                    }
                }
            }
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                for (i in items.indices) {
                    val entry = items[i]
                    if (entry.isStorageVolume) {
                        entry.subtitle = getStorageSpaceString(entry.path)
                        drawerAdapter.notifyItemChanged(i)
                    }
                }
            }
        })
    }

    private fun updateHeaderTitle() {
        if (isVirtualArchive) {
            val arcName = File(virtualArchivePath).name
            tvActiveFolder.text = if (virtualInnerDir.isEmpty()) arcName else "$arcName/$virtualInnerDir"
        } else {
            val f = File(currentPath)
            tvActiveFolder.text = if (currentPath == StorageManager.PRIMARY_STORAGE_PATH) "Internal Storage" else f.name
        }
    }

    fun refreshDirectory() {
        if (isVirtualArchive) {
            loadVirtualArchiveDir()
        } else {
            loadPhysicalDirectory()
        }
    }

    private fun loadPhysicalDirectory() {
        if (!File(currentPath).exists()) {
            currentPath = StorageManager.PRIMARY_STORAGE_PATH
        }
        updateHeaderTitle()
        lifecycleScope.launch(Dispatchers.IO) {
            val json = RustCore.listDirectory(
                path = currentPath,
                sortField = sortField,
                descending = sortDescending,
                showHidden = showHidden,
                query = searchQuery
            )
            val parsed = RustCore.parseEnvelope(json) { obj ->
                val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
                val list = mutableListOf<FileEntryItem>()
                for (i in 0 until entriesArr.length()) {
                    val e = entriesArr.optJSONObject(i) ?: continue
                    val isDir = e.optBoolean("is_dir", false)
                    val p = e.optString("path", "")
                    val childCount = if (isDir) {
                        try {
                            File(p).list()?.size
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    list.add(
                        FileEntryItem(
                            name = e.optString("name", "Unknown"),
                            path = p,
                            isDir = isDir,
                            isSymlink = e.optBoolean("is_symlink", false),
                            isHidden = e.optBoolean("is_hidden", false),
                            size = e.optLong("size", 0L),
                            modifiedMs = e.optLong("modified_ms", 0L),
                            permissions = "",
                            extension = e.optString("extension", ""),
                            category = e.optString("category", "file"),
                            childCount = childCount
                        )
                    )
                }
                val dirPath = obj.optString("path", obj.optString("current_path", currentPath))
                val parentP = if (obj.has("parent") && !obj.isNull("parent")) {
                    obj.optString("parent")
                } else if (obj.has("parent_path") && !obj.isNull("parent_path")) {
                    obj.optString("parent_path")
                } else {
                    null
                }
                DirectoryListingResult(
                    currentPath = dirPath,
                    parentPath = parentP,
                    entries = list,
                    totalCount = list.size,
                    totalSizeBytes = list.sumOf { it.size },
                    canWrite = obj.optBoolean("can_write", true)
                )
            }

            withContext(Dispatchers.Main) {
                displayEntries.clear()
                // Pinned ".." parent row
                if (currentPath != "/" && currentPath != StorageManager.PRIMARY_STORAGE_PATH) {
                    displayEntries.add(RowItem.parentRow())
                }
                parsed.onSuccess { dir ->
                    for (e in dir.entries) {
                        displayEntries.add(RowItem.fromFileEntry(e))
                    }
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "Read error: ${err.message}", Toast.LENGTH_SHORT).show()
                }
                adapter.notifyDataSetChanged()
                applyListTransitionAnimation()
            }
        }
    }

    private fun loadVirtualArchiveDir() {
        updateHeaderTitle()
        lifecycleScope.launch(Dispatchers.IO) {
            val json = RustCore.listArchiveVirtual(virtualArchivePath, virtualInnerDir, virtualPassword)
            val parsed = RustCore.parseEnvelope(json) { obj ->
                val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
                val list = mutableListOf<VirtualEntryItem>()
                for (i in 0 until entriesArr.length()) {
                    val e = entriesArr.optJSONObject(i) ?: continue
                    list.add(
                        VirtualEntryItem(
                            name = e.optString("name", "Unknown"),
                            fullPath = e.optString("full_path", ""),
                            isDir = e.optBoolean("is_dir", false),
                            size = e.optLong("size", 0L),
                            compressedSize = e.optLong("compressed_size", 0L),
                            method = e.optString("method", ""),
                            encrypted = e.optBoolean("encrypted", false),
                            crc32 = e.optLong("crc32", 0L)
                        )
                    )
                }
                ArchiveVirtualResult(
                    archivePath = obj.optString("archive_path", virtualArchivePath),
                    innerDir = obj.optString("inner_dir", ""),
                    entries = list,
                    totalEntries = obj.optInt("total_entries", list.size)
                )
            }

            withContext(Dispatchers.Main) {
                displayEntries.clear()
                // Pinned parent row
                displayEntries.add(RowItem.parentRow())
                parsed.onSuccess { res ->
                    for (v in res.entries) {
                        displayEntries.add(RowItem.fromVirtualEntry(v, virtualArchivePath, virtualInnerDir))
                    }
                }.onFailure { err ->
                    val msg = err.message ?: ""
                    if (msg.contains("password", true) || msg.contains("encrypt", true) || msg.contains("crypto", true)) {
                        promptArchivePassword(virtualArchivePath)
                    } else {
                        Toast.makeText(this@MainActivity, "Archive error: $msg", Toast.LENGTH_SHORT).show()
                    }
                }
                adapter.notifyDataSetChanged()
                applyListTransitionAnimation()
            }
        }
    }

    private fun applyListTransitionAnimation() {
        rvFileList.scrollToPosition(0)
        when (navDirection) {
            NavDirection.FORWARD -> {
                rvFileList.translationX = 50f
                rvFileList.alpha = 0f
                rvFileList.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(140)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            NavDirection.BACKWARD -> {
                rvFileList.translationX = -50f
                rvFileList.alpha = 0f
                rvFileList.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(140)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            NavDirection.REFRESH -> {
                rvFileList.alpha = 0.5f
                rvFileList.animate()
                    .alpha(1f)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        navDirection = NavDirection.REFRESH
    }

    fun navigateToDirectory(path: String) {
        val f = File(path)
        if (f.exists() && f.isDirectory) {
            navDirection = NavDirection.FORWARD
            currentPath = f.absolutePath
            isVirtualArchive = false
            exitSelectionMode()
            refreshDirectory()
        }
    }

    fun openVirtualArchive(archivePath: String, innerDir: String) {
        navDirection = NavDirection.FORWARD
        isVirtualArchive = true
        virtualArchivePath = archivePath
        virtualInnerDir = innerDir
        exitSelectionMode()
        refreshDirectory()
    }

    private fun promptArchivePassword(archivePath: String) {
        val input = EditText(this).apply {
            hint = "Archive Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Password Required")
            .setMessage("This archive is password-protected. Enter password to view:")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val pw = input.text.toString()
                if (pw.isNotEmpty()) {
                    virtualPassword = pw
                    loadVirtualArchiveDir()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                exitVirtualArchive()
                refreshDirectory()
            }
            .setCancelable(false)
            .show()
    }

    private fun exitVirtualArchive() {
        navDirection = NavDirection.BACKWARD
        isVirtualArchive = false
        virtualArchivePath = ""
        virtualInnerDir = ""
        virtualPassword = ""
    }

    private fun navigateUp() {
        navDirection = NavDirection.BACKWARD
        if (isVirtualArchive) {
            if (virtualInnerDir.isEmpty()) {
                exitVirtualArchive()
                refreshDirectory()
            } else {
                val parent = File(virtualInnerDir).parent ?: ""
                virtualInnerDir = parent
                refreshDirectory()
            }
        } else {
            val f = File(currentPath)
            val parent = f.parent
            if (parent != null && f.absolutePath != StorageManager.PRIMARY_STORAGE_PATH && f.absolutePath != "/") {
                currentPath = parent
                exitSelectionMode()
                refreshDirectory()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                if (isSelectionMode) {
                    exitSelectionMode()
                    return
                }
                if (searchBarContainer.visibility == View.VISIBLE) {
                    searchBarContainer.visibility = View.GONE
                    searchQuery = ""
                    etSearchQuery.setText("")
                    refreshDirectory()
                    return
                }
                if (isVirtualArchive) {
                    navigateUp()
                    return
                }
                if (currentPath != StorageManager.PRIMARY_STORAGE_PATH && currentPath != "/") {
                    navigateUp()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun toggleSearchBar() {
        if (searchBarContainer.visibility == View.VISIBLE) {
            searchBarContainer.animate()
                .translationY(-60f)
                .alpha(0f)
                .setDuration(120)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    searchBarContainer.visibility = View.GONE
                    searchBarContainer.translationY = 0f
                    searchQuery = ""
                    etSearchQuery.setText("")
                    refreshDirectory()
                }
                .start()
        } else {
            searchBarContainer.visibility = View.VISIBLE
            searchBarContainer.alpha = 0f
            searchBarContainer.translationY = -60f
            searchBarContainer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(140)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    etSearchQuery.requestFocus()
                }
                .start()
        }
    }

    private fun showSelectionActionBar() {
        if (selectionActionBar.visibility != View.VISIBLE) {
            selectionActionBar.visibility = View.VISIBLE
            selectionActionBar.alpha = 0f
            selectionActionBar.translationY = -60f
            selectionActionBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(140)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun hideSelectionActionBar() {
        if (selectionActionBar.visibility == View.VISIBLE) {
            selectionActionBar.animate()
                .translationY(-60f)
                .alpha(0f)
                .setDuration(120)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    selectionActionBar.visibility = View.GONE
                    selectionActionBar.translationY = 0f
                }
                .start()
        }
    }

    private fun toggleItemSelection(path: String) {
        if (selectedItems.contains(path)) {
            selectedItems.remove(path)
        } else {
            selectedItems.add(path)
        }
        if (selectedItems.isEmpty()) {
            exitSelectionMode()
        } else {
            isSelectionMode = true
            showSelectionActionBar()
            tvSelectionCount.text = "${selectedItems.size} selected"
            adapter.notifyDataSetChanged()
        }
    }

    private fun selectAllItems() {
        val nonParentItems = displayEntries.filter { !it.isParentRow }
        if (selectedItems.size == nonParentItems.size && nonParentItems.isNotEmpty()) {
            exitSelectionMode()
        } else {
            for (item in nonParentItems) {
                selectedItems.add(item.path)
            }
            isSelectionMode = true
            showSelectionActionBar()
            tvSelectionCount.text = "${selectedItems.size} selected"
            adapter.notifyDataSetChanged()
        }
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        hideSelectionActionBar()
        adapter.notifyDataSetChanged()
    }

    private fun updateClipboardFab() {
        fabMain.imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
        val isClipboardNow = clipboardPaths.isNotEmpty()
        if (isClipboardNow != wasClipboardActive) {
            wasClipboardActive = isClipboardNow
            fabMain.animate()
                .rotationBy(if (isClipboardNow) 90f else -90f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(75)
                .withEndAction {
                    if (isClipboardNow) {
                        fabMain.setImageResource(R.drawable.ic_paste)
                        tvFabBadge.visibility = View.VISIBLE
                        tvFabBadge.text = clipboardPaths.size.toString()
                        tvFabBadge.scaleX = 0f
                        tvFabBadge.scaleY = 0f
                        tvFabBadge.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    } else {
                        fabMain.setImageResource(R.drawable.ic_add)
                        tvFabBadge.visibility = View.GONE
                    }
                    fabMain.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(75)
                        .start()
                }
                .start()
        } else {
            if (isClipboardNow) {
                fabMain.setImageResource(R.drawable.ic_paste)
                tvFabBadge.visibility = View.VISIBLE
                tvFabBadge.text = clipboardPaths.size.toString()
            } else {
                fabMain.setImageResource(R.drawable.ic_add)
                tvFabBadge.visibility = View.GONE
            }
        }
    }

    private fun handleFabClick() {
        if (clipboardPaths.isNotEmpty()) {
            if (isVirtualArchive) {
                Toast.makeText(this, "Cannot paste files directly into an archive virtual view. Extract archive first.", Toast.LENGTH_SHORT).show()
                return
            }
            // Paste action
            val sources = JSONArray(clipboardPaths).toString()
            val moveMode = clipboardCutMode
            val count = clipboardPaths.size
            val taskJson = RustCore.startCopyMoveTask(sources, currentPath, moveMode)
            val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
            parsed.onSuccess { taskId ->
                TaskForegroundService.startTask(
                    this, taskId,
                    if (moveMode) "Moving $count files" else "Copying $count files",
                    currentPath
                )
                showTaskProgressDialog(taskId, if (moveMode) "Moving files..." else "Copying files...")
                clipboardPaths.clear()
                updateClipboardFab()
            }.onFailure { err ->
                Toast.makeText(this, "Task failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // "+" FAB: Create New
            showCreateNewDialog()
        }
    }

    private fun showCreateNewDialog() {
        if (isVirtualArchive) {
            Toast.makeText(this, "Cannot create items directly inside an archive virtual view", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("New Folder", "New Empty File", "Create Archive (.7z / .zip)")
        AlertDialog.Builder(this)
            .setTitle("Create")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showInputDialog("New Folder", "Folder name", positiveLabel = "Create") { name ->
                        val target = "$currentPath/$name"
                        RustCore.createFolder(target)
                        refreshDirectory()
                    }
                    1 -> showInputDialog("New File", "File name", positiveLabel = "Create") { name ->
                        val target = "$currentPath/$name"
                        RustCore.createEmptyFile(target)
                        refreshDirectory()
                    }
                    2 -> {
                        showCompressDialog(listOf(currentPath))
                    }
                }
            }
            .show()
    }

    private fun showInputDialog(
        title: String,
        hint: String,
        initialText: String = "",
        positiveLabel: String = "OK",
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            this.hint = hint
            if (initialText.isNotEmpty()) {
                setText(initialText)
                val dot = initialText.lastIndexOf('.')
                setSelection(0, if (dot > 0) dot else initialText.length)
            }
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positiveLabel) { _, _ ->
                val txt = input.text.toString().trim()
                    .replace("/", "")
                    .replace("\\", "")
                    .replace("\u0000", "")
                if (txt.isNotEmpty()) {
                    onConfirm(txt)
                } else {
                    Toast.makeText(this, "Name cannot be empty or contain slashes", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleFileTap(item: RowItem) {
        val name = item.name.lowercase()
        val isArchive = name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".tar") ||
                name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".tar.bz2") ||
                name.endsWith(".rar") || name.endsWith(".apk") || name.endsWith(".xapk") ||
                name.endsWith(".iso") || name.endsWith(".dmg")

        if (isArchive && !isVirtualArchive) {
            // Virtual folder browsing
            openVirtualArchive(item.path, "")
        } else if (item.category == "image" || item.category == "video" || item.category == "audio" || item.category == "document" || item.category == "code") {
            // Tap directly opens in Viewer preview
            openInViewer(item)
        } else {
            showFileActionSheet(item)
        }
    }

    private fun showFileActionSheet(item: RowItem) {
        val sheet = BottomSheetDialog(this)
        sheet.window?.setWindowAnimations(R.style.Animation_PrismFiles_BottomSheet)
        val v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_file_actions, null)
        sheet.setContentView(v)

        v.findViewById<TextView>(R.id.sheet_file_name).text = item.name
        v.findViewById<TextView>(R.id.sheet_file_meta).text = item.meta

        val isArchive = item.name.lowercase().let {
            it.endsWith(".zip") || it.endsWith(".7z") || it.endsWith(".tar") || it.endsWith(".tar.gz") ||
            it.endsWith(".rar") || it.endsWith(".iso") || it.endsWith(".apk")
        }

        val extractHereBtn = v.findViewById<View>(R.id.action_extract_here)
        val extractToBtn = v.findViewById<View>(R.id.action_extract_to)
        val testArchiveBtn = v.findViewById<View>(R.id.action_test_archive)
        val openExternalBtn = v.findViewById<View>(R.id.action_open_external)

        if (!isArchive) {
            extractHereBtn.visibility = View.GONE
            extractToBtn.visibility = View.GONE
            testArchiveBtn.visibility = View.GONE
        }

        if (item.isDir || item.isParentRow || isVirtualArchive) {
            openExternalBtn.visibility = View.GONE
        } else {
            openExternalBtn.visibility = View.VISIBLE
            val isApk = item.name.lowercase().endsWith(".apk")
            v.findViewById<TextView>(R.id.tv_action_open_external)?.text = if (isApk) "Install APK..." else "Open with other app / Share..."
            openExternalBtn.setOnClickListener {
                sheet.dismiss()
                openWithExternalApp(item)
            }
        }

        v.findViewById<View>(R.id.action_open_view).setOnClickListener {
            sheet.dismiss()
            openInViewer(item)
        }

        v.findViewById<View>(R.id.action_convert).setOnClickListener {
            sheet.dismiss()
            showDonutWheelDialog(item.path, item.extension)
        }

        extractHereBtn.setOnClickListener {
            sheet.dismiss()
            startExtractJob(item.path, currentPath)
        }

        extractToBtn.setOnClickListener {
            sheet.dismiss()
            val folderName = File(item.path).nameWithoutExtension
            val outDir = "$currentPath/$folderName"
            startExtractJob(item.path, outDir)
        }

        testArchiveBtn.setOnClickListener {
            sheet.dismiss()
            val taskJson = RustCore.startTestArchiveTask(item.path, "")
            val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
            parsed.onSuccess { id ->
                showTaskProgressDialog(id, "Testing Archive Integrity...")
            }
        }

        v.findViewById<View>(R.id.action_compress).setOnClickListener {
            sheet.dismiss()
            showCompressDialog(listOf(item.path))
        }

        v.findViewById<View>(R.id.action_copy).setOnClickListener {
            sheet.dismiss()
            clipboardCutMode = false
            clipboardPaths.clear()
            clipboardPaths.add(item.path)
            updateClipboardFab()
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        v.findViewById<View>(R.id.action_cut).setOnClickListener {
            sheet.dismiss()
            clipboardCutMode = true
            clipboardPaths.clear()
            clipboardPaths.add(item.path)
            updateClipboardFab()
            Toast.makeText(this, "Cut to clipboard", Toast.LENGTH_SHORT).show()
        }

        v.findViewById<View>(R.id.action_rename).setOnClickListener {
            sheet.dismiss()
            showInputDialog("Rename", "New name", initialText = item.name, positiveLabel = "Rename") { newName ->
                RustCore.renameEntry(item.path, newName)
                refreshDirectory()
            }
        }

        v.findViewById<View>(R.id.action_checksum).setOnClickListener {
            sheet.dismiss()
            startChecksumJob(item.path)
        }

        v.findViewById<View>(R.id.action_shred).setOnClickListener {
            sheet.dismiss()
            confirmShred(listOf(item.path))
        }

        v.findViewById<View>(R.id.action_delete).setOnClickListener {
            sheet.dismiss()
            confirmDelete(listOf(item.path))
        }

        sheet.show()
    }

    private fun openInViewer(item: RowItem) {
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_FILE_PATH, item.path)
            putExtra(ViewerActivity.EXTRA_FILE_NAME, item.name)
            putExtra(ViewerActivity.EXTRA_IS_ARCHIVE_ENTRY, isVirtualArchive)
            putExtra(ViewerActivity.EXTRA_ARCHIVE_PATH, virtualArchivePath)
            putExtra(ViewerActivity.EXTRA_ENTRY_PATH, item.virtualFullPath ?: "")
        }
        startActivity(intent)
    }

    private fun openWithExternalApp(item: RowItem) {
        try {
            val file = File(item.path)
            if (!file.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val isApk = file.extension.lowercase() == "apk"
            val mime = if (isApk) {
                "application/vnd.android.package-archive"
            } else {
                android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (isApk) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                    val reqIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(reqIntent)
                    Toast.makeText(this, "Please allow PrismFiles to install packages", Toast.LENGTH_SHORT).show()
                    return
                }
                startActivity(intent)
            } else {
                val chooser = Intent.createChooser(intent, "Open with...").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDonutWheelDialog(filePath: String, ext: String) {
        val profile = WheelProfiles.getProfileForExtension(ext)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_donut_wheel, null)
        dialog.setContentView(v)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setWindowAnimations(R.style.Animation_PrismFiles_Dialog)

        v.findViewById<TextView>(R.id.tv_donut_title)?.text = "Convert / Compress"
        v.findViewById<TextView>(R.id.tv_donut_subtitle)?.text = "${File(filePath).name} • Rotate wheel to select target format"

        val wheel = v.findViewById<DonutWheelView>(R.id.donut_wheel_view)
        wheel.setWheelProfile(profile)
        wheel.onSliceSelectedListener = { slice ->
            dialog.dismiss()
            when (slice.actionType) {
                "extract" -> {
                    val parentDir = File(filePath).parentFile ?: File(currentPath)
                    val destDir = File(parentDir, File(filePath).nameWithoutExtension).absolutePath
                    startExtractJob(filePath, destDir)
                }
                else -> {
                    executeFormatConversion(filePath, slice.targetFormat)
                }
            }
        }

        v.findViewById<View>(R.id.btn_donut_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun executeFormatConversion(sourcePath: String, targetFormat: String) {
        val parentDir = File(sourcePath).parentFile ?: File(currentPath)
        val convertedDir = File(parentDir, "converted")
        if (!convertedDir.exists()) {
            convertedDir.mkdirs()
        }
        val outName = "${File(sourcePath).nameWithoutExtension}.$targetFormat"
        val destFile = File(convertedDir, outName)

        val req = JSONObject().apply {
            put("source_path", sourcePath)
            put("target_format", targetFormat)
            put("dest_path", destFile.absolutePath)
        }
        val isCompress = targetFormat in listOf("zip", "7z", "tar", "tar.gz", "tar.bz2", "tar.xz", "tar.zst", "gz", "bz2", "xz")
        val actionVerb = if (isCompress) "Compressing to" else "Converting to"
        val taskJson = RustCore.startConvertTask(req.toString())
        val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
        parsed.onSuccess { id ->
            TaskForegroundService.startTask(this, id, "$actionVerb ${targetFormat.uppercase()}", destFile.absolutePath)
            showTaskProgressDialog(id, "$actionVerb ${targetFormat.uppercase()}...")
            android.media.MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), null, null)
        }.onFailure {
            Toast.makeText(this, "Task error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startExtractJob(archivePath: String, destDir: String, password: String = virtualPassword) {
        val taskJson = RustCore.startExtractTask(archivePath, destDir, password, "")
        val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
        parsed.onSuccess { id ->
            TaskForegroundService.startTask(this, id, "Extracting Archive", destDir)
            showTaskProgressDialog(id, "Extracting Archive...")
        }.onFailure {
            val msg = it.message ?: ""
            if (msg.contains("password", true) || msg.contains("encrypt", true)) {
                promptExtractPassword(archivePath, destDir)
            } else {
                Toast.makeText(this, "Extract error: $msg", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptExtractPassword(archivePath: String, destDir: String) {
        val input = EditText(this).apply {
            hint = "Archive Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Password Required for Extraction")
            .setMessage("This archive is encrypted. Enter password to extract:")
            .setView(input)
            .setPositiveButton("Extract") { _, _ ->
                val pw = input.text.toString()
                if (pw.isNotEmpty()) {
                    virtualPassword = pw
                    startExtractJob(archivePath, destDir, pw)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCompressDialog(paths: List<String>) {
        val dialog = Dialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_compress, null)
        dialog.setContentView(v)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setWindowAnimations(R.style.Animation_PrismFiles_Dialog)

        val etName = v.findViewById<EditText>(R.id.et_archive_name)
        val defaultName = if (paths.size == 1) File(paths[0]).nameWithoutExtension else "archive"
        etName.setText(defaultName)

        val spFormat = v.findViewById<Spinner>(R.id.sp_archive_format)
        val formats = arrayOf("7z", "zip", "tar", "tar.gz", "tar.bz2", "tar.xz", "tar.zst")
        spFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats)

        val spLevel = v.findViewById<Spinner>(R.id.sp_compression_level)
        val levels = arrayOf("Normal", "Store", "Fastest", "Fast", "Maximum", "Ultra")
        spLevel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels)

        val spSplit = v.findViewById<Spinner>(R.id.sp_split_volumes)
        val splitOptions = arrayOf("None", "10 MB", "50 MB", "100 MB", "700 MB (CD)", "2000 MB (FAT32)")
        spSplit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, splitOptions)

        v.findViewById<View>(R.id.btn_compress_cancel).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.btn_compress_confirm).setOnClickListener {
            val name = etName.text.toString().trim().ifEmpty { "archive" }
            val fmt = spFormat.selectedItem.toString()
            val lvl = spLevel.selectedItem.toString()
            val pw = v.findViewById<EditText>(R.id.et_archive_password).text.toString()

            val splitMb = when (spSplit.selectedItemPosition) {
                1 -> 10
                2 -> 50
                3 -> 100
                4 -> 700
                5 -> 2000
                else -> null
            }

            val req = JSONObject().apply {
                put("archive_path", "$currentPath/$name.$fmt")
                put("format", fmt)
                put("compression_level", lvl)
                if (pw.isNotEmpty()) put("password", pw)
                if (splitMb != null) put("split_volume_size_mb", splitMb)
                put("source_paths", JSONArray(paths))
            }

            dialog.dismiss()
            val taskJson = RustCore.startCreateArchiveTask(req.toString())
            val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
            parsed.onSuccess { id ->
                TaskForegroundService.startTask(this, id, "Compressing $name.$fmt", "$currentPath/$name.$fmt")
                showTaskProgressDialog(id, "Compressing Archive...")
            }.onFailure {
                Toast.makeText(this, "Compress error: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun startChecksumJob(filePath: String) {
        val taskJson = RustCore.startChecksumTask(filePath)
        val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
        parsed.onSuccess { id ->
            showTaskProgressDialog(id, "Computing Checksums...")
        }
    }

    private fun confirmShred(paths: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("DoD 5220.22-M Shredder")
            .setMessage("Permanently overwrite ${paths.size} item(s) with 3 DoD sanitization passes? Data cannot be recovered.")
            .setPositiveButton("Shred Permanently") { _, _ ->
                val taskJson = RustCore.startShredTask(JSONArray(paths).toString())
                val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
                parsed.onSuccess { id ->
                    showTaskProgressDialog(id, "Shredding files (DoD 3-pass)...")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(paths: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete ${paths.size} item(s)?")
            .setPositiveButton("Delete") { _, _ ->
                val taskJson = RustCore.startDeleteTask(JSONArray(paths).toString())
                val parsed = RustCore.parseEnvelope(taskJson) { it.optLong("task_id") }
                parsed.onSuccess { id ->
                    showTaskProgressDialog(id, "Deleting items...")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTaskProgressDialog(taskId: Long, title: String) {
        progressDialog?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        dialog.setContentView(v)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setWindowAnimations(R.style.Animation_PrismFiles_Dialog)
        dialog.setCancelable(false)

        val tvTitle = v.findViewById<TextView>(R.id.tv_dialog_task_title)
        val tvItem = v.findViewById<TextView>(R.id.tv_dialog_task_item)
        val pb = v.findViewById<ProgressBar>(R.id.pb_dialog_task)
        val tvStats = v.findViewById<TextView>(R.id.tv_dialog_task_stats)
        val btnPause = v.findViewById<Button>(R.id.btn_dialog_task_pause)
        val btnHide = v.findViewById<Button>(R.id.btn_dialog_task_hide)
        val btnCancel = v.findViewById<Button>(R.id.btn_dialog_task_cancel)

        tvTitle.text = title

        btnHide.setOnClickListener {
            dialog.dismiss()
        }

        var isPaused = false
        btnPause.setOnClickListener {
            if (isPaused) {
                RustCore.resumeTask(taskId)
                btnPause.text = "Pause"
                isPaused = false
            } else {
                RustCore.pauseTask(taskId)
                btnPause.text = "Resume"
                isPaused = true
            }
        }

        btnCancel.setOnClickListener {
            RustCore.cancelTask(taskId)
            if (title.contains("Checksum", true) || title.contains("Testing Archive", true)) {
                RustCore.dropTask(taskId)
            }
            dialog.dismiss()
        }

        progressDialog = dialog
        dialog.show()

        lifecycleScope.launch {
            while (dialog.isShowing) {
                delay(250)
                val snapJson = RustCore.getTaskSnapshot(taskId)
                val snap = parseDialogSnapshot(snapJson)
                if (snap != null) {
                    val p = (snap.percent * 100f).toInt().coerceIn(0, 100)
                    pb.progress = p
                    tvItem.text = snap.currentFile
                    tvStats.text = "$p%   ${snap.itemsProcessed}/${snap.totalItems} items   ${formatBytes(snap.bytesProcessed)} / ${formatBytes(snap.totalBytes)}"

                    if (snap.state == "Completed") {
                        dialog.dismiss()
                        refreshDirectory()
                        if (title.contains("Checksum", true) && !snap.resultJson.isNullOrEmpty()) {
                            showChecksumResultDialog(snap.resultJson)
                            RustCore.dropTask(taskId)
                        } else if (title.contains("Testing Archive", true) && !snap.resultJson.isNullOrEmpty()) {
                            showIntegrityResultDialog(snap.resultJson)
                            RustCore.dropTask(taskId)
                        } else {
                            Toast.makeText(this@MainActivity, "Task Completed", Toast.LENGTH_SHORT).show()
                        }
                        break
                    } else if (snap.state == "Failed") {
                        dialog.dismiss()
                        refreshDirectory()
                        val err = snap.errorMessage ?: ""
                        if ((err.contains("password", true) || err.contains("encrypt", true)) && title.contains("Extract", true)) {
                            promptExtractPassword(snap.currentFile, currentPath)
                        } else {
                            Toast.makeText(this@MainActivity, "Task Failed: $err", Toast.LENGTH_LONG).show()
                        }
                        if (title.contains("Checksum", true) || title.contains("Testing Archive", true)) {
                            RustCore.dropTask(taskId)
                        }
                        break
                    } else if (snap.state == "Canceled") {
                        dialog.dismiss()
                        refreshDirectory()
                        Toast.makeText(this@MainActivity, "Task Canceled", Toast.LENGTH_SHORT).show()
                        if (title.contains("Checksum", true) || title.contains("Testing Archive", true)) {
                            RustCore.dropTask(taskId)
                        }
                        break
                    }
                }
            }
        }
    }

    private fun showChecksumResultDialog(resultJson: String) {
        try {
            val obj = JSONObject(resultJson)
            val name = File(obj.optString("path")).name
            val size = formatBytes(obj.optLong("size"))
            val sha256 = obj.optString("sha256")
            val md5 = obj.optString("md5")
            val sha512 = obj.optString("sha512")
            val blake3 = obj.optString("blake3")

            val msg = "File: $name ($size)\n\n" +
                    "SHA-256:\n$sha256\n\n" +
                    "MD5:\n$md5\n\n" +
                    "BLAKE3:\n$blake3\n\n" +
                    "SHA-512:\n$sha512"

            AlertDialog.Builder(this)
                .setTitle("Checksum Verification")
                .setMessage(msg)
                .setPositiveButton("Copy SHA-256") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", sha256))
                    Toast.makeText(this, "SHA-256 copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Copy All") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Checksums", msg))
                    Toast.makeText(this, "All checksums copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun showIntegrityResultDialog(resultJson: String) {
        try {
            val obj = JSONObject(resultJson)
            val passed = obj.optBoolean("passed", false)
            val total = obj.optInt("checked_entries", 0)
            val failedArr = obj.optJSONArray("failed_entries")
            val failedCount = failedArr?.length() ?: 0

            val title = if (passed) "Integrity Check: PASSED" else "Integrity Check: FAILED"
            val msg = if (passed) {
                "Archive is completely intact.\nSuccessfully verified $total entries without CRC/decompression errors."
            } else {
                val sb = StringBuilder()
                sb.append("Corrupted archive detected!\n$failedCount out of $total entries failed CRC/decompression check:\n\n")
                if (failedArr != null) {
                    for (i in 0 until minOf(failedArr.length(), 10)) {
                        sb.append("• ").append(failedArr.optString(i)).append("\n")
                    }
                }
                sb.toString()
            }

            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun parseDialogSnapshot(json: String): TaskSnapshotInfo? {
        return try {
            val root = JSONObject(json)
            if (!root.optBoolean("ok", false)) return null
            val d = root.optJSONObject("data") ?: return null
            TaskSnapshotInfo(
                taskId = d.optLong("task_id"),
                title = d.optString("title"),
                percent = d.optDouble("percent", 0.0).toFloat(),
                itemsProcessed = d.optLong("items_processed"),
                totalItems = d.optLong("total_items"),
                bytesProcessed = d.optLong("bytes_processed"),
                totalBytes = d.optLong("total_bytes"),
                state = d.optString("state"),
                currentFile = d.optString("current_file"),
                errorMessage = if (d.has("error_message") && !d.isNull("error_message")) d.optString("error_message") else null,
                resultJson = if (d.has("result_json") && !d.isNull("result_json")) d.optString("result_json") else null
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun showSortDialog() {
        val fields = arrayOf("Name", "Size", "Date Modified", "File Type")
        val fieldKeys = arrayOf("name", "size", "date", "type")
        val currentIdx = fieldKeys.indexOf(sortField).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Sort By")
            .setSingleChoiceItems(fields, currentIdx) { d, which ->
                sortField = fieldKeys[which]
                saveStickyPreferences()
                d.dismiss()
                refreshDirectory()
            }
            .setPositiveButton(if (sortDescending) "Order: Descending" else "Order: Ascending") { _, _ ->
                sortDescending = !sortDescending
                saveStickyPreferences()
                refreshDirectory()
            }
            .setNeutralButton(if (showHidden) "Hide Hidden" else "Show Hidden") { _, _ ->
                showHidden = !showHidden
                saveStickyPreferences()
                refreshDirectory()
            }
            .show()
    }

    private fun showPathDropdownMenu() {
        val popup = PopupMenu(this, headerPathContainer)
        popup.menu.add("Copy Path")
        popup.menu.add("Parent Directory")
        popup.menu.add("Jump to Internal Storage")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Copy Path" -> {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Path", currentPath))
                    Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
                }
                "Parent Directory" -> navigateUp()
                "Jump to Internal Storage" -> {
                    exitVirtualArchive()
                    navigateToDirectory(StorageManager.PRIMARY_STORAGE_PATH)
                }
            }
            true
        }
        popup.show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 4)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}

// Data models for the RecyclerView
data class RowItem(
    val name: String,
    val path: String,
    val isParentRow: Boolean,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long,
    val permissions: String,
    val extension: String,
    val category: String,
    val meta: String,
    val virtualFullPath: String? = null,
    val virtualSubDir: String? = null
) {
    companion object {
        fun parentRow(): RowItem {
            return RowItem(
                name = "..",
                path = "..",
                isParentRow = true,
                isDir = true,
                size = 0,
                modifiedMs = 0,
                permissions = "",
                extension = "",
                category = "folder",
                meta = "Parent directory"
            )
        }

        fun fromFileEntry(e: FileEntryItem): RowItem {
            val dateStr = if (e.modifiedMs > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(e.modifiedMs))
            } else ""
            val sizeStr = if (e.isDir) {
                when (val cnt = e.childCount) {
                    null -> "Folder"
                    0 -> "Empty"
                    1 -> "1 item"
                    else -> "$cnt items"
                }
            } else {
                formatSize(e.size)
            }
            val metaStr = if (dateStr.isNotEmpty()) "$sizeStr   $dateStr" else sizeStr
            return RowItem(
                name = e.name,
                path = e.path,
                isParentRow = false,
                isDir = e.isDir,
                size = e.size,
                modifiedMs = e.modifiedMs,
                permissions = "",
                extension = e.extension,
                category = e.category,
                meta = metaStr
            )
        }

        fun fromVirtualEntry(v: VirtualEntryItem, archivePath: String, innerDir: String): RowItem {
            val sizeStr = if (v.isDir) "Virtual Folder" else formatSize(v.size)
            val metaStr = "$sizeStr   ${v.method}"
            val sub = if (innerDir.isEmpty()) v.name else "$innerDir/${v.name}"
            return RowItem(
                name = v.name,
                path = "$archivePath#$sub",
                isParentRow = false,
                isDir = v.isDir,
                size = v.size,
                modifiedMs = 0,
                permissions = if (v.encrypted) "encrypted" else "",
                extension = File(v.name).extension.lowercase(),
                category = if (v.isDir) "folder" else "file",
                meta = metaStr,
                virtualFullPath = v.fullPath,
                virtualSubDir = sub
            )
        }

        fun formatSize(bytes: Long): String = com.prismfiles.app.formatSize(bytes)
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 4)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

object ThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    val lru = object : androidx.collection.LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
}

class FileListAdapter(
    private val items: List<RowItem>,
    private val isSelected: (String) -> Boolean,
    private val onIconClick: (RowItem) -> Unit,
    private val onRowClick: (RowItem) -> Unit,
    private val onRowLongClick: (RowItem) -> Unit,
    private val onRowMoreClick: (RowItem) -> Unit
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.row_container)
        val iconTouchTarget: View = view.findViewById(R.id.icon_touch_target)
        val fileIcon: ImageView = view.findViewById(R.id.file_icon)
        val selectionIndicator: View = view.findViewById(R.id.selection_indicator)
        val textTouchTarget: View = view.findViewById(R.id.text_touch_target)
        val fileName: TextView = view.findViewById(R.id.file_name)
        val fileMeta: TextView = view.findViewById(R.id.file_meta)
        val btnRowMore: ImageView = view.findViewById(R.id.btn_row_more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val selected = isSelected(item.path)

        holder.container.setBackgroundColor(
            if (selected) ContextCompat.getColor(holder.itemView.context, R.color.selected_row_bg)
            else ContextCompat.getColor(holder.itemView.context, R.color.amoled_black)
        )
        holder.selectionIndicator.visibility = if (selected) View.VISIBLE else View.GONE

        holder.fileName.text = item.name
        holder.fileMeta.text = item.meta
        holder.fileIcon.tag = item.path

        if (item.isParentRow) {
            holder.fileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.fileIcon.setImageResource(R.drawable.ic_parent_dir)
            holder.fileIcon.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.icon_folder))
            holder.btnRowMore.visibility = View.GONE
        } else if (item.isDir) {
            holder.fileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.fileIcon.setImageResource(R.drawable.ic_folder)
            holder.fileIcon.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.icon_folder))
            holder.btnRowMore.visibility = View.VISIBLE
        } else {
            val isMedia = (item.category == "image" || item.category == "video") && item.virtualFullPath == null
            val cachedBmp = if (isMedia) ThumbnailCache.lru.get(item.path) else null

            if (cachedBmp != null) {
                holder.fileIcon.clearColorFilter()
                holder.fileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.fileIcon.setImageBitmap(cachedBmp)
            } else {
                holder.fileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.fileIcon.setImageResource(R.drawable.ic_file)
                val colorRes = when (item.category) {
                    "archive" -> R.color.icon_archive
                    "apk" -> R.color.icon_apk
                    "image" -> R.color.icon_image
                    "audio" -> R.color.icon_audio
                    "video" -> R.color.icon_video
                    "document" -> R.color.icon_document
                    "code" -> R.color.icon_code
                    "specialized" -> R.color.icon_specialized
                    else -> R.color.icon_generic
                }
                holder.fileIcon.setColorFilter(ContextCompat.getColor(holder.itemView.context, colorRes))

                if (isMedia) {
                    val filePath = item.path
                    val category = item.category
                    (holder.itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                        val bmp: Bitmap? = if (category == "image") {
                            try {
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(filePath, opts)
                                val sample = maxOf(1, minOf(opts.outWidth / 96, opts.outHeight / 96))
                                val decOpts = BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                }
                                val raw = BitmapFactory.decodeFile(filePath, decOpts)
                                if (raw != null) {
                                    val exif = try { android.media.ExifInterface(filePath) } catch (_: Exception) { null }
                                    val orient = exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
                                    val rot = when (orient) {
                                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                        else -> 0f
                                    }
                                    if (rot != 0f) {
                                        val m = Matrix().apply { postRotate(rot) }
                                        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                                        if (rotated != raw) {
                                            raw.recycle()
                                        }
                                        rotated
                                    } else raw
                                } else null
                            } catch (_: Exception) { null }
                        } else {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    android.media.ThumbnailUtils.createVideoThumbnail(File(filePath), android.util.Size(96, 96), null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    android.media.ThumbnailUtils.createVideoThumbnail(filePath, android.provider.MediaStore.Images.Thumbnails.MICRO_KIND)
                                }
                            } catch (_: Exception) { null }
                        }

                        if (bmp != null) {
                            ThumbnailCache.lru.put(filePath, bmp)
                            withContext(Dispatchers.Main) {
                                if (holder.fileIcon.tag == filePath) {
                                    holder.fileIcon.clearColorFilter()
                                    holder.fileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                                    holder.fileIcon.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                }
            }
            holder.btnRowMore.visibility = View.VISIBLE
        }

        // Split-tap & long-press listeners
        holder.iconTouchTarget.setOnClickListener { onIconClick(item) }
        holder.textTouchTarget.setOnClickListener { onRowClick(item) }
        holder.container.setOnLongClickListener {
            if (!item.isParentRow) {
                onRowLongClick(item)
                true
            } else false
        }
        holder.textTouchTarget.setOnLongClickListener {
            if (!item.isParentRow) {
                onRowLongClick(item)
                true
            } else false
        }
        holder.btnRowMore.setOnClickListener { onRowMoreClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

data class DrawerEntry(
    val name: String,
    val path: String,
    val iconRes: Int,
    var subtitle: String = "",
    val isStorageVolume: Boolean = false
)

class DrawerAdapter(
    private val items: List<DrawerEntry>,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<DrawerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_drawer_icon)
        val title: TextView = view.findViewById(R.id.tv_drawer_title)
        val subtitle: TextView = view.findViewById(R.id.tv_drawer_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_drawer_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.title.text = entry.name
        holder.icon.setImageResource(entry.iconRes)
        if (entry.subtitle.isNotEmpty()) {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = entry.subtitle
        } else {
            holder.subtitle.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onItemClick(entry.path) }
    }

    override fun getItemCount(): Int = items.size
}
