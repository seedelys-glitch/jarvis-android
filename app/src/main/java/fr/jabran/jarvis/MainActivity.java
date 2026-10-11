package fr.jabran.jarvis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Jarvis pour Android : affiche l'appli Jarvis servie par le PC, donne accès au micro,
 * et sait rallumer le PC à distance (Wake-on-LAN) même quand il est en vraie veille.
 */
public class MainActivity extends Activity {

    private static final int HTTPS_PORT = 8766;
    private static final int HTTP_PORT = 8765;
    private WebView web;
    private SharedPreferences prefs;
    private boolean triedHttp = false;
    private PermissionRequest pendingPermission;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#050102"));
        web.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "JarvisAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // certificat auto-signé de ton PC : accepté uniquement pour l'adresse du PC
                String ip = prefs.getString("ip", "");
                if (!ip.isEmpty() && error.getUrl() != null && error.getUrl().contains("://" + ip + ":")) {
                    handler.proceed();
                } else {
                    handler.cancel();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                if (!triedHttp) {           // version sécurisée indisponible -> version simple
                    triedHttp = true;
                    view.loadUrl(url(false));
                    return;
                }
                view.loadUrl("file:///android_asset/hors-ligne.html");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    }
                });
            }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        if (prefs.getString("ip", "").isEmpty() || prefs.getString("code", "").isEmpty()) {
            showSettings(true);
        } else {
            load();
        }
    }

    private String url(boolean https) {
        String ip = prefs.getString("ip", "");
        String code = prefs.getString("code", "");
        return (https ? "https://" + ip + ":" + HTTPS_PORT : "http://" + ip + ":" + HTTP_PORT)
                + "/app#code=" + code + "&android=1";
    }

    private void load() {
        triedHttp = false;
        web.loadUrl(url(true));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    private void showSettings(boolean firstTime) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView info = new TextView(this);
        info.setText("Les infos sont dans la fenêtre noire de Jarvis sur ton PC "
                + "(ligne « Appli mobile »).");
        box.addView(info);

        EditText ip = new EditText(this);
        ip.setHint("Adresse du PC (ex : 192.168.1.20)");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setText(prefs.getString("ip", "192.168."));
        box.addView(ip);

        EditText code = new EditText(this);
        code.setHint("Code de l'appli");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setText(prefs.getString("code", ""));
        box.addView(code);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Connexion à Jarvis")
                .setView(box)
                .setCancelable(!firstTime)
                .setPositiveButton("Connecter", (d, w) -> {
                    String v = ip.getText().toString().trim()
                            .replace("https://", "").replace("http://", "");
                    if (v.contains(":")) v = v.substring(0, v.indexOf(':'));
                    if (v.contains("/")) v = v.substring(0, v.indexOf('/'));
                    prefs.edit().putString("ip", v)
                            .putString("code", code.getText().toString().trim()).apply();
                    load();
                });
        if (!firstTime) b.setNegativeButton("Annuler", null);
        b.show();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ Wake-on-LAN
    private void sendMagicPacket(String mac, String broadcast) throws Exception {
        String[] hex = mac.replace("-", ":").split(":");
        if (hex.length != 6) throw new IllegalArgumentException("adresse MAC invalide");
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(hex[i], 16);
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
        for (int i = 6; i < packet.length; i += 6) System.arraycopy(macBytes, 0, packet, i, 6);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            String[] targets = {broadcast, "255.255.255.255"};
            for (String t : targets) {
                if (t == null || t.isEmpty()) continue;
                InetAddress addr = InetAddress.getByName(t);
                for (int port : new int[]{9, 7}) {
                    for (int k = 0; k < 3; k++) {
                        socket.send(new DatagramPacket(packet, packet.length, addr, port));
                    }
                }
            }
        }
    }

    /** Fonctions appelées depuis la page Jarvis (window.JarvisAndroid). */
    private class Bridge {
        @JavascriptInterface
        public boolean isApp() {
            return true;
        }

        @JavascriptInterface
        public void saveWol(String mac, String broadcast) {
            if (mac != null && !mac.isEmpty()) {
                prefs.edit().putString("mac", mac).putString("broadcast", broadcast).apply();
            }
        }

        @JavascriptInterface
        public String wake() {
            String mac = prefs.getString("mac", "");
            String bc = prefs.getString("broadcast", "");
            if (mac.isEmpty()) {
                return "Je ne connais pas encore l'adresse de ton PC. "
                        + "Ouvre l'appli une fois quand le PC est allumé.";
            }
            new Thread(() -> {
                try {
                    sendMagicPacket(mac, bc);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Signal envoyé, le PC démarre…", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Échec : " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
            return "Signal de rallumage envoyé. Le PC démarre, ça prend quelques secondes.";
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(MainActivity.this::load);
        }

        @JavascriptInterface
        public void settings() {
            runOnUiThread(() -> showSettings(false));
        }

        @JavascriptInterface
        public String pcIp() {
            return prefs.getString("ip", "");
        }
    }
}
