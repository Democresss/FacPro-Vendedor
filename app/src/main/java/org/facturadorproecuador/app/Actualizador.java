package org.facturadorproecuador.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Se actualiza sola: al abrirse (y al volver, cada 10 min) mira la última versión publicada en GitHub; si hay una
 * nueva la descarga y abre el instalador. Las pantallas no necesitan esto (vienen de la web); esto es para la parte
 * nativa (GPS, cámara, descargas).
 */
class Actualizador {

    private final MainActivity app;
    private volatile boolean enCurso = false;
    private long ultimaRevision = 0;
    private File apk;
    private boolean esperandoPermiso = false;

    Actualizador(MainActivity app) {
        this.app = app;
    }

    void revisar(boolean ahora) {
        if (enCurso) return;
        // Al abrirse siempre; al volver a la app, si pasaron más de 10 min
        if (!ahora && System.currentTimeMillis() - ultimaRevision < 600_000) return;
        ultimaRevision = System.currentTimeMillis();
        enCurso = true;
        new Thread(() -> {
            try {
                JSONObject v = new JSONObject(leer(BuildConfig.ACTUALIZACIONES + "version.json"));
                int codigo = v.optInt("versionCode", 0);
                String nombre = v.optString("versionName", "");
                if (codigo > BuildConfig.VERSION_CODE) {
                    app.runOnUiThread(() -> descargar(nombre));
                    return;
                }
            } catch (Exception ignorado) {
                // sin internet o sin versiones publicadas: se revisa la próxima vez
            }
            enCurso = false;
        }).start();
    }

    /** Al volver a la app: si fue a dar permiso de instalar, sigue; si no, revisa (como mucho cada hora). */
    void alVolver() {
        if (esperandoPermiso && apk != null) {
            esperandoPermiso = false;
            instalar();
            return;
        }
        revisar(false);
    }

    private void descargar(String version) {
        LinearLayout caja = new LinearLayout(app);
        caja.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * app.getResources().getDisplayMetrics().density);
        caja.setPadding(p, p / 2, p, 0);
        TextView texto = new TextView(app);
        texto.setText("Descargando la versión " + version + "…");
        ProgressBar barra = new ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(100);
        caja.addView(texto);
        caja.addView(barra);
        AlertDialog d = app.dialogo()
                .setTitle("Actualizando " + app.getString(R.string.app_name))
                .setView(caja)
                .setCancelable(false)
                .create();
        d.show();

        new Thread(() -> {
            try {
                File dir = new File(app.getCacheDir(), "actualizacion");
                if (!dir.exists()) dir.mkdirs();
                File destino = new File(dir, BuildConfig.APK);
                HttpURLConnection c = abrir(BuildConfig.ACTUALIZACIONES + BuildConfig.APK);
                long total = c.getContentLengthLong();
                long leidos = 0;
                try (InputStream i = c.getInputStream(); OutputStream o = new FileOutputStream(destino)) {
                    byte[] b = new byte[64 * 1024];
                    int n;
                    int ultimo = -1;
                    while ((n = i.read(b)) != -1) {
                        o.write(b, 0, n);
                        leidos += n;
                        if (total > 0) {
                            final int pct = (int) (leidos * 100 / total);
                            if (pct != ultimo) {
                                ultimo = pct;
                                app.runOnUiThread(() -> barra.setProgress(pct));
                            }
                        }
                    }
                } finally {
                    c.disconnect();
                }
                if (leidos < 100_000 || (total > 0 && leidos != total)) throw new Exception("descarga incompleta");
                apk = destino;
                app.runOnUiThread(() -> {
                    d.dismiss();
                    instalar();
                });
            } catch (Exception e) {
                app.runOnUiThread(() -> {
                    d.dismiss();
                    app.aviso("No se pudo descargar la actualización; se intentará de nuevo más tarde.");
                });
                enCurso = false;
            }
        }).start();
    }

    private void instalar() {
        if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
            app.dialogo()
                    .setTitle("Permite las actualizaciones")
                    .setMessage("Para que " + app.getString(R.string.app_name) + " se actualice sola, activa "
                            + "«Permitir de esta fuente» en la pantalla que se abre y vuelve con la flecha atrás.")
                    .setCancelable(false)
                    .setPositiveButton("Continuar", (x, y) -> {
                        esperandoPermiso = true;
                        try {
                            app.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + app.getPackageName())));
                        } catch (Exception e) {
                            esperandoPermiso = false;
                            enCurso = false;
                        }
                    })
                    .setNegativeButton("Más tarde", (x, y) -> enCurso = false)
                    .show();
            return;
        }
        try {
            Uri u = FileProvider.getUriForFile(app, app.getPackageName() + ".archivos", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception e) {
            app.aviso("No se pudo abrir el instalador de la actualización");
        }
        enCurso = false;
    }

    private static HttpURLConnection abrir(String url) throws Exception {
        // GitHub responde con redirecciones (github.com → objects.githubusercontent.com): se siguen a mano
        for (int saltos = 0; saltos < 6; saltos++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "FacProApp/" + BuildConfig.VERSION_NAME);
            int codigo = c.getResponseCode();
            if (codigo >= 300 && codigo < 400) {
                String destino = c.getHeaderField("Location");
                c.disconnect();
                if (destino == null) throw new Exception("redirección sin destino");
                url = new URL(new URL(url), destino).toString();
                continue;
            }
            if (codigo != 200) {
                c.disconnect();
                throw new Exception("HTTP " + codigo);
            }
            return c;
        }
        throw new Exception("demasiadas redirecciones");
    }

    private static String leer(String url) throws Exception {
        HttpURLConnection c = abrir(url);
        try (InputStream i = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) != -1) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
