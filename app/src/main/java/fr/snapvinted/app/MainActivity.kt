package fr.snapvinted.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
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
 * SpyEbay : l'interface est une page web embarquée (assets/index.html).
 * Cette activité lui fournit la surveillance eBay (Ebay.kt), l'ouverture des annonces,
 * les notifications et la mise à jour depuis GitHub.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var pendingTab: String? = null
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        pendingTab = intent?.getStringExtra("tab")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                openPendingTab()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                    return true
                }
                return false
            }
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingTab = intent.getStringExtra("tab")
        if (pageReady) openPendingTab()
    }

    private fun openPendingTab() {
        val t = pendingTab ?: return
        pendingTab = null
        web.evaluateJavascript("window.openTab&&window.openTab(${JSONObject.quote(t)})", null)
    }

    @Deprecated("API Activity classique")
    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack&&window.onBack()") { r ->
            if (r != "true") finish()
        }
    }

    private fun toastNow(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }

    private fun http(url: String, headers: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        return code to stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun newer(a: String, b: String): Boolean {
        fun p(v: String) = v.trimStart('v').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        val x = p(a)
        val y = p(b)
        for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
        return false
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
        fun version(): String = versionName()

        @JavascriptInterface
        fun toast(m: String) {
            toastNow(m)
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            if (url.startsWith("https://")) main.post { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        /* ---------- Surveillance eBay ---------- */
        @JavascriptInterface
        fun ebayGetConfig(): String = getPref("ebayConfig").ifBlank { "{}" }

        @JavascriptInterface
        fun ebaySaveConfig(json: String) {
            val old = JSONObject(ebayGetConfig())
            val neu = JSONObject(json)
            if (old.optString("appId") != neu.optString("appId") || old.optString("certId") != neu.optString("certId")) {
                prefs.edit().remove("ebayToken").apply()
            }
            setPref("ebayConfig", json)
        }

        @JavascriptInterface
        fun ebayScan(id: String) {
            io.execute {
                val (ok, out) = try {
                    true to Ebay.scan(this@MainActivity).toString()
                } catch (e: Exception) {
                    false to JSONObject().put("error", e.message ?: e.toString()).toString()
                }
                js("window.__ebayDone(${JSONObject.quote(id)},$ok,${JSONObject.quote(out)})")
            }
        }

        @JavascriptInterface
        fun ebayMarkSeen(idsJson: String) {
            val a = JSONArray(idsJson)
            Ebay.markSeen(this@MainActivity, (0 until a.length()).map { a.getString(it) })
        }

        /** Active (minutes > 0) ou coupe (0) la surveillance en arrière-plan. */
        @JavascriptInterface
        fun ebaySchedule(minutes: Int) {
            if (minutes > 0 && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                main.post { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }
            }
            Ebay.schedule(this@MainActivity, minutes)
        }

        /* ---------- Mise à jour depuis GitHub ---------- */
        @JavascriptInterface
        fun checkUpdate(id: String) {
            fun done(ok: Boolean, j: JSONObject) = js("window.__updDone(${JSONObject.quote(id)},$ok,${JSONObject.quote(j.toString())})")
            if (BuildConfig.GH_TOKEN.isBlank()) {
                main.post { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GH_REPO}/releases/latest"))) }
                done(true, JSONObject().put("mode", "browser"))
                return
            }
            io.execute {
                try {
                    val (code, txt) = http(
                        "https://api.github.com/repos/${BuildConfig.GH_REPO}/releases/latest",
                        mapOf("Accept" to "application/vnd.github+json", "Authorization" to "Bearer ${BuildConfig.GH_TOKEN}", "User-Agent" to "SpyEbay")
                    )
                    if (code !in 200..299) {
                        done(false, JSONObject().put("error", "GitHub a répondu $code"))
                        return@execute
                    }
                    val rel = JSONObject(txt)
                    val tag = rel.optString("tag_name")
                    var url = ""
                    val assets = rel.optJSONArray("assets") ?: JSONArray()
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) url = a.optString("url")
                    }
                    done(true, JSONObject().put("mode", "app").put("latest", tag).put("url", url)
                        .put("notes", rel.optString("body")).put("update", url.isNotEmpty() && newer(tag, versionName())))
                } catch (e: Exception) {
                    done(false, JSONObject().put("error", "Connexion impossible : " + (e.message ?: e.toString())))
                }
            }
        }

        @JavascriptInterface
        fun installUpdate(assetUrl: String, id: String) {
            fun done(ok: Boolean, j: JSONObject) = js("window.__updDone(${JSONObject.quote(id)},$ok,${JSONObject.quote(j.toString())})")
            if (!packageManager.canRequestPackageInstalls()) {
                main.post { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                done(true, JSONObject().put("status", "permission"))
                return
            }
            io.execute {
                try {
                    var conn = URL(assetUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "application/octet-stream")
                    conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.GH_TOKEN}")
                    conn.setRequestProperty("User-Agent", "SpyEbay")
                    conn.instanceFollowRedirects = false
                    var code = conn.responseCode
                    if (code in 300..399) { // lien de téléchargement signé, sans jeton
                        conn = URL(conn.getHeaderField("Location")).openConnection() as HttpURLConnection
                        code = conn.responseCode
                    }
                    if (code !in 200..299) {
                        done(false, JSONObject().put("error", "téléchargement refusé ($code)"))
                        return@execute
                    }
                    val f = File(cacheDir, "update/SpyEbay.apk")
                    f.parentFile?.mkdirs()
                    conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    main.post { startActivity(intent) }
                    done(true, JSONObject().put("status", "installing"))
                } catch (e: Exception) {
                    done(false, JSONObject().put("error", e.message ?: e.toString()))
                }
            }
        }
    }
}
