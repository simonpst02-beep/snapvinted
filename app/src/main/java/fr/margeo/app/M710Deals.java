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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class M710Deals {

    static final int JOB_ID = 44;

    static final String SERVER =
            "http://100.81.68.109:8103";

    static final String CANAL =
            "m710_affaires";


    static void planifier(Context ctx) {
        JobScheduler js =
                (JobScheduler) ctx.getSystemService(
                        Context.JOB_SCHEDULER_SERVICE
                );

        if (js == null) return;

        // Le M710 fait maintenant les recherches eBay :
        // on supprime l'ancien scan eBay local horaire.
        js.cancel(Alertes.JOB_ID);

        JobInfo job =
                new JobInfo.Builder(
                        JOB_ID,
                        new ComponentName(
                                ctx,
                                AlertJob.class
                        )
                )
                .setPeriodic(
                        30L * 60L * 1000L
                )
                .setRequiredNetworkType(
                        JobInfo.NETWORK_TYPE_ANY
                )
                .setPersisted(true)
                .build();

        js.schedule(job);
    }


    static synchronized String lancer(Context ctx) {
        JSONObject result = new JSONObject();

        int notifications = 0;
        String erreur = "";

        try {
            JSONArray deals = getCandidates();

            SharedPreferences sp =
                    ctx.getSharedPreferences(
                            Alertes.PREFS,
                            0
                    );

            JSONObject vus =
                    new JSONObject(
                            sp.getString(
                                    "m710_deals_vus",
                                    "{}"
                            )
                    );

            boolean initialise =
                    sp.getBoolean(
                            "m710_deals_init",
                            false
                    );

            /*
             * Première exécution :
             * on mémorise les annonces déjà présentes
             * sans envoyer 20 anciennes notifications.
             */
            if (!initialise) {
                for (
                        int i = 0;
                        i < deals.length();
                        i++
                ) {
                    JSONObject d =
                            deals.getJSONObject(i);

                    String id =
                            cle(d);

                    if (!id.isEmpty()) {
                        vus.put(
                                id,
                                System.currentTimeMillis()
                        );
                    }
                }

                sp.edit()
                        .putString(
                                "m710_deals_vus",
                                vus.toString()
                        )
                        .putBoolean(
                                "m710_deals_init",
                                true
                        )
                        .apply();

                result.put("ok", true);
                result.put(
                        "notifications",
                        0
                );
                result.put(
                        "initialisation",
                        true
                );

                return result.toString();
            }


            for (
                    int i = 0;
                    i < deals.length();
                    i++
            ) {
                JSONObject d =
                        deals.getJSONObject(i);

                String id = cle(d);

                if (
                        id.isEmpty() ||
                        vus.has(id)
                ) {
                    continue;
                }

                vus.put(
                        id,
                        System.currentTimeMillis()
                );

                notifier(ctx, d);

                notifications++;
            }


            // Nettoyage après 30 jours
            long limite =
                    System.currentTimeMillis()
                    - 30L
                    * 24L
                    * 3600L
                    * 1000L;

            JSONObject garde =
                    new JSONObject();

            JSONArray noms =
                    vus.names();

            if (noms != null) {
                for (
                        int i = 0;
                        i < noms.length();
                        i++
                ) {
                    String id =
                            noms.getString(i);

                    long t =
                            vus.optLong(id);

                    if (t >= limite) {
                        garde.put(id, t);
                    }
                }
            }

            sp.edit()
                    .putString(
                            "m710_deals_vus",
                            garde.toString()
                    )
                    .apply();

        } catch (Exception e) {
            erreur =
                    String.valueOf(
                            e.getMessage()
                    );
        }

        try {
            result.put(
                    "ok",
                    erreur.isEmpty()
            );

            result.put(
                    "notifications",
                    notifications
            );

            result.put(
                    "erreur",
                    erreur
            );

            result.put(
                    "quand",
                    System.currentTimeMillis()
            );

        } catch (Exception ignored) {
        }

        return result.toString();
    }


    static JSONArray getCandidates()
            throws Exception {

        URL url =
                new URL(
                        SERVER
                        + "/api/watch/candidates?status=new&limit=50"
                );

        HttpURLConnection c =
                (HttpURLConnection)
                        url.openConnection();

        c.setRequestMethod("GET");
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);

        int code =
                c.getResponseCode();

        InputStream in =
                code >= 400
                        ? c.getErrorStream()
                        : c.getInputStream();

        StringBuilder sb =
                new StringBuilder();

        if (in != null) {
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    in,
                                    "UTF-8"
                            )
                    );

            String line;

            while (
                    (line = r.readLine())
                    != null
            ) {
                sb.append(line);
            }

            r.close();
        }

        c.disconnect();

        if (code >= 400) {
            throw new Exception(
                    "M710 HTTP " + code
            );
        }

        return new JSONArray(
                sb.toString()
        );
    }


    static String cle(JSONObject d) {
        String id =
                d.optString(
                        "ebay_item_id",
                        ""
                );

        if (id.isEmpty()) {
            id =
                    d.optString(
                            "id",
                            ""
                    );
        }

        return id;
    }


    static void notifier(
            Context ctx,
            JSONObject d
    ) {
        NotificationManager nm =
                (NotificationManager)
                        ctx.getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        if (nm == null) return;


        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(
                            CANAL,
                            "Affaires détectées par le M710",
                            NotificationManager
                                    .IMPORTANCE_HIGH
                    )
            );
        }


        String id =
                cle(d);

        String url =
                d.optString(
                        "url",
                        "https://www.ebay.fr"
                );


        Intent voir =
                new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(url)
                );


        PendingIntent pi =
                PendingIntent.getActivity(
                        ctx,
                        id.hashCode(),
                        voir,
                        PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_UPDATE_CURRENT
                );


        double achat =
                d.optDouble(
                        "purchase_price",
                        0
                )
                +
                d.optDouble(
                        "shipping_price",
                        0
                );

        double revente =
                d.optDouble(
                        "resale_estimate",
                        0
                );

        double marge =
                d.optDouble(
                        "net_margin",
                        0
                );


        String titre =
                String.format(
                        Locale.FRANCE,
                        "🤖 +%.0f € de marge possible",
                        marge
                );


        String texte =
                String.format(
                        Locale.FRANCE,
                        "%s — achat %.0f €, revente %.0f €",
                        d.optString(
                                "title",
                                "Nouvelle affaire eBay"
                        ),
                        achat,
                        revente
                );


        Notification.Builder b =
                Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(
                                ctx,
                                CANAL
                        )
                        : new Notification.Builder(
                                ctx
                        );


        b.setSmallIcon(
                R.drawable.ic_notif
        )
        .setContentTitle(titre)
        .setContentText(texte)
        .setStyle(
                new Notification
                        .BigTextStyle()
                        .bigText(texte)
        )
        .setContentIntent(pi)
        .setAutoCancel(true);


        try {
            nm.notify(
                    id.hashCode(),
                    b.build()
            );

        } catch (
                SecurityException ignored
        ) {
        }
    }
}
