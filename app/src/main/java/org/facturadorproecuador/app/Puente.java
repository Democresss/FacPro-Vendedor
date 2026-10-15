package org.facturadorproecuador.app;

import android.webkit.JavascriptInterface;

/** Lo que la página puede pedirle al teléfono: window.FacProNativo (solo existe dentro de la app). */
public class Puente {

    private final MainActivity app;

    Puente(MainActivity app) {
        this.app = app;
    }

    @JavascriptInterface
    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    @JavascriptInterface
    public String app() {
        return "vendedor";
    }

    // ── Correo y contraseña guardados en el teléfono (cifrados): solo para las páginas de FacturaPro ──
    private boolean paginaNuestra() {
        String u = app.urlActual;
        return u != null && MainActivity.esNuestro(android.net.Uri.parse(u));
    }

    @JavascriptInterface
    public String acceso() {
        return paginaNuestra() ? Boveda.leer(app) : "";
    }

    @JavascriptInterface
    public void guardarAcceso(String usuario, String clave) {
        if (paginaNuestra()) Boveda.guardar(app, usuario, clave);
    }

    @JavascriptInterface
    public void borrarAcceso() {
        Boveda.borrar(app);
    }

    /** Cerró sesión a mano: la próxima vez no entra sola. */
    @JavascriptInterface
    public void marcarSalida() {
        Boveda.marcarSalida(app);
    }

    @JavascriptInterface
    public boolean salio() {
        return Boveda.salio(app);
    }

    @JavascriptInterface
    public void guardarArchivo(String dataUrl, String nombre) {
        app.guardarArchivo(dataUrl, nombre);
    }

    /** Hace vibrar el teléfono (p. ej. al llegar una entrega o pedido nuevo). */
    @JavascriptInterface
    public void vibrar(long ms) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) app.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(ms > 0 ? ms : 500, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms > 0 ? ms : 500);
                }
            }
        } catch (Exception ignored) {}
    }
}
