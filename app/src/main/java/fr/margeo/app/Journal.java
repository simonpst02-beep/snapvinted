package fr.margeo.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Journal d'erreurs côté Android et compteur de requêtes eBay du jour. */
public class Journal {

    static final int QUOTA = 5000;

    static synchronized void noter(Context ctx, String msg) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(Alertes.PREFS, 0);
            JSONArray a = new JSONArray(sp.getString("journal", "[]"));
            JSONObject e = new JSONObject();
            e.put("t", System.currentTimeMillis());
            e.put("m", "[Android] " + msg);
            a.put(e);
            JSONArray garde = new JSONArray();
            for (int i = Math.max(0, a.length() - 100); i < a.length(); i++) garde.put(a.get(i));
            sp.edit().putString("journal", garde.toString()).commit();
        } catch (Exception ignored) { }
    }

    static String lire(Context ctx) {
        return ctx.getSharedPreferences(Alertes.PREFS, 0).getString("journal", "[]");
    }

    static void effacer(Context ctx) {
        ctx.getSharedPreferences(Alertes.PREFS, 0).edit().remove("journal").apply();
    }

    // ---------- Quota eBay (compté par jour) ----------

    private static String aujourdhui() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date());
    }

    static synchronized int compter(Context ctx, int n) {
        SharedPreferences sp = ctx.getSharedPreferences(Alertes.PREFS, 0);
        String j = aujourdhui();
        int v = j.equals(sp.getString("q_jour", "")) ? sp.getInt("q_n", 0) : 0;
        v += n;
        sp.edit().putString("q_jour", j).putInt("q_n", v).apply();
        return v;
    }

    static int utilise(Context ctx) {
        return compter(ctx, 0);
    }

    /** Installe un enregistreur de plantages : le message est gardé pour le prochain lancement. */
    static void surveiller(final Context ctx) {
        final Thread.UncaughtExceptionHandler avant = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            StringBuilder sb = new StringBuilder("Plantage : ").append(ex);
            StackTraceElement[] st = ex.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) sb.append("\n  at ").append(st[i]);
            noter(ctx, sb.toString());
            if (avant != null) avant.uncaughtException(th, ex);
        });
    }
}
