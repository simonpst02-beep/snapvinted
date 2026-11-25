package fr.margeo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alertes en arrière-plan : refait les recherches enregistrées toutes les heures
 * et envoie une notification quand une affaire dépasse la marge voulue.
 */
public class Alertes {

    static final String API = "https://api.ebay.com";
    static final String PREFS = "margeo";
    static final int JOB_ID = 42;
    static final String CANAL = "alertes";

    // ---------- Planification ----------

    static void planifier(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        boolean actif = false;
        try {
            JSONObject cfg = new JSONObject(ctx.getSharedPreferences(PREFS, 0).getString("cfg", "{}"));
            JSONArray al = cfg.optJSONArray("alerts");
            actif = cfg.optBoolean("alertsOn", true) && al != null && al.length() > 0
                    && !cfg.optString("id").isEmpty()
                    && !(Secrets.get(ctx, "ebay_sec").isEmpty() && cfg.optString("sec").isEmpty());
        } catch (Exception ignored) { }
        if (!actif) { js.cancel(JOB_ID); return; }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, AlertJob.class))
                .setPeriodic(60L * 60L * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    // ---------- Exécution ----------

    /** Lance toutes les alertes. Renvoie un résumé JSON {ok, trouves, erreur, quand}. */
    static synchronized String lancer(Context ctx) {
        appCtx = ctx.getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
        JSONObject res = new JSONObject();
        int trouves = 0;
        String erreur = "";
        try {
            JSONObject cfg = new JSONObject(sp.getString("cfg", "{}"));
            String id = cfg.optString("id"), mk = cfg.optString("mk", "EBAY_FR");
            String sec = Secrets.get(ctx, "ebay_sec");
            if (sec.isEmpty()) sec = cfg.optString("sec");
            double rep = cfg.optDouble("rep", 20), fr = cfg.optDouble("fr", 13), fx = cfg.optDouble("fx", 0.35);
            JSONArray alertes = cfg.optJSONArray("alerts");
            if (id.isEmpty() || sec.isEmpty() || alertes == null) throw new Exception("Clés eBay ou alertes manquantes");
            if (Journal.utilise(ctx) > 4000) throw new Exception("Quota eBay presque atteint aujourd'hui (" + Journal.utilise(ctx) + "/5000) : alertes en pause jusqu'à demain");

            JSONObject vus = new JSONObject(sp.getString("vus", "{}"));
            String tok = token(id, sec);
            Map<String, double[]> cache = new HashMap<>();

            for (int a = 0; a < alertes.length(); a++) {
                JSONObject al = alertes.getJSONObject(a);
                double mmin = al.optDouble("mmin", 30);
                JSONArray items = chercher(tok, mk, al.optString("q"), null, al.optString("cat"),
                        "7000", 20, al.optString("pmax"), al.optBoolean("fr", false), "FIXED_PRICE");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    String itemId = it.optString("itemId");
                    if (vus.has(itemId)) continue;
                    String epid = it.optString("epid", "");
                    String cle = epid.isEmpty() ? propre(it.optString("title")) : epid;
                    double[] rv = cache.get(cle);
                    if (rv == null) {
                        List<Double> px = new ArrayList<>();
                        JSONArray ok = chercher(tok, mk, epid.isEmpty() ? cle : null, epid.isEmpty() ? null : epid,
                                null, "3000", 30, null, false, null);
                        for (int j = 0; j < ok.length(); j++) {
                            JSONObject o = ok.getJSONObject(j);
                            if (accessoire(o.optString("title"))) continue;
                            double p = prix(o);
                            if (p > 0) px.add(p);
                        }
                        rv = robuste(px);
                        cache.put(cle, rv);
                    }
                    if (rv[1] < 3) continue;
                    double revente = rv[0];
                    double achat = prix(it) + port(it);
                    double marge = revente - achat - revente * rep / 100 - (revente * fr / 100 + fx);
                    vus.put(itemId, System.currentTimeMillis());
                    if (marge >= mmin) {
                        notifier(ctx, it, marge, achat, revente);
                        trouves++;
                    }
                }
            }
            // Oublie les annonces vues il y a plus de 14 jours
            long limite = System.currentTimeMillis() - 14L * 24 * 3600 * 1000;
            JSONObject garde = new JSONObject();
            JSONArray noms = vus.names();
            if (noms != null) for (int i = 0; i < noms.length(); i++) {
                String k = noms.getString(i);
                if (vus.optLong(k) > limite) garde.put(k, vus.optLong(k));
            }
            sp.edit().putString("vus", garde.toString()).apply();
        } catch (Exception e) {
            erreur = String.valueOf(e.getMessage());
            Journal.noter(ctx, "Alertes : " + erreur);
        }
        try {
            res.put("ok", erreur.isEmpty());
            res.put("trouves", trouves);
            res.put("erreur", erreur);
            res.put("quand", System.currentTimeMillis());
        } catch (Exception ignored) { }
        sp.edit().putString("dernier", res.toString()).apply();
        return res.toString();
    }

    // ---------- Notifications ----------

    static void notifier(Context ctx, JSONObject it, double marge, double achat, double revente) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Alertes de bonnes affaires",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        String url = it.optString("itemWebUrl", "https://www.ebay.fr");
        Intent voir = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        PendingIntent pi = PendingIntent.getActivity(ctx, it.optString("itemId").hashCode(), voir,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String titre = String.format(Locale.FRANCE, "+%.0f € de marge possible", marge);
        String texte = String.format(Locale.FRANCE, "%s — achat %.0f €, revente occasion %.0f €",
                it.optString("title"), achat, revente);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CANAL) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(new Notification.BigTextStyle().bigText(texte))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            nm.notify(it.optString("itemId").hashCode(), b.build());
        } catch (SecurityException ignored) { } // notifications refusées
    }

    // ---------- eBay ----------

    static Context appCtx;

    static String token(String id, String sec) throws Exception {
        String auth = Base64.encodeToString((id + ":" + sec).getBytes("UTF-8"), Base64.NO_WRAP);
        String r = http("POST", API + "/identity/v1/oauth2/token",
                new String[][]{{"Authorization", "Basic " + auth}, {"Content-Type", "application/x-www-form-urlencoded"}},
                "grant_type=client_credentials&scope=" + URLEncoder.encode("https://api.ebay.com/oauth/api_scope", "UTF-8"));
        return new JSONObject(r).getString("access_token");
    }

    static JSONArray chercher(String tok, String mk, String q, String epid, String cat, String cond, int limit,
                              String pmax, boolean fr, String achat) throws Exception {
        StringBuilder f = new StringBuilder("conditionIds:{" + cond + "},priceCurrency:EUR");
        if (pmax != null && !pmax.isEmpty()) f.append(",price:[..").append(pmax).append("]");
        if (achat != null) f.append(",buyingOptions:{").append(achat).append("}");
        if (fr) f.append(",itemLocationCountry:FR");
        StringBuilder u = new StringBuilder(API + "/buy/browse/v1/item_summary/search?limit=" + limit);
        u.append("&filter=").append(URLEncoder.encode(f.toString(), "UTF-8"));
        if (q != null && !q.isEmpty()) u.append("&q=").append(URLEncoder.encode(q, "UTF-8"));
        if (epid != null && !epid.isEmpty()) u.append("&epid=").append(URLEncoder.encode(epid, "UTF-8"));
        if (cat != null && !cat.isEmpty()) u.append("&category_ids=").append(URLEncoder.encode(cat, "UTF-8"));
        String r = http("GET", u.toString(), new String[][]{
                {"Authorization", "Bearer " + tok}, {"X-EBAY-C-MARKETPLACE-ID", mk}}, null);
        JSONArray a = new JSONObject(r).optJSONArray("itemSummaries");
        return a == null ? new JSONArray() : a;
    }

    static double prix(JSONObject i) {
        JSONObject p = i.optJSONObject("price");
        if (p == null) p = i.optJSONObject("currentBidPrice");
        return p == null ? 0 : p.optDouble("value", 0);
    }

    static double port(JSONObject i) {
        JSONArray o = i.optJSONArray("shippingOptions");
        if (o == null || o.length() == 0) return 0;
        JSONObject o0 = o.optJSONObject(0);
        JSONObject c = o0 == null ? null : o0.optJSONObject("shippingCost");
        return c == null ? 0 : c.optDouble("value", 0);
    }

    static final String MOTS = "\\b(hs|panne|pour pi[eè]ces?|d[ée]fectueu[sx]e?|cass[ée]e?s?|ne fonctionne pas|bloqu[ée]e?s?|for parts|broken)\\b";
    static final String ACCESSOIRES = "(coque|housse|[ée]tui|bo[iî]te vide|boite seule|chargeur|c[aâ]ble|verre tremp[ée]|protection|film|support|pi[eè]ces? d[ée]tach[ée]es?|carte m[eè]re|[ée]cran seul|nappe|batterie seule)";

    static String propre(String t) {
        String s = t.toLowerCase(Locale.FRANCE).replaceAll(MOTS, " ").replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim();
        String[] mots = s.split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(6, mots.length); i++) b.append(i > 0 ? " " : "").append(mots[i]);
        return b.toString();
    }

    static boolean accessoire(String t) {
        String s = t.toLowerCase(Locale.FRANCE);
        return s.matches(".*" + ACCESSOIRES + ".*") || s.matches(".*" + MOTS + ".*");
    }

    /** Médiane après avoir écarté les prix aberrants. Renvoie {médiane, nombre}. */
    static double[] robuste(List<Double> px) {
        if (px.isEmpty()) return new double[]{0, 0};
        Collections.sort(px);
        double m = mediane(px);
        List<Double> garde = new ArrayList<>();
        for (double p : px) if (p >= m * 0.5 && p <= m * 2) garde.add(p);
        if (garde.isEmpty()) return new double[]{0, 0};
        return new double[]{mediane(garde), garde.size()};
    }

    static double mediane(List<Double> s) {
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static String http(String method, String url, String[][] h, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        for (String[] kv : h) c.setRequestProperty(kv[0], kv[1]);
        if (body != null) {
            c.setDoOutput(true);
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
        }
        int code = c.getResponseCode();
        if (appCtx != null && url.startsWith(API)) Journal.compter(appCtx, 1);
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
        }
        if (code >= 400) throw new Exception("eBay " + code);
        return sb.toString();
    }
}
