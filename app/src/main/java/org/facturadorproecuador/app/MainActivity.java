package org.facturadorproecuador.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * La app es la misma web de FacturaPro (/v2/pedidos/dashboard) dentro de un WebView: se ve y funciona igual, y cada
 * cambio que se publica en la web llega sin reinstalar. Lo nativo es lo que la web no puede: cámara/galería,
 * descargas, enlaces a Maps/Waze/WhatsApp y actualizarse sola.
 */
public class MainActivity extends Activity {

    private static final int ELEGIR_ARCHIVO = 3;

    WebView web;
    /** Página abierta (la lee el puente para no darle la contraseña guardada a otra página). */
    volatile String urlActual = "";
    private View sinConexion;
    private Actualizador actualizador;

    private ValueCallback<Uri[]> archivoCallback;
    private Uri fotoUri;
    private File fotoArchivo;

    private final Set<Long> misDescargas = new HashSet<>();
    private BroadcastReceiver receptorDescargas;
    private ConnectivityManager.NetworkCallback vigiaRed;
    /** Sin internet se usa la última versión guardada de la pantalla (y la página trabaja con lo del teléfono). */
    private boolean usandoGuardado = false;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.parseColor("#0F172A"));
        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sinConexion = crearSinConexion();
        sinConexion.setVisibility(View.GONE);
        raiz.addView(sinConexion, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(raiz);

        configurarWeb();
        vigilarRed();
        if (estado == null || web.restoreState(estado) == null) {
            web.loadUrl(BuildConfig.SITIO + BuildConfig.INICIO);
        }

        receptorDescargas = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (misDescargas.remove(id)) abrirDescarga(id);
            }
        };
        ContextCompat.registerReceiver(this, receptorDescargas,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED);

        // Recién actualizada: se avisa la versión nueva (así se sabe que quedó)
        avisarSiSeActualizo();

        // Busca actualizaciones apenas se abre
        actualizador = new Actualizador(this);
        actualizador.revisar(true);
    }

    private void configurarWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(s.getUserAgentString() + " FacProApp/" + BuildConfig.VERSION_NAME
 + " (vendedor)");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Puente(this), "FacProNativo");
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Cromo());
        web.setDownloadListener((url, agente, disposicion, tipo, largo) -> descargar(url, agente, disposicion, tipo));
    }

    // ── Sin internet: la pantalla guardada; al volver la señal, todo normal (la página envía lo pendiente) ──
    private boolean hayInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private void vigilarRed() {
        if (!hayInternet()) {
            usandoGuardado = true;
            web.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            vigiaRed = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    runOnUiThread(() -> {
                        web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                        if (usandoGuardado) {
                            usandoGuardado = false;
                            if (sinConexion.getVisibility() == View.VISIBLE) {
                                sinConexion.setVisibility(View.GONE);
                                web.reload();
                            }
                        }
                    });
                }

                @Override
                public void onLost(Network n) {
                    runOnUiThread(() -> {
                        if (!hayInternet()) {
                            usandoGuardado = true;
                            web.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                        }
                    });
                }
            };
            cm.registerDefaultNetworkCallback(vigiaRed);
        } catch (Exception ignorado) {
        }
    }

    // ── Navegación: lo de FacturaPro se queda en la app; Maps, Waze, WhatsApp, llamadas… se abren en su app ──

    static boolean esNuestro(Uri u) {
        if (u == null || u.getHost() == null) return false;
        String sc = u.getScheme();
        if (!"https".equals(sc) && !"http".equals(sc)) return false;
        String nuestro = Uri.parse(BuildConfig.SITIO).getHost();
        String h = u.getHost().toLowerCase();
        return h.equals(nuestro) || h.endsWith("." + nuestro);
    }

    private class Cliente extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String sc = u.getScheme() == null ? "" : u.getScheme();
            if (sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
            boolean http = sc.equals("http") || sc.equals("https");
            if (http && (esNuestro(u) || !r.isForMainFrame())) return false;
            abrirFuera(u);
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap icono) {
            urlActual = url == null ? "" : url;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            urlActual = url == null ? "" : url;
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            if (!usandoGuardado) {
                // Sin señal: se abre la última versión guardada de la pantalla
                usandoGuardado = true;
                v.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ONLY);
                v.reload();
                return;
            }
            sinConexion.setVisibility(View.VISIBLE);
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            recreate();
            return true;
        }
    }

    private class Cromo extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origen, GeolocationPermissions.Callback cb) {
            cb.invoke(origen, false, false);          // Pedidos no usa la ubicación
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            return elegirArchivo(cb, p);
        }

        // window.open(…, '_blank'): PDF y reportes se descargan; lo demás se abre donde corresponde
        @Override
        public boolean onCreateWindow(WebView v, boolean dialogo, boolean gesto, Message resultado) {
            final WebView temporal = new WebView(MainActivity.this);
            temporal.setWebViewClient(new WebViewClient() {
                private boolean hecho = false;

                private void tomar(WebView w, Uri u) {
                    if (hecho || u == null || "about:blank".equals(u.toString())) return;
                    hecho = true;
                    abrirVentana(u);
                    w.post(w::destroy);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                    tomar(w, r.getUrl());
                    return true;
                }

                @Override
                public void onPageStarted(WebView w, String url, Bitmap icono) {
                    if (url != null && !url.equals("about:blank")) {
                        w.stopLoading();
                        tomar(w, Uri.parse(url));
                    }
                }
            });
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultado.obj;
            t.setWebView(temporal);
            resultado.sendToTarget();
            return true;
        }
    }

    private void abrirVentana(Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme();
        if (sc.equals("data")) {
            verImagen(u.toString());
        } else if (esNuestro(u)) {
            String t = u.toString().toLowerCase();
            if (t.contains("pdf") || t.contains("/export") || t.contains("descargar") || t.contains("download")) {
                descargar(u.toString(), web.getSettings().getUserAgentString(), null, null);
            } else {
                web.loadUrl(u.toString());
            }
        } else {
            abrirFuera(u);
        }
    }

    void abrirFuera(Uri u) {
        try {
            Intent i;
            if ("intent".equals(u.getScheme())) {
                i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                String respaldo = i.getStringExtra("browser_fallback_url");
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return;
                } catch (ActivityNotFoundException e) {
                    if (respaldo == null) throw e;
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(respaldo));
                }
            } else {
                i = new Intent(Intent.ACTION_VIEW, u);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            aviso("No hay una app para abrir este enlace");
        }
    }

    private void verImagen(String dataUrl) {
        try {
            byte[] b = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bmp == null) return;
            Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.BLACK);
            iv.setOnClickListener(x -> d.dismiss());
            d.setContentView(iv);
            d.show();
        } catch (Exception ignorado) {
        }
    }

    // ── Cámara y galería (fotos de la entrega) ──

    private boolean elegirArchivo(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (archivoCallback != null) archivoCallback.onReceiveValue(null);
        archivoCallback = cb;
        boolean imagen = false;
        String[] tipos = p.getAcceptTypes();
        if (tipos == null || tipos.length == 0) imagen = true;
        else for (String t : tipos) if (t == null || t.isEmpty() || t.startsWith("image")) imagen = true;

        Intent camara = null;
        fotoUri = null;
        fotoArchivo = null;
        if (imagen) {
            try {
                File dir = new File(getCacheDir(), "fotos");
                if (!dir.exists()) dir.mkdirs();
                fotoArchivo = File.createTempFile("foto_", ".jpg", dir);
                fotoUri = FileProvider.getUriForFile(this, getPackageName() + ".archivos", fotoArchivo);
                camara = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                camara.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
                camara.setClipData(ClipData.newRawUri("foto", fotoUri));
                camara.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                camara = null;
            }
        }
        Intent elegir;
        if (camara != null && p.isCaptureEnabled()) {
            elegir = camara;           // capture="environment": directo a la cámara, como en Chrome
        } else {
            Intent galeria = p.createIntent();
            if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                galeria.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            elegir = Intent.createChooser(galeria, "Elegir");
            if (camara != null) elegir.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camara});
        }
        try {
            startActivityForResult(elegir, ELEGIR_ARCHIVO);
        } catch (Exception e) {
            archivoCallback = null;
            cb.onReceiveValue(null);
            aviso("No se pudo abrir la cámara ni la galería");
        }
        return true;
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        super.onActivityResult(pedido, resultado, datos);
        if (pedido != ELEGIR_ARCHIVO || archivoCallback == null) return;
        Uri[] r = null;
        if (resultado == RESULT_OK) {
            if (datos != null && datos.getClipData() != null && datos.getClipData().getItemCount() > 0
                    && !esLaFoto(datos.getClipData().getItemAt(0).getUri())) {
                ClipData c = datos.getClipData();
                r = new Uri[c.getItemCount()];
                for (int i = 0; i < c.getItemCount(); i++) r[i] = c.getItemAt(i).getUri();
            } else if (datos != null && datos.getData() != null && !esLaFoto(datos.getData())) {
                r = new Uri[]{datos.getData()};
            } else if (fotoArchivo != null && fotoArchivo.length() > 0) {
                r = new Uri[]{fotoUri};
            }
        }
        archivoCallback.onReceiveValue(r);
        archivoCallback = null;
    }

    private boolean esLaFoto(Uri u) {
        return u != null && fotoUri != null && u.equals(fotoUri);
    }

    // ── Descargas (PDF de reportes y facturas, constancias) ──

    private void descargar(String url, String agente, String disposicion, String tipo) {
        try {
            if (url.startsWith("blob:")) {
                String nombre = URLUtil.guessFileName(url, disposicion, tipo);
                web.evaluateJavascript("(function(){var x=new XMLHttpRequest();x.open('GET'," + citar(url)
                        + ",true);x.responseType='blob';x.onload=function(){var r=new FileReader();"
                        + "r.onloadend=function(){FacProNativo.guardarArchivo(r.result," + citar(nombre)
                        + ");};r.readAsDataURL(x.response);};x.send();})();", null);
                return;
            }
            if (url.startsWith("data:")) {
                guardarArchivo(url, URLUtil.guessFileName(url, disposicion, tipo));
                return;
            }
            String nombre = URLUtil.guessFileName(url, disposicion, tipo);
            if (tipo == null && url.toLowerCase().contains("pdf") && !nombre.toLowerCase().endsWith(".pdf")) {
                nombre = nombre.replaceAll("\\.bin$", "") + ".pdf";
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (agente != null) req.addRequestHeader("User-Agent", agente);
            if (tipo != null) req.setMimeType(tipo);
            req.setTitle(nombre);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, nombre);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            misDescargas.add(dm.enqueue(req));
            aviso("Descargando " + nombre + "…");
        } catch (Exception e) {
            aviso("No se pudo descargar el archivo");
        }
    }

    private static String citar(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void abrirDescarga(long id) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri u = dm.getUriForDownloadedFile(id);
            if (u == null) {
                aviso("La descarga no se completó");
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, dm.getMimeTypeForDownloadedFile(id));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            aviso("Descargado: ábrelo desde las notificaciones");
        }
    }

    /** Archivo armado en la página (blob/data): se guarda y se abre. */
    void guardarArchivo(String dataUrl, String nombre) {
        try {
            int coma = dataUrl.indexOf(',');
            String cabecera = dataUrl.substring(0, Math.max(0, coma));
            String tipo = cabecera.startsWith("data:") ? cabecera.substring(5).split(";")[0] : "application/octet-stream";
            if (tipo.isEmpty()) tipo = "application/octet-stream";
            byte[] b = cabecera.contains(";base64")
                    ? Base64.decode(dataUrl.substring(coma + 1), Base64.DEFAULT)
                    : Uri.decode(dataUrl.substring(coma + 1)).getBytes("UTF-8");
            if (nombre == null || nombre.isEmpty() || nombre.endsWith(".bin")) {
                nombre = "archivo_" + System.currentTimeMillis()
                        + (tipo.contains("pdf") ? ".pdf" : tipo.startsWith("text") ? ".txt" : "");
            }
            File dir = new File(getExternalFilesDir(null), "Download");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, nombre.replaceAll("[\\\\/:*?\"<>|]", "_"));
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(b);
            }
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".archivos", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, tipo);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            final String n = f.getName();
            runOnUiThread(() -> {
                try {
                    startActivity(i);
                } catch (Exception e) {
                    aviso("Guardado: " + n);
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> aviso("No se pudo guardar el archivo"));
        }
    }

    // ── Ciclo de vida ──

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        if (actualizador != null) actualizador.alVolver();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.onPause();
        web.pauseTimers();
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receptorDescargas);
        } catch (Exception ignorado) {
        }
        try {
            if (vigiaRed != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(vigiaRed);
        } catch (Exception ignorado) {
        }
        super.onDestroy();
    }

    // ── Pantalla sin conexión ──

    private View crearSinConexion() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(Color.parseColor("#0F172A"));
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);
        TextView t = new TextView(this);
        t.setText("Sin conexión a internet");
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        TextView d = new TextView(this);
        d.setText("Revisa los datos móviles o el WiFi y vuelve a intentar.");
        d.setTextColor(Color.parseColor("#CBD5E1"));
        d.setTextSize(14);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, p / 2, 0, p);
        Button b = new Button(this);
        b.setText("Reintentar");
        b.setOnClickListener(x -> {
            sinConexion.setVisibility(View.GONE);
            String actual = web.getUrl();
            if (actual == null || actual.isEmpty() || actual.startsWith("about:")) {
                web.loadUrl(BuildConfig.SITIO + BuildConfig.INICIO);
            } else {
                web.reload();
            }
        });
        l.addView(t);
        l.addView(d);
        l.addView(b);
        return l;
    }

    private void avisarSiSeActualizo() {
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        String antes = sp.getString("version_vista", "");
        if (!BuildConfig.VERSION_NAME.equals(antes)) {
            if (!antes.isEmpty()) {
                aviso(getString(R.string.app_name) + " actualizada a la versión " + BuildConfig.VERSION_NAME);
            }
            sp.edit().putString("version_vista", BuildConfig.VERSION_NAME).apply();
        }
    }

    void aviso(String texto) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show();
    }

    AlertDialog.Builder dialogo() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }
}
