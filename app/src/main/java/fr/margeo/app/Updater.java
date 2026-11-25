package fr.margeo.app;

import android.app.DownloadManager;
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
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mises à jour automatiques : vérifie GitHub, télécharge le nouvel APK
 * et propose de l'installer (Android demande toujours une confirmation).
 */
public class Updater {

    static final String REPO = "simonpst02-beep/snapvinted";
    static final int JOB_ID = 43;
    static final String CANAL = "mises_a_jour";

    // ---------- Planification (toutes les 6 heures) ----------

    static void planifier(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!auto(ctx)) { js.cancel(JOB_ID); return; }
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, AlertJob.class))
                .setPeriodic(6L * 3600L * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build());
    }

    static boolean auto(Context ctx) {
        try {
            return new JSONObject(ctx.getSharedPreferences(Alertes.PREFS, 0).getString("cfg", "{}"))
                    .optBoolean("autoUpd", true);
        } catch (Exception e) {
            return true;
        }
    }

    static int versionInstallee(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- Vérification en arrière-plan ----------

    /** Appelé par la tâche de fond : télécharge la nouvelle version et envoie une notification. */
    static void verifierEnFond(Context ctx) {
        try {
            String r = Alertes.http("GET", "https://api.github.com/repos/" + REPO + "/releases/latest",
                    new String[][]{{"Accept", "application/vnd.github+json"}, {"User-Agent", "SpyEbay-app"}}, null);
            JSONObject rel = new JSONObject(r);
            int n = Integer.parseInt("0" + rel.optString("tag_name").replaceAll("\\D", ""));
            if (n <= versionInstallee(ctx)) return;
            String url = null;
            JSONArray as = rel.optJSONArray("assets");
            if (as != null) for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.getJSONObject(i);
                if (a.optString("name").toLowerCase().endsWith(".apk")) url = a.optString("browser_download_url");
            }
            if (url == null) return;
            long id = telecharger(ctx, url, n);
            // Attend la fin du téléchargement (10 minutes au plus)
            for (int i = 0; i < 600; i++) {
                int[] e = etat(ctx, id);
                if (e[0] == DownloadManager.STATUS_SUCCESSFUL) { notifierPret(ctx, n); return; }
                if (e[0] == DownloadManager.STATUS_FAILED || e[0] == -1) return;
                Thread.sleep(1000);
            }
        } catch (Exception ignored) { }
    }

    // ---------- Téléchargement ----------

    /** Lance (ou réutilise) le téléchargement de la version n. Renvoie l'identifiant du téléchargement. */
    static synchronized long telecharger(Context ctx, String url, int n) {
        SharedPreferences sp = ctx.getSharedPreferences(Alertes.PREFS, 0);
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        long ancien = sp.getLong("upd_id", -1);
        if (sp.getInt("upd_n", 0) == n && ancien != -1) {
            int s = etat(ctx, ancien)[0];
            if (s == DownloadManager.STATUS_SUCCESSFUL || s == DownloadManager.STATUS_RUNNING
                    || s == DownloadManager.STATUS_PENDING || s == DownloadManager.STATUS_PAUSED) return ancien;
        }
        if (ancien != -1) dm.remove(ancien);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                .setTitle("SpyEbay – version " + n)
                .setDescription("Téléchargement de la mise à jour")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "SpyEbay-" + n + ".apk");
        long id = dm.enqueue(req);
        sp.edit().putLong("upd_id", id).putInt("upd_n", n).apply();
        return id;
    }

    /** {statut, pourcentage} du téléchargement ; statut -1 s'il n'existe plus. */
    static int[] etat(Context ctx, long id) {
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        try {
            if (c == null || !c.moveToFirst()) return new int[]{-1, 0};
            int s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long fait = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            return new int[]{s, total > 0 ? (int) (fait * 100 / total) : 0};
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------- Installation ----------

    static Intent intentInstall(Context ctx) {
        long id = ctx.getSharedPreferences(Alertes.PREFS, 0).getLong("upd_id", -1);
        if (id == -1) return null;
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) return null;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    static void notifierPret(Context ctx, int n) {
        Intent i = intentInstall(ctx);
        if (i == null) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Mises à jour",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 4300, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CANAL) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("SpyEbay version " + n + " prête")
                .setContentText("Touche pour installer la mise à jour")
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            nm.notify(4300, b.build());
        } catch (SecurityException ignored) { }
    }
}
