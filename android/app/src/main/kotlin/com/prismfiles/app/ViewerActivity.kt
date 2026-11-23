package com.prismfiles.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_IS_ARCHIVE_ENTRY = "extra_is_archive_entry"
        const val EXTRA_ARCHIVE_PATH = "extra_archive_path"
        const val EXTRA_ENTRY_PATH = "extra_entry_path"
    }

    private var filePath: String = ""
    private var fileName: String = ""
    private var isArchiveEntry: Boolean = false
    private var archivePath: String = ""
    private var entryPath: String = ""

    private var isHexMode: Boolean = false
    private var initialText: String = ""
    private var isTextLoaded: Boolean = false

    private lateinit var tvTitle: TextView
    private lateinit var btnSave: ImageView
    private lateinit var textContainer: View
    private lateinit var etCode: EditText
    private lateinit var ivImage: ImageView
    private lateinit var videoView: VideoView
    private lateinit var rvPdf: RecyclerView
    private lateinit var hexContainer: View
    private lateinit var tvHex: TextView
    private lateinit var errorContainer: View
    private lateinit var tvError: TextView
    private lateinit var btnErrorHex: Button

    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: File(filePath).name
        isArchiveEntry = intent.getBooleanExtra(EXTRA_IS_ARCHIVE_ENTRY, false)
        archivePath = intent.getStringExtra(EXTRA_ARCHIVE_PATH) ?: ""
        entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH) ?: ""

        initViews()
        setupBackNavigation()
        loadFileContent()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (textContainer.visibility == View.VISIBLE && isTextLoaded && etCode.text.toString() != initialText) {
                    androidx.appcompat.app.AlertDialog.Builder(this@ViewerActivity)
                        .setTitle("Unsaved Changes")
                        .setMessage("You have unsaved changes in this document. Do you want to discard them?")
                        .setPositiveButton("Discard") { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .setNeutralButton("Save") { _, _ ->
                            saveCurrentText {
                                finish()
                            }
                        }
                        .setNegativeButton("Keep Editing", null)
                        .show()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.viewer_tv_title)
        btnSave = findViewById(R.id.viewer_btn_save)
        textContainer = findViewById(R.id.viewer_text_container)
        etCode = findViewById(R.id.viewer_et_code)
        ivImage = findViewById(R.id.viewer_iv_image)
        videoView = findViewById(R.id.viewer_video_view)
        rvPdf = findViewById(R.id.viewer_rv_pdf)
        hexContainer = findViewById(R.id.viewer_hex_container)
        tvHex = findViewById(R.id.viewer_tv_hex)
        errorContainer = findViewById(R.id.viewer_error_container)
        tvError = findViewById(R.id.viewer_tv_error)
        btnErrorHex = findViewById(R.id.viewer_btn_error_hex)

        tvTitle.text = fileName

        findViewById<View>(R.id.viewer_btn_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<View>(R.id.viewer_btn_mode).setOnClickListener {
            toggleHexMode()
        }

        btnSave.setOnClickListener {
            saveCurrentText()
        }

        btnErrorHex.setOnClickListener {
            showHex()
        }
    }

    private suspend fun resolveFileForViewing(): File? {
        if (isArchiveEntry && archivePath.isNotEmpty() && entryPath.isNotEmpty()) {
            val safeName = File(fileName).name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val cacheFile = File(cacheDir, "preview_$safeName")
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            val resJson = RustCore.startExtractSingleTask(archivePath, entryPath, cacheFile.absolutePath, "")
            val parsed = RustCore.parseEnvelope(resJson) { it.optLong("task_id") }
            if (parsed.isSuccess) {
                val taskId = parsed.getOrNull() ?: return null
                var done = false
                var attempts = 0
                try {
                    while (!done && attempts < 60) {
                        delay(50)
                        attempts++
                        val snapJson = RustCore.getTaskSnapshot(taskId)
                        val snap = RustCore.parseEnvelope(snapJson) { it.optString("state", "") }
                        val state = snap.getOrNull() ?: ""
                        if (state.equals("Completed", ignoreCase = true) || state.equals("Done", ignoreCase = true)) {
                            done = true
                        } else if (state.equals("Failed", ignoreCase = true) || state.equals("Error", ignoreCase = true) || state.equals("Canceled", ignoreCase = true) || state.equals("Cancelled", ignoreCase = true)) {
                            break
                        }
                    }
                } finally {
                    RustCore.dropTask(taskId)
                }
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    return cacheFile
                }
            }
        }
        val f = File(filePath)
        return if (f.exists()) f else null
    }

    private fun loadFileContent() {
        val ext = File(fileName).extension.lowercase()
        when {
            ext in listOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "ico") -> {
                showImage()
            }
            ext in listOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "mp3", "wav", "ogg", "flac", "m4a", "aac", "opus") -> {
                showVideo()
            }
            ext == "pdf" -> {
                showPdf()
            }
            else -> {
                showText()
            }
        }
    }

    private fun decodeSampledBitmap(file: File, reqWidth: Int = 2048, reqHeight: Int = 2048): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            file.inputStream().use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var bmp = file.inputStream().use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: BitmapFactory.decodeFile(file.absolutePath, decodeOptions)

            if (bmp != null) {
                try {
                    val exif = android.media.ExifInterface(file.absolutePath)
                    val orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    val matrix = android.graphics.Matrix()
                    when (orientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                    }
                    if (!matrix.isIdentity) {
                        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                        if (rotated != bmp) {
                            bmp.recycle()
                            bmp = rotated
                        }
                    }
                } catch (_: Throwable) {}
            }
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun showImage() {
        hideAllContainers()
        ivImage.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val targetFile = resolveFileForViewing()
            val bmp = if (targetFile != null && targetFile.exists()) {
                decodeSampledBitmap(targetFile)
            } else null

            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    ivImage.setImageBitmap(bmp)
                } else {
                    showError("Unable to decode image format: $fileName")
                }
            }
        }
    }

    private fun showVideo() {
        hideAllContainers()
        videoView.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val targetFile = resolveFileForViewing()
            withContext(Dispatchers.Main) {
                if (targetFile != null && targetFile.exists()) {
                    val mc = MediaController(this@ViewerActivity)
                    mc.setAnchorView(videoView)
                    videoView.setMediaController(mc)
                    videoView.setOnErrorListener { _, what, extra ->
                        showError("Cannot decode video format: $fileName (Error $what, $extra)")
                        true
                    }
                    videoView.setVideoURI(Uri.fromFile(targetFile))
                    videoView.start()
                } else {
                    showError("Unable to load video stream: $fileName")
                }
            }
        }
    }

    private fun showPdf() {
        hideAllContainers()
        rvPdf.visibility = View.VISIBLE
        rvPdf.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val targetFile = resolveFileForViewing()
            withContext(Dispatchers.Main) {
                if (targetFile != null && targetFile.exists()) {
                    try {
                        val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        if (pfd != null) {
                            pdfDescriptor = pfd
                            val renderer = PdfRenderer(pfd)
                            pdfRenderer = renderer
                            rvPdf.adapter = PdfPageAdapter(renderer)
                        } else {
                            showError("Unable to open PDF descriptor: $fileName")
                        }
                    } catch (e: Exception) {
                        try {
                            pdfDescriptor?.close()
                        } catch (_: Exception) {}
                        pdfDescriptor = null
                        showError("PDF render error: ${e.message}")
                    }
                } else {
                    showError("Unable to open PDF: $fileName")
                }
            }
        }
    }

    private fun showText() {
        hideAllContainers()
        textContainer.visibility = View.VISIBLE
        btnSave.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val targetFile = resolveFileForViewing()
            val path = targetFile?.absolutePath ?: filePath
            val resJson = RustCore.readTextBounded(path, 2 * 1024 * 1024)
            val parsed = RustCore.parseEnvelope(resJson) { obj ->
                if (obj.has("content")) obj.optString("content", "")
                else obj.optString("value", "")
            }
            withContext(Dispatchers.Main) {
                parsed.onSuccess { content ->
                    initialText = content
                    isTextLoaded = true
                    etCode.setText(content)
                }.onFailure {
                    showError("Unable to read as text document: ${it.message}")
                }
            }
        }
    }

    private fun showHex() {
        hideAllContainers()
        hexContainer.visibility = View.VISIBLE
        btnSave.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            val targetFile = resolveFileForViewing()
            val path = targetFile?.absolutePath ?: filePath
            val resJson = RustCore.readHexWindow(path, 0L, 64 * 1024)
            val parsed = RustCore.parseEnvelope(resJson) { obj ->
                val sb = StringBuilder()
                if (obj.has("lines") && !obj.isNull("lines")) {
                    val lines = obj.optJSONArray("lines")
                    if (lines != null) {
                        for (i in 0 until lines.length()) {
                            val l = lines.optJSONObject(i) ?: continue
                            sb.append(String.format("%s  %s  |%s|\n", l.optString("offset_hex"), l.optString("hex_bytes"), l.optString("ascii")))
                        }
                    }
                }
                if (sb.isEmpty() && obj.has("bytes_base64")) {
                    val b64 = obj.optString("bytes_base64", "")
                    val rawBytes = if (b64.isNotEmpty()) {
                        try {
                            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        } catch (_: Exception) {
                            ByteArray(0)
                        }
                    } else {
                        ByteArray(0)
                    }
                    val offset = obj.optLong("offset", 0L)
                    val totalSize = obj.optLong("total_size", rawBytes.size.toLong())
                    sb.append(String.format("File: %s   Size: %d bytes\n\n", File(path).name, totalSize))
                    for (i in rawBytes.indices step 16) {
                        val lineOffset = offset + i
                        val chunkEnd = minOf(i + 16, rawBytes.size)
                        val hexSb = StringBuilder()
                        val asciiSb = StringBuilder()
                        for (j in i until i + 16) {
                            if (j < chunkEnd) {
                                val b = rawBytes[j].toInt() and 0xFF
                                hexSb.append(String.format("%02X ", b))
                                if (b in 32..126) asciiSb.append(b.toChar()) else asciiSb.append('.')
                            } else {
                                hexSb.append("   ")
                            }
                        }
                        sb.append(String.format("%08X  %s |%s|\n", lineOffset, hexSb.toString(), asciiSb.toString()))
                    }
                }
                sb.toString()
            }
            withContext(Dispatchers.Main) {
                parsed.onSuccess { txt ->
                    tvHex.text = if (txt.isNotEmpty()) txt else "Empty file (0 bytes)"
                }.onFailure {
                    tvHex.text = "Error reading hex view: ${it.message}"
                }
            }
        }
    }

    private fun showError(message: String) {
        hideAllContainers()
        errorContainer.visibility = View.VISIBLE
        tvError.text = message
    }

    private fun toggleHexMode() {
        isHexMode = !isHexMode
        if (isHexMode) {
            showHex()
        } else {
            loadFileContent()
        }
    }

    private fun saveCurrentText(onSaved: (() -> Unit)? = null) {
        val newText = etCode.text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val resJson = if (isArchiveEntry) {
                RustCore.updateArchiveEntry(archivePath, entryPath, newText, "")
            } else {
                RustCore.writeTextAtomic(filePath, newText)
            }
            val parsed = RustCore.parseEnvelope(resJson) { true }
            withContext(Dispatchers.Main) {
                parsed.onSuccess {
                    initialText = newText
                    Toast.makeText(this@ViewerActivity, "Saved successfully", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                }.onFailure { err ->
                    Toast.makeText(this@ViewerActivity, "Save failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }



    private fun hideAllContainers() {
        textContainer.visibility = View.GONE
        ivImage.visibility = View.GONE
        videoView.visibility = View.GONE
        rvPdf.visibility = View.GONE
        hexContainer.visibility = View.GONE
        errorContainer.visibility = View.GONE
        btnSave.visibility = View.GONE
    }

    override fun onStop() {
        super.onStop()
        if (videoView.isPlaying) {
            videoView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            videoView.stopPlayback()
        } catch (_: Exception) {}
        try {
            pdfRenderer?.close()
            pdfDescriptor?.close()
        } catch (_: Exception) {}
    }
}

class PdfPageAdapter(private val renderer: PdfRenderer) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPage: ImageView = view as ImageView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val iv = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            setPadding(0, 8, 0, 8)
        }
        return PageViewHolder(iv)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        var page: PdfRenderer.Page? = null
        try {
            page = renderer.openPage(position)
            val maxDim = maxOf(page.width, page.height)
            val scale = if (maxDim > 2048) 2048f / maxDim else 1.0f
            val renderW = (page.width * scale).toInt().coerceAtLeast(1)
            val renderH = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            val oldDrawable = holder.ivPage.drawable as? android.graphics.drawable.BitmapDrawable
            oldDrawable?.bitmap?.let { oldBmp ->
                if (!oldBmp.isRecycled && oldBmp != bitmap) {
                    oldBmp.recycle()
                }
            }
            holder.ivPage.setImageBitmap(bitmap)
        } catch (_: Throwable) {
        } finally {
            try {
                page?.close()
            } catch (_: Throwable) {}
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        val drawable = holder.ivPage.drawable as? android.graphics.drawable.BitmapDrawable
        drawable?.bitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
        holder.ivPage.setImageDrawable(null)
    }

    override fun getItemCount(): Int = renderer.pageCount
}
