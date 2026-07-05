package com.simplerender.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.caverock.androidsvg.SVG
import com.simplerender.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                renderUri(uri)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear) {
                clearRender()
                true
            } else false
        }

        binding.btnPickFile.setOnClickListener { openFilePicker() }
        binding.btnInputSvg.setOnClickListener { toggleSvgInput() }
        binding.btnRenderSvg.setOnClickListener { renderSvgCode() }

        initWebView(binding.webView)

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null) {
            renderUri(uri)
            return
        }
        val clipData = intent?.clipData
        if (clipData != null && clipData.itemCount > 0) {
            renderUri(clipData.getItemAt(0).uri)
        }
    }

    private fun initWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        WebView.setWebContentsDebuggingEnabled(false)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "image/*",
                    "text/html",
                    "text/plain",
                    "text/xml",
                    "application/xml",
                    "application/json",
                    "application/pdf",
                    "application/octet-stream"
                )
            )
        }
        pickFileLauncher.launch(intent)
    }

    private fun toggleSvgInput() {
        val visible = binding.svgInputLayout.visibility == View.GONE
        binding.svgInputLayout.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnRenderSvg.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.etSvgCode.requestFocus()
        } else {
            binding.etSvgCode.text?.clear()
        }
    }

    private fun renderSvgCode() {
        val code = binding.etSvgCode.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            showError("SVG 代码为空")
            return
        }
        lifecycleScope.launch {
            runCatching {
                val svg = withContext(Dispatchers.IO) { SVG.getFromString(code) }
                val bitmap = Bitmap.createBitmap(
                    binding.renderCard.width.coerceAtLeast(1),
                    binding.renderCard.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                svg.renderToCanvas(canvas)
                bitmap
            }.onSuccess { bitmap ->
                showImage(bitmap)
            }.onFailure { e ->
                showError(getString(R.string.error_render, e.message ?: e.toString()))
            }
        }
    }

    private fun renderUri(uri: Uri) {
        clearRender()
        val mime = contentResolver.getType(uri) ?: ""
        val ext = getExtensionFromUri(uri)
        lifecycleScope.launch {
            runCatching {
                when {
                    mime.startsWith("image/svg") || ext == "svg" -> renderSvg(uri)
                    mime.startsWith("image/") -> renderImage(uri)
                    mime == "text/html" || ext == "html" || ext == "htm" -> renderHtml(uri)
                    mime == "application/pdf" || ext == "pdf" -> renderPdf(uri)
                    mime.startsWith("text/") ||
                            mime == "application/json" ||
                            mime == "application/xml" ||
                            ext in CODE_EXTENSIONS -> renderText(uri)
                    else -> {
                        val fallback = readText(uri)
                        if (fallback != null) {
                            showText(fallback)
                        } else {
                            showError(getString(R.string.unknown_type))
                        }
                    }
                }
            }.onFailure { e ->
                showError(getString(R.string.error_render, e.message ?: e.toString()))
            }
        }
    }

    private suspend fun renderSvg(uri: Uri) = withContext(Dispatchers.IO) {
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法打开文件")
        input.use { stream ->
            val svg = SVG.getFromInputStream(stream)
            val width = binding.renderCard.width.coerceAtLeast(1)
            val height = binding.renderCard.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas)
            bitmap
        }
    }.let { bitmap -> showImage(bitmap) }

    private suspend fun renderImage(uri: Uri) = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException("无法打开图片")
    }.let { bitmap -> showImage(bitmap) }

    private suspend fun renderHtml(uri: Uri) {
        val html = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("无法打开 HTML")
        }
        showHtml(html, uri)
    }

    private suspend fun renderPdf(uri: Uri) = withContext(Dispatchers.IO) {
        val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
        pfd ?: throw IllegalStateException("无法打开 PDF")
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) throw IllegalStateException("PDF 为空")
                val page = renderer.openPage(0)
                val width = page.width
                val height = page.height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap
            }
        }
    }.let { bitmap -> showImage(bitmap) }

    private suspend fun renderText(uri: Uri) {
        val text = readText(uri) ?: throw IllegalStateException("无法读取文本")
        showText(text)
    }

    private suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
        }.getOrNull()
    }

    private fun getExtensionFromUri(uri: Uri): String {
        val name = uri.lastPathSegment ?: return ""
        return name.substringAfterLast('.', "").lowercase()
    }

    private fun showImage(bitmap: Bitmap) {
        hideAllRenderers()
        binding.imageView.setImageBitmap(bitmap)
        binding.imageView.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showHtml(html: String, baseUri: Uri? = null) {
        hideAllRenderers()
        binding.webView.visibility = View.VISIBLE
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.loadDataWithBaseURL(
            baseUri?.toString(),
            wrapHtml(html),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun showText(text: String) {
        hideAllRenderers()
        binding.tvText.text = text
        binding.scrollText.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        hideAllRenderers()
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun hideAllRenderers() {
        binding.imageView.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.scrollText.visibility = View.GONE
        binding.tvError.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE
    }

    private fun clearRender() {
        hideAllRenderers()
        binding.tvEmpty.visibility = View.VISIBLE
        binding.imageView.setImageBitmap(null)
        binding.webView.loadUrl("about:blank")
        binding.etSvgCode.text?.clear()
    }

    private fun wrapHtml(body: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    html, body { margin: 0; padding: 16px; font-family: sans-serif; }
                    img, svg, video { max-width: 100%; height: auto; }
                    pre { white-space: pre-wrap; word-break: break-word; }
                </style>
            </head>
            <body>
                $body
            </body>
            </html>
        """.trimIndent()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            AlertDialog.Builder(this)
                .setTitle("退出")
                .setMessage("确定要退出应用吗？")
                .setPositiveButton("退出") { _, _ -> finish() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    companion object {
        private val CODE_EXTENSIONS = setOf(
            "svg", "html", "htm", "xml", "json", "txt", "md", "css", "js",
            "kt", "java", "py", "c", "cpp", "h", "hpp", "rs", "go", "swift",
            "yaml", "yml", "toml", "ini", "conf", "log"
        )
    }
}
