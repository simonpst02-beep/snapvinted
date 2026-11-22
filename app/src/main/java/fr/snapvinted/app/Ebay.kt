package fr.snapvinted.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Surveillance des annonces eBay « pour pièces » via l'API officielle (Browse API).
 * Les réglages sont enregistrés par l'interface (clé « ebayConfig ») ; le même code sert
 * au scan manuel et au scan en arrière-plan.
 */
object Ebay {
    private val EU = setOf(
        "FR", "BE", "DE", "ES", "IT", "NL", "LU", "PT", "AT", "IE", "FI", "SE", "DK", "PL", "CZ",
        "SK", "SI", "HU", "RO", "BG", "GR", "HR", "CY", "MT", "EE", "LV", "LT"
    )

    private fun prefs(c: Context) = c.getSharedPreferences("snapvinted", Context.MODE_PRIVATE)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val s = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return code to s.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun token(c: Context, appId: String, cert: String): String {
        val p = prefs(c)
        val cached = p.getString("ebayToken", "") ?: ""
        if (cached.isNotEmpty() && System.currentTimeMillis() < p.getLong("ebayTokenExp", 0)) return cached
        val conn = URL("https://api.ebay.com/identity/v1/oauth2/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        val basic = Base64.encodeToString("$appId:$cert".toByteArray(), Base64.NO_WRAP)
        conn.setRequestProperty("Authorization", "Basic $basic")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use {
            it.write(("grant_type=client_credentials&scope=" + enc("https://api.ebay.com/oauth/api_scope")).toByteArray())
        }
        val (code, body) = read(conn)
        if (code !in 200..299) throw Exception("eBay refuse vos clés ($code). Vérifiez l'App ID et le Cert ID (clés de production).")
        val j = JSONObject(body)
        val tok = j.getString("access_token")
        val ttl = j.optLong("expires_in", 7200)
        p.edit().putString("ebayToken", tok).putLong("ebayTokenExp", System.currentTimeMillis() + (ttl - 120) * 1000).apply()
        return tok
    }

    private fun seen(c: Context): MutableSet<String> =
        LinkedHashSet((prefs(c).getString("ebaySeen", "") ?: "").split(',').filter { it.isNotEmpty() })

    fun markSeen(c: Context, ids: List<String>) {
        val s = seen(c)
        s.addAll(ids)
        val kept = s.toList().takeLast(3000)
        prefs(c).edit().putString("ebaySeen", kept.joinToString(",")).apply()
    }

    fun scan(c: Context): JSONObject {
        val cfg = JSONObject(prefs(c).getString("ebayConfig", "{}") ?: "{}")
        val appId = cfg.optString("appId").trim()
        val cert = cfg.optString("certId").trim()
        if (appId.isBlank() || cert.isBlank()) throw Exception("Ajoutez vos clés eBay (onglet Achats → Réglages eBay).")
        val tok = token(c, appId, cert)
        val ex = cfg.optJSONArray("exclude") ?: JSONArray()
        val exclude = (0 until ex.length()).map { norm(ex.getString(it).trim()) }.filter { it.isNotBlank() }
        val minProfit = cfg.optDouble("minProfit", 0.0)
        val euOnly = cfg.optBoolean("euOnly", true)
        val minFb = cfg.optDouble("minFeedback", 0.0)
        val fees = cfg.optDouble("fees", 0.0) / 100.0
        val already = seen(c)
        val items = JSONArray()
        val rejected = JSONObject()
        fun reject(r: String) = rejected.put(r, rejected.optInt(r) + 1)

        val searches = cfg.optJSONArray("searches") ?: JSONArray()
        for (i in 0 until searches.length()) {
            val s = searches.getJSONObject(i)
            val q = s.optString("q").trim()
            if (q.isEmpty()) continue
            val max = s.optDouble("maxPrice", 0.0)
            val resale = s.optDouble("resale", 0.0)
            val parts = s.optDouble("parts", 0.0)
            var filter = "conditionIds:{7000},buyingOptions:{FIXED_PRICE|AUCTION|BEST_OFFER}"
            if (max > 0) filter += ",price:[..${max.toInt()}],priceCurrency:EUR"
            val url = "https://api.ebay.com/buy/browse/v1/item_summary/search?q=${enc(q)}&filter=${enc(filter)}&sort=newlyListed&limit=50"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $tok")
            conn.setRequestProperty("X-EBAY-C-MARKETPLACE-ID", cfg.optString("market", "EBAY_FR"))
            conn.setRequestProperty("Accept-Language", "fr-FR")
            val (code, body) = read(conn)
            if (code == 401) {
                prefs(c).edit().remove("ebayToken").apply()
                throw Exception("Accès eBay expiré : relancez le scan.")
            }
            if (code !in 200..299) throw Exception("eBay a répondu $code : " + body.take(200))
            val arr = JSONObject(body).optJSONArray("itemSummaries") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val x = arr.getJSONObject(k)
                val title = x.optString("title")
                val nt = norm(title)
                val bad = exclude.firstOrNull { w -> nt.contains(w) }
                if (bad != null) {
                    reject("« $bad »")
                    continue
                }
                val priceObj = x.optJSONObject("price") ?: x.optJSONObject("currentBidPrice")
                val price = priceObj?.optString("value")?.toDoubleOrNull() ?: continue
                val ship = x.optJSONArray("shippingOptions")?.optJSONObject(0)
                    ?.optJSONObject("shippingCost")?.optString("value")?.toDoubleOrNull() ?: 0.0
                val country = x.optJSONObject("itemLocation")?.optString("country") ?: ""
                if (euOnly && country.isNotEmpty() && country !in EU) {
                    reject("hors Union européenne")
                    continue
                }
                val fb = x.optJSONObject("seller")?.optString("feedbackPercentage")?.toDoubleOrNull() ?: 100.0
                if (fb < minFb) {
                    reject("vendeur mal noté")
                    continue
                }
                val total = price + ship
                val profit = if (resale > 0) resale - total - parts - resale * fees else null
                if (profit != null && profit < minProfit) {
                    reject("bénéfice trop faible")
                    continue
                }
                val id = x.optString("itemId")
                val auction = (x.optJSONArray("buyingOptions")?.toString() ?: "").contains("AUCTION")
                items.put(
                    JSONObject().put("id", id).put("search", s.optString("name", q)).put("title", title)
                        .put("price", price).put("ship", ship).put("total", total)
                        .put("profit", profit ?: JSONObject.NULL)
                        .put("img", x.optJSONObject("image")?.optString("imageUrl") ?: "")
                        .put("url", x.optString("itemWebUrl")).put("country", country).put("feedback", fb)
                        .put("auction", auction).put("end", x.optString("itemEndDate"))
                        .put("isNew", id !in already)
                )
            }
        }
        return JSONObject().put("items", items).put("rejected", rejected).put("at", System.currentTimeMillis())
    }

