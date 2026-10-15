package org.facturadorproecuador.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Correo y contraseña guardados en el teléfono para entrar sin escribirlos (como una app, no como un navegador).
 * Van cifrados con una llave del Android Keystore que no sale del teléfono; se borran al desinstalar la app.
 * «salió»: el usuario cerró sesión a mano, así que la próxima vez no entra sola (solo se rellenan los datos).
 */
final class Boveda {

    private static final String ALIAS = "facpro_acceso";

    private Boveda() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("acceso", Context.MODE_PRIVATE);
    }

    private static SecretKey llave() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    static synchronized void guardar(Context c, String usuario, String clave) {
        try {
            JSONObject j = new JSONObject();
            j.put("usuario", usuario == null ? "" : usuario);
            j.put("clave", clave == null ? "" : clave);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, llave());
            byte[] cifrado = ci.doFinal(j.toString().getBytes(StandardCharsets.UTF_8));
            sp(c).edit()
                    .putString("datos", Base64.encodeToString(ci.getIV(), Base64.NO_WRAP) + ":"
                            + Base64.encodeToString(cifrado, Base64.NO_WRAP))
                    .putBoolean("salio", false)
                    .apply();
        } catch (Exception ignorado) {
            // sin llave del teléfono: no se guarda (se sigue pidiendo la contraseña)
        }
    }

    /** {"usuario": …, "clave": …} o "" si no hay nada guardado. */
    static synchronized String leer(Context c) {
        String d = sp(c).getString("datos", "");
        int i = d.indexOf(':');
        if (i <= 0) return "";
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, llave(), new GCMParameterSpec(128, Base64.decode(d.substring(0, i), Base64.NO_WRAP)));
            return new String(ci.doFinal(Base64.decode(d.substring(i + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            sp(c).edit().remove("datos").apply();
            return "";
        }
    }

    static synchronized void borrar(Context c) {
        sp(c).edit().remove("datos").apply();
    }

    static void marcarSalida(Context c) {
        sp(c).edit().putBoolean("salio", true).apply();
    }

    static boolean salio(Context c) {
        return sp(c).getBoolean("salio", false);
    }
}
