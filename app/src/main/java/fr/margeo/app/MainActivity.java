package fr.margeo.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.Locale;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> fichiers;
    private static final int CHOIX_FICHIER = 7;
    private boolean installEnAttente = false;
    private static final int VOIX = 8;
    private TextToSpeech tts;
    private boolean ttsPret = false;
    private String partage = null;

    /** Texte reçu via « Partager » (lien d'annonce eBay). */
    private void lirePartage(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t == null || t.isEmpty()) return;
        partage = t;
        if (web != null) rappel("window.__partage && window.__partage(" + JSONObject.quote(t) + ")");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        lirePartage(intent);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Retour des réglages « Sources inconnues » : on relance l'installation
        if (installEnAttente && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            installEnAttente = false;
            installer();
        }
    }

    /** Ouvre l'installateur Android sur l'APK téléchargé. */
    private void installer() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            installEnAttente = true;
            rappel("window.__updProgress && window.__updProgress(100,'perm')");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = Updater.intentInstall(this);
        if (i == null) {
            rappel("window.__updProgress && window.__updProgress(0,'err')");
            return;
        }
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOIX) {
            String texte = "";
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) texte = r.get(0);
            }
            rappel("window.__voix && window.__voix(" + JSONObject.quote(texte) + ")");
            return;
        }
        if (requestCode != CHOIX_FICHIER || fichiers == null) return;
        Uri[] res = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                res = new Uri[cd.getItemCount()];
                for (int i = 0; i < cd.getItemCount(); i++) res[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                res = new Uri[]{data.getData()};
            }
        }
        fichiers.onReceiveValue(res);
        fichiers = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Journal.surveiller(getApplicationContext());
        partage = null;
        Intent recu = getIntent();
        if (recu != null && Intent.ACTION_SEND.equals(recu.getAction())) partage = recu.getStringExtra(Intent.EXTRA_TEXT);
        int fond = Color.parseColor("#07080c");
        getWindow().setStatusBarColor(fond);
        getWindow().setNavigationBarColor(fond);

        web = new WebView(this);
        web.setBackgroundColor(fond);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Native");

        // Les liens vers eBay et GitHub s'ouvrent dans le navigateur ou l'appli eBay
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    return true;
                }
                return false;
            }
        });

        // Sélecteur de fichiers (photos de revente, restauration de sauvegarde)
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fichiers != null) fichiers.onReceiveValue(null);
                fichiers = callback;
                try {
                    Intent i = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(i, CHOIX_FICHIER);
                } catch (Exception e) {
                    fichiers = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        M710Deals.planifier(this);
        Updater.planifier(this);
    }

    @Override
    public void onBackPressed() {
        // Ferme d'abord un panneau ou un onglet, sinon quitte l'appli
        web.evaluateJavascript("window.onBack ? window.onBack() : false", value -> {
            if (!"true".equals(value)) retourSysteme();
        });
    }

    private void retourSysteme() {
        super.onBackPressed();
    }

    private void rappel(String js) {
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    class Bridge {
        /** Numéro de version installé (= numéro de compilation GitHub). */
        @JavascriptInterface
        public int version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Exception e) {
                return 0;
            }
        }

        /** Enregistre clés, réglages et alertes pour la tâche en arrière-plan. */
        @JavascriptInterface
        public void saveConfig(String json) {
            getSharedPreferences(Alertes.PREFS, 0).edit().putString("cfg", json).apply();
            M710Deals.planifier(MainActivity.this);
            Updater.planifier(MainActivity.this);
        }

        /** Télécharge la version n depuis GitHub puis lance l'installation. */
        @JavascriptInterface
        public void updateNow(final String url, final int n) {
            new Thread(() -> {
                try {
                    long id = Updater.telecharger(getApplicationContext(), url, n);
                    for (int i = 0; i < 1200; i++) {
                        int[] e = Updater.etat(getApplicationContext(), id);
                        if (e[0] == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                            rappel("window.__updProgress && window.__updProgress(100,'ok')");
                            runOnUiThread(MainActivity.this::installer);
                            return;
                        }
                        if (e[0] == android.app.DownloadManager.STATUS_FAILED || e[0] == -1) break;
                        rappel("window.__updProgress && window.__updProgress(" + e[1] + ",'run')");
                        Thread.sleep(500);
                    }
                } catch (Exception ignored) { }
                rappel("window.__updProgress && window.__updProgress(0,'err')");
            }).start();
        }

        /** Résultat de la dernière vérification des alertes. */
        @JavascriptInterface
        public String lastRun() {
            return getSharedPreferences(Alertes.PREFS, 0).getString("dernier", "");
        }

        /** Vérifie les alertes tout de suite. */
        @JavascriptInterface
        public void runAlerts() {
            new Thread(() -> {
                String r = Alertes.lancer(getApplicationContext());
                rappel("window.__alertsDone && window.__alertsDone(" + r + ")");
            }).start();
        }

        /** Demande l'autorisation d'afficher des notifications (Android 13+). */
        @JavascriptInterface
        public void askNotifications() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1));
            }
        }

        /** Enregistre un fichier dans Téléchargements. Renvoie "" si OK, sinon le message d'erreur. */
        @JavascriptInterface
        public String saveFile(String nom, String mime, String contenu) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nom);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "Impossible de créer le fichier";
                    OutputStream o = getContentResolver().openOutputStream(uri);
                    if (o == null) return "Impossible d'écrire le fichier";
                    o.write(contenu.getBytes("UTF-8"));
                    o.close();
                    return "";
                }
                return "ANCIEN"; // Android 9 et moins : on passe par le partage
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        /** Ouvre le menu Partager d'Android avec un texte. */
        @JavascriptInterface
        public void share(String titre, String texte) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, titre);
                i.putExtra(Intent.EXTRA_TEXT, texte);
                startActivity(Intent.createChooser(i, titre));
            });
        }

        /** Coffre-fort chiffré pour les clés. */
        @JavascriptInterface
        public void secretSet(String nom, String valeur) { Secrets.set(getApplicationContext(), nom, valeur); }

        @JavascriptInterface
        public String secretGet(String nom) { return Secrets.get(getApplicationContext(), nom); }

        /** Journal d'erreurs Android. */
        @JavascriptInterface
        public String journal() { return Journal.lire(getApplicationContext()); }

        @JavascriptInterface
        public void journalEffacer() { Journal.effacer(getApplicationContext()); }

        /** Compteur de requêtes eBay du jour (partagé avec les alertes). */
        @JavascriptInterface
        public int quotaAdd(int n) { return Journal.compter(getApplicationContext(), n); }

        /** Infos sur l'appareil, pour le journal. */
        @JavascriptInterface
        public String appareil() { return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE; }

        /** Couleur des barres système selon le thème (icônes foncées en thème clair). */
        @JavascriptInterface
        public void setBars(final String couleur, final boolean clair) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(couleur);
                    getWindow().setStatusBarColor(c);
                    getWindow().setNavigationBarColor(c);
                    web.setBackgroundColor(c);
                    int f = getWindow().getDecorView().getSystemUiVisibility();
                    int lum = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | (Build.VERSION.SDK_INT >= 26 ? android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
                    getWindow().getDecorView().setSystemUiVisibility(clair ? (f | lum) : (f & ~lum));
                } catch (Exception ignored) { }
            });
        }

        /** Texte partagé au lancement de l'appli (lu une seule fois). */
        @JavascriptInterface
        public String sharedText() { String t = partage; partage = null; return t == null ? "" : t; }

        /** Suit les redirections d'un lien court (ebay.us…) et renvoie l'adresse finale. */
        @JavascriptInterface
        public void resolve(final String id, final String url) {
            new Thread(() -> {
                String u = url;
                try {
                    for (int i = 0; i < 6; i++) {
                        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SpyEbay");
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        int code = c.getResponseCode();
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (code < 300 || code >= 400 || loc == null) break;
                        u = new URL(new URL(u), loc).toString();
                    }
                } catch (Exception ignored) { }
                String json;
                try { json = new JSONObject().put("url", u).toString(); } catch (Exception e) { json = "{}"; }
                rappel("window.__native(" + JSONObject.quote(id) + ",200," + JSONObject.quote(json) + ")");
            }).start();
        }

        /** Dictée vocale (reconnaissance vocale d'Android). */
        @JavascriptInterface
        public void listen() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Pose ta question à SpyEbay");
                try {
                    startActivityForResult(i, VOIX);
                } catch (Exception e) {
                    rappel("window.__voix && window.__voix(null)");
                }
            });
        }

        /** Lecture à voix haute (synthèse vocale d'Android). */
        @JavascriptInterface
        public void speak(final String texte) {
            runOnUiThread(() -> {
                if (tts == null) {
                    tts = new TextToSpeech(MainActivity.this, st -> {
                        if (st == TextToSpeech.SUCCESS) {
                            tts.setLanguage(Locale.FRANCE);
                            ttsPret = true;
                            tts.speak(texte, TextToSpeech.QUEUE_FLUSH, null, "margeo");
                        }
                    });
                } else if (ttsPret) {
                    tts.speak(texte, TextToSpeech.QUEUE_FLUSH, null, "margeo");
                }
            });
        }

        @JavascriptInterface
        public void stopSpeak() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }

        /** Met à jour le widget d'écran d'accueil. */
        @JavascriptInterface
        public void saveWidget(String json) {
            getSharedPreferences(Alertes.PREFS, 0).edit().putString("widget", json).apply();
            Widget.maj(MainActivity.this);
        }

        /** Copie un texte dans le presse-papiers. */
        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SpyEbay", text));
            });
        }

        /** Appels HTTP faits par Android (pas de blocage CORS comme dans un navigateur). */
        @JavascriptInterface
        public void http(final String id, final String method, final String url,
                         final String headers, final String body) {
            new Thread(() -> {
                int code = 0;
                String text;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    JSONObject h = new JSONObject(headers == null || headers.isEmpty() ? "{}" : headers);
                    Iterator<String> keys = h.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        c.setRequestProperty(k, h.getString(k));
                    }
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        OutputStream o = c.getOutputStream();
                        o.write(body.getBytes("UTF-8"));
                        o.close();
                    }
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    text = in == null ? "" : lire(in);
                } catch (Exception e) {
                    text = "Erreur réseau : " + e.getMessage();
                }
                rappel("window.__native(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")");
            }).start();
        }
    }

    private static String lire(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }
}