    fun schedule(c: Context, minutes: Int) {
        val wm = WorkManager.getInstance(c)
        if (minutes <= 0) {
            wm.cancelUniqueWork("ebay-scan")
            return
        }
        val req = PeriodicWorkRequestBuilder<ScanWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("ebay-scan", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

/** Scan en arrière-plan : notifie les nouvelles annonces qui passent tous les filtres. */
class ScanWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            val r = Ebay.scan(applicationContext)
            val items = r.getJSONArray("items")
            val fresh = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optBoolean("isNew") }
            if (fresh.isNotEmpty()) {
                notify(fresh)
                Ebay.markSeen(applicationContext, fresh.map { it.getString("id") })
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notify(fresh: List<JSONObject>) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel("ebay", "Annonces eBay", NotificationManager.IMPORTANCE_DEFAULT))
        val best = fresh.maxByOrNull { if (it.isNull("profit")) -1e9 else it.getDouble("profit") } ?: fresh[0]
        val open = Intent(applicationContext, MainActivity::class.java)
            .putExtra("tab", "buy")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(applicationContext, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val profit = if (best.isNull("profit")) "" else " · bénéfice ≈ ${best.getDouble("profit").toInt()} €"
        val title = if (fresh.size == 1) "Nouvelle annonce eBay" else "${fresh.size} nouvelles annonces eBay"
        val n = Notification.Builder(applicationContext, "ebay")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText("${best.optString("title")} — ${best.getDouble("total").toInt()} €$profit")
            .setStyle(Notification.BigTextStyle().bigText(
                fresh.take(5).joinToString("\n") { "• ${it.optString("title").take(60)} — ${it.getDouble("total").toInt()} €" }))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, n)
    }
}
