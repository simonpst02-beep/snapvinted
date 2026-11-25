package fr.margeo.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Widget d'écran d'accueil : meilleure affaire du moment et bénéfice du mois. */
public class Widget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, vue(ctx));
    }

    static RemoteViews vue(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        try {
            JSONObject w = new JSONObject(ctx.getSharedPreferences(Alertes.PREFS, 0).getString("widget", "{}"));
            v.setTextViewText(R.id.w_best, w.optString("best", "–"));
            v.setTextViewText(R.id.w_title, w.optString("titre", "Ouvre l'appli pour chercher"));
            v.setTextViewText(R.id.w_month, "Bénéfice du mois : " + w.optString("mois", "0 €"));
        } catch (Exception ignored) { }
        Intent ouvrir = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, ouvrir,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    /** Rafraîchit tous les widgets posés sur l'écran d'accueil. */
    static void maj(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, Widget.class));
        for (int id : ids) mgr.updateAppWidget(id, vue(ctx));
    }
}
