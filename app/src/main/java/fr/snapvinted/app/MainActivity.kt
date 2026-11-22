package fr.snapvinted.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * SnapVinted : l'interface est une page web embarquée (assets/index.html).
 * Cette activité fournit ce que la page ne peut pas faire seule :
 * appareil photo et galerie, appel à l'API Claude, presse-papiers,
 * enregistrement des photos dans la galerie et ouverture de Vinted.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent: Intent
                if (params?.isCaptureEnabled == true) {
                    val f = File(cacheDir, "camera/photo_${System.currentTimeMillis()}.jpg")
                    f.parentFile?.mkdirs()
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    cameraUri = uri
                    intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    cameraUri = null
                    intent = Intent(Intent.ACTION_GET_CONTENT)
                        .setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(intent, REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    toastNow("Impossible d'ouvrir l'appareil photo ou la galerie")
                    false
                }
            }
        }

        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("API Activity classique")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val cb = fileCallback ?: return
        fileCallback = null
        if (resultCode != RESULT_OK) {
            cb.onReceiveValue(null)
            return
        }
        val uris = ArrayList<Uri>()
        val cam = cameraUri
        if (cam != null) {
            uris.add(cam)
        } else {
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        }
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    @Deprecated("API Activity classique")
    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack&&window.onBack()") { r ->
            if (r != "true") finish()
        }
    }

    private fun toastNow(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }

    private fun decodePhotos(json: String): List<ByteArray> {
        val arr = JSONArray(json)
        val out = ArrayList<ByteArray>()
        for (i in 0 until arr.length()) {
            val s = arr.getString(i)
            out.add(Base64.decode(s.substringAfter(","), Base64.DEFAULT))
        }
        return out
    }

    /** Enregistre les photos dans la galerie (Images/SnapVinted) et renvoie leurs adresses. */
    private fun saveToGallery(photos: List<ByteArray>): ArrayList<Uri> {
        val uris = ArrayList<Uri>()
        val stamp = System.currentTimeMillis()
        photos.forEachIndexed { i, bytes ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SnapVinted_${stamp}_${i + 1}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapVinted")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                uris.add(uri)
            }
        }
        return uris
    }

    private fun copy(text: String) {
        main.post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Annonce", text))
        }
    }

    inner class Bridge {
        private val prefs get() = getSharedPreferences("snapvinted", Context.MODE_PRIVATE)

        @JavascriptInterface
        fun getPref(k: String): String = prefs.getString(k, "") ?: ""

        @JavascriptInterface
        fun setPref(k: String, v: String) {
            prefs.edit().putString(k, v).apply()
        }

        @JavascriptInterface
        fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        @JavascriptInterface
        fun toast(m: String) {
            toastNow(m)
        }

        @JavascriptInterface
        fun copyText(t: String) {
            copy(t)
        }

        /** Appelle l'API Claude hors de la page (pas de problème de CORS, la clé ne quitte pas le téléphone). */
        @JavascriptInterface
        fun callClaude(body: String, id: String) {
            io.execute {
                var ok = false
                var out: String
                try {
                    val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 20000
                    c.readTimeout = 120000
                    c.doOutput = true
                    c.setRequestProperty("content-type", "application/json")
                    c.setRequestProperty("x-api-key", getPref("apiKey"))
                    c.setRequestProperty("anthropic-version", "2023-06-01")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
                    out = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    ok = code in 200..299
                } catch (e: Exception) {
                    out = JSONObject().put("error", JSONObject().put("message", "Connexion impossible : " + (e.message ?: e.toString()))).toString()
                }
                js("window.__claudeDone(${JSONObject.quote(id)},$ok,${JSONObject.quote(out)})")
            }
        }

        /**
         * Publication assistée : photos dans la galerie, description copiée, puis Vinted.
         * Renvoie "shared" (photos passées à Vinted), "opened" (Vinted ouvert) ou "none" (pas de Vinted).
         */
        @JavascriptInterface
        fun publish(photosJson: String, text: String): String {
            val uris = try {
                saveToGallery(decodePhotos(photosJson))
            } catch (e: Exception) {
                ArrayList()
            }
            copy(text)
            val send = Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/jpeg")
                .setPackage(VINTED)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uris.isNotEmpty() && send.resolveActivity(packageManager) != null) {
                main.post { startActivity(send) }
                return "shared"
            }
            val launch = packageManager.getLaunchIntentForPackage(VINTED)
            if (launch != null) {
                main.post { startActivity(launch) }
                return "opened"
            }
            return "none"
        }

        /** Partage générique (WhatsApp, e-mail, autre appli de vente…). */
        @JavascriptInterface
        fun share(photosJson: String, text: String) {
            try {
                val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                val uris = ArrayList<Uri>()
                decodePhotos(photosJson).forEachIndexed { i, bytes ->
                    val f = File(dir, "photo_${i + 1}.jpg")
                    f.writeBytes(bytes)
                    uris.add(FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f))
                }
                val send = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("image/jpeg")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                main.post { startActivity(Intent.createChooser(send, "Partager l'annonce")) }
            } catch (e: Exception) {
                toastNow("Partage impossible")
            }
        }
    }

    companion object {
        private const val REQ_FILE = 42
        private const val VINTED = "fr.vinted"
    }
}
