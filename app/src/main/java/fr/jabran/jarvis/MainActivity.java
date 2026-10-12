package fr.jabran.jarvis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

/**
 * Jarvis pour Android.
 * - Avec le PC (Wi-Fi maison ou Tailscale) : pilote Jarvis comme sur le PC.
 * - Sans le PC (éteint, en 4G) : prix du gold, actus de Trump traduites, annonces, prières,
 *   discussion avec Claude (clé API rangée dans le téléphone).
 * - Rallume le PC à distance (Wake-on-LAN).
 */
public class MainActivity extends Activity {

    private static final int PC_PORT = 8765;
    private static final String CLAUDE_MODEL = "claude-sonnet-5-5";
    private static final String CLAUDE_FALLBACK = "claude-haiku-5-5";

    private WebView web;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;

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
        web.addJavascriptInterface(new Bridge(), "JarvisAndroid");
        web.setWebViewClient(new WebViewClient());

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.FRANCE);
                ttsReady = true;
            }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        web.loadUrl("file:///android_asset/mobile.html");
        if (prefs.getString("code", "").isEmpty() && prefs.getString("claude", "").isEmpty()) {
            showSettings();
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ réglages
    private EditText field(LinearLayout box, String label, String value, int type) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, 18, 0, 0);
        box.addView(t);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(value);
        box.addView(e);
        return e;
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText ip = field(box, "Adresse du PC à la maison (ex : 192.168.1.209)",
                prefs.getString("ip", "192.168.1.209"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText ts = field(box, "Adresse Tailscale du PC (100.x.x.x) — pour la 4G",
                prefs.getString("tailscale", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText code = field(box, "Code de l'appli (fenêtre noire de Jarvis)",
                prefs.getString("code", ""), InputType.TYPE_CLASS_NUMBER);
        boolean hasKey = !prefs.getString("claude", "").isEmpty();
        EditText key = field(box, hasKey ? "Clé IA : enregistrée (laisse vide pour la garder)"
                        : "Clé IA Gemini gratuite (aistudio.google.com > Get API key)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Réglages de Jarvis")
                .setView(sv)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    SharedPreferences.Editor ed = prefs.edit()
                            .putString("ip", clean(ip.getText().toString()))
                            .putString("tailscale", clean(ts.getText().toString()))
                            .putString("code", code.getText().toString().trim());
                    String k = key.getText().toString().trim();
                    if (!k.isEmpty()) ed.putString("claude", k);
                    ed.apply();
                    web.reload();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static String clean(String v) {
        v = v.trim().replace("https://", "").replace("http://", "");
        if (v.contains("/")) v = v.substring(0, v.indexOf('/'));
        if (v.contains(":")) v = v.substring(0, v.indexOf(':'));
        return v;
    }

    // ------------------------------------------------------------------ réseau
    private static boolean reachable(String ip, int port, int ms) {
        if (ip == null || ip.isEmpty()) return false;
        try (Socket so = new Socket()) {
            so.connect(new InetSocketAddress(ip, port), ms);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String[] httpCall(String method, String url, JSONObject headers, String body,
                                     int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(Math.min(timeoutMs, 10000));
            c.setReadTimeout(timeoutMs);
            if (headers != null) {
                Iterator<String> it = headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.setRequestProperty(k, headers.optString(k));
                }
            }
            if (body != null && !body.isEmpty() && !"GET".equals(method)) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            return new String[]{String.valueOf(code), bo.toString("UTF-8")};
        } catch (Exception e) {
            return new String[]{"0", String.valueOf(e.getMessage())};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void reply(String id, String status, String body) {
        String js = "window.__native(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    // ------------------------------------------------------------------ Wake-on-LAN
    private void sendMagicPacket(String mac, String broadcast) throws Exception {
        String[] hex = mac.replace("-", ":").split(":");
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(hex[i], 16);
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
        for (int i = 6; i < packet.length; i += 6) System.arraycopy(macBytes, 0, packet, i, 6);
        ArrayList<String> targets = new ArrayList<>();
        if (broadcast != null && !broadcast.isEmpty()) targets.add(broadcast);
        String ip = prefs.getString("ip", "");
        if (ip.contains(".")) targets.add(ip.substring(0, ip.lastIndexOf('.')) + ".255");
        targets.add("255.255.255.255");
        if (!ip.isEmpty()) targets.add(ip);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (String t : targets) {
                InetAddress addr = InetAddress.getByName(t);
                for (int port : new int[]{9, 7}) {
                    for (int k = 0; k < 3; k++) {
                        socket.send(new DatagramPacket(packet, packet.length, addr, port));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ pont JavaScript
    private class Bridge {
        @JavascriptInterface
        public String code() {
            return prefs.getString("code", "");
        }

        @JavascriptInterface
        public boolean hasClaude() {
            return !prefs.getString("claude", "").isEmpty();
        }

        @JavascriptInterface
        public void settings() {
            runOnUiThread(MainActivity.this::showSettings);
        }

        /** Renvoie l'adresse du PC joignable (Wi-Fi maison puis Tailscale), sinon "". */
        @JavascriptInterface
        public String findPc() {
            String ip = prefs.getString("ip", "");
            if (reachable(ip, PC_PORT, 1500)) return "http://" + ip + ":" + PC_PORT;
            String ts = prefs.getString("tailscale", "");
            if (reachable(ts, PC_PORT, 2500)) return "http://" + ts + ":" + PC_PORT;
            return "";
        }

        @JavascriptInterface
        public void http(String id, String method, String url, String headersJson, String body, int timeoutMs) {
            new Thread(() -> {
                JSONObject h = null;
                try { h = new JSONObject(headersJson); } catch (Exception ignored) { }
                String[] r = httpCall(method, url, h, body, timeoutMs);
                reply(id, r[0], r[1]);
            }).start();
        }

        /** IA en ligne avec la clé rangée dans le téléphone (jamais exposée à la page).
         *  Clé Google (AIza…) -> Gemini gratuit ; clé Anthropic (sk-ant…) -> Claude.
         *  Réponse unifiée : {"text": "..."} */
        @JavascriptInterface
        public void claude(String id, String messagesJson, String system, int maxTokens) {
            new Thread(() -> {
                String key = prefs.getString("claude", "");
                if (key.isEmpty()) { reply(id, "401", "clé IA manquante"); return; }
                try {
                    JSONArray msgs = new JSONArray(messagesJson);
                    String[] r = key.startsWith("sk-ant") ? callClaude(key, msgs, system, maxTokens)
                                                          : callGemini(key, msgs, system, maxTokens);
                    reply(id, r[0], r[1]);
                } catch (Exception e) {
                    reply(id, "0", String.valueOf(e.getMessage()));
                }
            }).start();
        }

        @JavascriptInterface
        public void speak(String text) {
            if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "j" + System.nanoTime());
        }

        @JavascriptInterface
        public void stopSpeak() {
            if (ttsReady) tts.stop();
        }

        /** Reconnaissance vocale du téléphone ; le texte revient dans window.__onSpeech. */
        @JavascriptInterface
        public void listen() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                    speechResult(null, "autorise le micro puis réessaie");
                    return;
                }
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                    recognizer.setRecognitionListener(new RecognitionListener() {
                        public void onResults(Bundle b) {
                            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                            speechResult(r != null && !r.isEmpty() ? r.get(0) : null, "rien compris");
                        }
                        public void onError(int error) { speechResult(null, "code " + error); }
                        public void onReadyForSpeech(Bundle p) { }
                        public void onBeginningOfSpeech() { }
                        public void onRmsChanged(float v) { }
                        public void onBufferReceived(byte[] b) { }
                        public void onEndOfSpeech() { }
                        public void onPartialResults(Bundle b) { }
                        public void onEvent(int t, Bundle p) { }
                    });
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
                recognizer.startListening(i);
            });
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
            if (mac.isEmpty()) {
                return "Je ne connais pas encore l'adresse de ton PC. Ouvre l'appli une fois quand le PC est allumé.";
            }
            new Thread(() -> {
                try { sendMagicPacket(mac, prefs.getString("broadcast", "")); } catch (Exception ignored) { }
            }).start();
            return "Signal de rallumage envoyé. Le PC démarre, je me reconnecte dès qu'il répond.";
        }
    }

    private String[] callGemini(String key, JSONArray msgs, String system, int maxTokens) throws Exception {
        JSONArray contents = new JSONArray();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            contents.put(new JSONObject()
                    .put("role", "assistant".equals(m.optString("role")) ? "model" : "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", m.optString("content")))));
        }
        JSONObject b = new JSONObject().put("contents", contents)
                .put("generationConfig", new JSONObject().put("maxOutputTokens", Math.max(maxTokens, 800))
                        .put("temperature", 0.6));
        if (system != null && !system.isEmpty()) {
            b.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", system))));
        }
        JSONObject h = new JSONObject().put("content-type", "application/json").put("x-goog-api-key", key);
        String[] r = null;
        for (String model : new String[]{"gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash"}) {
            r = httpCall("POST", "https://generativelanguage.googleapis.com/v1beta/models/" + model
                    + ":generateContent", h, b.toString(), 60000);
            if ("200".equals(r[0])) {
                JSONObject j = new JSONObject(r[1]);
                JSONArray parts = j.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    if (!parts.getJSONObject(i).optBoolean("thought")) sb.append(parts.getJSONObject(i).optString("text"));
                }
                return new String[]{"200", new JSONObject().put("text", sb.toString().trim()).toString()};
            }
            if (!"404".equals(r[0])) break;
        }
        return r;
    }

    private String[] callClaude(String key, JSONArray msgs, String system, int maxTokens) throws Exception {
        JSONObject h = new JSONObject().put("x-api-key", key).put("anthropic-version", "2023-06-01")
                .put("content-type", "application/json");
        String[] r = null;
        for (String model : new String[]{CLAUDE_MODEL, CLAUDE_FALLBACK}) {
            JSONObject b = new JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", msgs);
            if (system != null && !system.isEmpty()) b.put("system", system);
            r = httpCall("POST", "https://api.anthropic.com/v1/messages", h, b.toString(), 60000);
            if ("200".equals(r[0])) {
                JSONArray c = new JSONObject(r[1]).getJSONArray("content");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < c.length(); i++) sb.append(c.getJSONObject(i).optString("text"));
                return new String[]{"200", new JSONObject().put("text", sb.toString().trim()).toString()};
            }
            if (!"404".equals(r[0]) && !"400".equals(r[0])) break;
        }
        return r;
    }

    private void speechResult(String text, String err) {
        String js = "window.__onSpeech(" + (text == null ? "null" : JSONObject.quote(text)) + ","
                + JSONObject.quote(err == null ? "" : err) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }
}
