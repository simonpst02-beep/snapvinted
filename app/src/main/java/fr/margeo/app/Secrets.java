package fr.margeo.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Coffre-fort des clés (eBay, Claude) : chiffrées avec une clé AES gardée
 * dans le Keystore Android, qui ne peut pas être extraite du téléphone.
 */
public class Secrets {

    private static final String ALIAS = "margeo_coffre";
    private static final String PREFS = "margeo_secrets";

    private static SecretKey cle() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    static synchronized void set(Context ctx, String nom, String valeur) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, 0);
        if (valeur == null || valeur.isEmpty()) { sp.edit().remove(nom).apply(); return; }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cle());
            byte[] iv = c.getIV();
            byte[] chiffre = c.doFinal(valeur.getBytes("UTF-8"));
            byte[] tout = new byte[iv.length + chiffre.length];
            System.arraycopy(iv, 0, tout, 0, iv.length);
            System.arraycopy(chiffre, 0, tout, iv.length, chiffre.length);
            sp.edit().putString(nom, Base64.encodeToString(tout, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            Journal.noter(ctx, "Coffre-fort (écriture) : " + e);
        }
    }

    static synchronized String get(Context ctx, String nom) {
        String b = ctx.getSharedPreferences(PREFS, 0).getString(nom, null);
        if (b == null) return "";
        try {
            byte[] tout = Base64.decode(b, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, cle(), new GCMParameterSpec(128, tout, 0, 12));
            return new String(c.doFinal(tout, 12, tout.length - 12), "UTF-8");
        } catch (Exception e) {
            Journal.noter(ctx, "Coffre-fort (lecture) : " + e);
            return "";
        }
    }
}
