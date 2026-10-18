package fr.jabran.jarvis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
 *   discussion avec l'IA : Gemini, Grok, DeepSeek, Groq ou Claude (clés rangées dans le téléphone ;
 *   si une IA ne répond pas, la suivante prend le relais).
 * - Rallume le PC à distance (Wake-on-LAN).
 */
public class MainActivity extends Activity {

    private static final int PC_PORT = 8765;
    private static final String CLAUDE_MODEL = "claude-sonnet-5-5";
    private static final String CLAUDE_FALLBACK = "claude-haiku-5-5";

    // IA du téléphone : identifiant (préférences « cle_<id> »), nom affiché
    private static final String[] IA_IDS = {"gemini", "grok", "deepseek", "groq", "claude"};
    private static final String[] IA_NOMS = {"Gemini", "Grok", "DeepSeek", "Groq", "Claude"};

    private WebView web;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private ValueCallback<Uri[]> fileCallback;   // choix d'une image (bouton photo de la page)
    private static final int PICK_IMAGE = 42;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        migrerCles();

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
        // Bouton photo de la page : ouvre la galerie (captures TradingView, photos).
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Image à analyser"), PICK_IMAGE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

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
        if (prefs.getString("code", "").isEmpty() && !aUneCle()) {
            showSettings();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_IMAGE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
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

    /** Ancienne version : une seule clé dans « claude ». On la range dans la bonne case. */
    private void migrerCles() {
        String old = prefs.getString("claude", "");
        if (old.isEmpty()) return;
        String id = old.startsWith("sk-ant") ? "claude" : old.startsWith("AIza") ? "gemini"
                : old.startsWith("xai-") ? "grok" : old.startsWith("gsk_") ? "groq" : "gemini";
        SharedPreferences.Editor ed = prefs.edit().remove("claude");
        if (prefs.getString("cle_" + id, "").isEmpty()) ed.putString("cle_" + id, old);
        ed.apply();
    }

    private String cle(String id) {
        return prefs.getString("cle_" + id, "");
    }

    private boolean aUneCle() {
        for (String id : IA_IDS) if (!cle(id).isEmpty()) return true;
        return false;
    }

    /** Ordre d'essai : l'IA préférée d'abord, puis les autres (Gemini gratuit en premier). */
    private ArrayList<String> ordreIA() {
        ArrayList<String> ordre = new ArrayList<>();
        String pref = prefs.getString("ia_pref", "auto");
        if (!"auto".equals(pref) && !cle(pref).isEmpty()) ordre.add(pref);
        for (String id : IA_IDS) if (!ordre.contains(id) && !cle(id).isEmpty()) ordre.add(id);
        return ordre;
    }

    private static String nomIA(String id) {
        for (int i = 0; i < IA_IDS.length; i++) if (IA_IDS[i].equals(id)) return IA_NOMS[i];
        return id;
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
        EditText code = field(box, "Code de l'appli, 6 chiffres (devant le PC : « Jarvis, code de l'appli »)",
                prefs.getString("code", ""), InputType.TYPE_CLASS_NUMBER);
        // Une case par IA. La clé n'est jamais réaffichée : case vide = on garde l'ancienne.
        String[] aide = {
                "gratuite : aistudio.google.com > Get API key",
                "console.x.ai > API Keys",
                "platform.deepseek.com > API keys",
                "gratuite : console.groq.com > API Keys",
                "console.anthropic.com > API Keys"};
        EditText[] keys = new EditText[IA_IDS.length];
        for (int i = 0; i < IA_IDS.length; i++) {
            boolean ok = !cle(IA_IDS[i]).isEmpty();
            keys[i] = field(box, "Clé " + IA_NOMS[i] + (ok ? " : enregistrée ✓ (vide = la garder, « effacer » = la retirer)"
                            : " (" + aide[i] + ")"), "",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        TextView tp = new TextView(this);
        tp.setText("IA à utiliser en premier (si elle ne répond pas, Jarvis passe à la suivante)");
        tp.setPadding(0, 18, 0, 0);
        box.addView(tp);
        String[] choix = new String[IA_IDS.length + 1];
        choix[0] = "Automatique";
        System.arraycopy(IA_NOMS, 0, choix, 1, IA_NOMS.length);
        Spinner pref = new Spinner(this);
        pref.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choix));
        String actuel = prefs.getString("ia_pref", "auto");
        for (int i = 0; i < IA_IDS.length; i++) if (IA_IDS[i].equals(actuel)) pref.setSelection(i + 1);
        box.addView(pref);

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
                    for (int i = 0; i < IA_IDS.length; i++) {
                        String k = keys[i].getText().toString().trim();
                        if (k.equalsIgnoreCase("effacer")) ed.remove("cle_" + IA_IDS[i]);
                        else if (!k.isEmpty()) ed.putString("cle_" + IA_IDS[i], k);
                    }
                    int sel = pref.getSelectedItemPosition();
                    ed.putString("ia_pref", sel <= 0 ? "auto" : IA_IDS[sel - 1]);
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
            // depuis la 5G : vers la box (qui renvoie le port 9 au PC)
            String pub = prefs.getString("public_ip", "");
            if (!pub.isEmpty()) {
                try {
                    InetAddress box = InetAddress.getByName(pub);
                    for (int k = 0; k < 5; k++) socket.send(new DatagramPacket(packet, packet.length, box, 9));
                } catch (Exception ignored) { }
            }
            for (String t : targets) {
                try {
                    InetAddress addr = InetAddress.getByName(t);
                    for (int port : new int[]{9, 7}) {
                        for (int k = 0; k < 3; k++) {
                            socket.send(new DatagramPacket(packet, packet.length, addr, port));
                        }
                    }
                } catch (Exception ignored) { }
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
            return aUneCle();
        }

        /** Noms des IA disponibles, dans l'ordre d'essai (ex. « Grok, Gemini »). */
        @JavascriptInterface
        public String iaDispo() {
            StringBuilder sb = new StringBuilder();
            for (String id : ordreIA()) sb.append(sb.length() > 0 ? ", " : "").append(nomIA(id));
            return sb.toString();
        }

        @JavascriptInterface
        public void settings() {
            runOnUiThread(MainActivity.this::showSettings);
        }

        /** Renvoie l'adresse du PC joignable (Wi-Fi maison puis Tailscale), sinon "". */
        @JavascriptInterface
        public String findPc() {
            String ip = prefs.getString("ip", "");
            if (reachable(ip, PC_PORT, 1500)) {
                // à la maison : on retient l'adresse internet de la box (pour rallumer le PC depuis la 5G)
                new Thread(() -> {
                    String[] r = httpCall("GET", "https://api.ipify.org", null, "", 8000);
                    if ("200".equals(r[0]) && r[1].trim().matches("[0-9.]+")) {
                        prefs.edit().putString("public_ip", r[1].trim()).apply();
                    }
                }).start();
                return "http://" + ip + ":" + PC_PORT;
            }
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

        /** IA en ligne avec les clés rangées dans le téléphone (jamais exposées à la page).
         *  Essaie l'IA préférée, puis les autres si elle ne répond pas.
         *  Réponse unifiée : {"text": "...", "ia": "Grok"} */
        @JavascriptInterface
        public void claude(String id, String messagesJson, String system, int maxTokens) {
            new Thread(() -> {
                ArrayList<String> ordre = ordreIA();
                if (ordre.isEmpty()) { reply(id, "401", "clé IA manquante"); return; }
                String[] r = {"0", "aucune IA n'a répondu"};
                StringBuilder echecs = new StringBuilder();
                for (String ia : ordre) {
                    try {
                        r = appelIA(ia, new JSONArray(messagesJson), system, maxTokens);
                    } catch (Exception e) {
                        r = new String[]{"0", String.valueOf(e.getMessage())};
                    }
                    if ("200".equals(r[0])) {
                        try { r[1] = new JSONObject(r[1]).put("ia", nomIA(ia)).toString(); } catch (Exception ignored) { }
                        reply(id, "200", r[1]);
                        return;
                    }
                    echecs.append(echecs.length() > 0 ? " ; " : "").append(nomIA(ia)).append(" (").append(r[0]).append(")");
                }
                reply(id, r[0], "aucune IA n'a répondu : " + echecs);
            }).start();
        }

        /** Analyse d'une image (JPEG en base64) par l'IA du téléphone, quand le PC est éteint.
         *  Clé Google -> Gemini (gratuit) ; clé Anthropic -> Claude. Réponse : {"text": "..."} */
        @JavascriptInterface
        public void vision(String id, String imageB64, String question, String system, int maxTokens) {
            new Thread(() -> {
                String g = cle("gemini"), c = cle("claude");
                if (g.isEmpty() && c.isEmpty()) {
                    reply(id, "401", "pour les images, il faut une clé Gemini (gratuite) ou Claude");
                    return;
                }
                String[] r = {"0", ""};
                for (String ia : "claude".equals(prefs.getString("ia_pref", "")) ? new String[]{"claude", "gemini"}
                                                                                  : new String[]{"gemini", "claude"}) {
                    String k = cle(ia);
                    if (k.isEmpty()) continue;
                    try {
                        r = "claude".equals(ia) ? visionClaude(k, imageB64, question, system, maxTokens)
                                                : visionGemini(k, imageB64, question, system, maxTokens);
                    } catch (Exception e) {
                        r = new String[]{"0", String.valueOf(e.getMessage())};
                    }
                    if ("200".equals(r[0])) break;
                }
                reply(id, r[0], r[1]);
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

    /** Appelle une IA par son identifiant. Réponse : {"200", {"text": ...}} ou {code, erreur}. */
    private String[] appelIA(String ia, JSONArray msgs, String system, int maxTokens) throws Exception {
        String k = cle(ia);
        switch (ia) {
            case "gemini": return callGemini(k, msgs, system, maxTokens);
            case "claude": return callClaude(k, msgs, system, maxTokens);
            case "grok": return callCompatible("https://api.x.ai/v1", k, msgs, system, maxTokens,
                    new String[]{"grok-4-latest", "grok-4", "grok-3-latest", "grok-3-mini"}, "grok");
            case "deepseek": return callCompatible("https://api.deepseek.com", k, msgs, system, maxTokens,
                    new String[]{"deepseek-chat"}, "deepseek");
            case "groq": return callCompatible("https://api.groq.com/openai/v1", k, msgs, system, maxTokens,
                    new String[]{"llama-3.3-70b-versatile", "openai/gpt-oss-120b"}, "");
            default: return new String[]{"0", "IA inconnue"};
        }
    }

    /** IA au format « OpenAI » (Grok, DeepSeek, Groq). Si aucun modèle connu n'existe plus,
     *  on demande la liste des modèles à l'IA et on prend le premier qui convient. */
    private String[] callCompatible(String base, String key, JSONArray msgs, String system, int maxTokens,
                                    String[] modeles, String filtre) throws Exception {
        JSONArray m = new JSONArray();
        if (system != null && !system.isEmpty()) m.put(new JSONObject().put("role", "system").put("content", system));
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject x = msgs.getJSONObject(i);
            m.put(new JSONObject().put("role", "assistant".equals(x.optString("role")) ? "assistant" : "user")
                    .put("content", x.optString("content")));
        }
        JSONObject h = new JSONObject().put("content-type", "application/json").put("authorization", "Bearer " + key);
        ArrayList<String> essais = new ArrayList<>(java.util.Arrays.asList(modeles));
        String[] r = null;
        for (int i = 0; i < essais.size() && i < 6; i++) {
            JSONObject b = new JSONObject().put("model", essais.get(i)).put("messages", m)
                    .put("max_tokens", Math.max(maxTokens, 400)).put("temperature", 0.6);
            r = httpCall("POST", base + "/chat/completions", h, b.toString(), 60000);
            if ("200".equals(r[0])) {
                JSONObject msg = new JSONObject(r[1]).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                String txt = msg.optString("content", "").replaceAll("(?s)<think>.*?</think>", "").trim();
                return new String[]{"200", new JSONObject().put("text", txt).toString()};
            }
            // clé refusée, plus de crédit, trop de demandes : inutile d'essayer un autre modèle
            if ("401".equals(r[0]) || "402".equals(r[0]) || "403".equals(r[0]) || "429".equals(r[0])) break;
            if (i == essais.size() - 1 && !filtre.isEmpty()) {
                String[] l = httpCall("GET", base + "/models", h, "", 15000);
                if ("200".equals(l[0])) {
                    JSONArray d = new JSONObject(l[1]).optJSONArray("data");
                    for (int j = 0; d != null && j < d.length(); j++) {
                        String id = d.getJSONObject(j).optString("id");
                        if (id.contains(filtre) && !id.contains("image") && !id.contains("vision")
                                && !id.contains("embed") && !essais.contains(id)) essais.add(id);
                    }
                }
            }
        }
        return r;
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

    private String[] visionGemini(String key, String imageB64, String question, String system, int maxTokens)
            throws Exception {
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("inline_data", new JSONObject()
                        .put("mime_type", "image/jpeg").put("data", imageB64)))
                .put(new JSONObject().put("text", question));
        JSONObject b = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", new JSONObject().put("maxOutputTokens", Math.max(maxTokens, 800))
                        .put("temperature", 0.4));
        if (system != null && !system.isEmpty()) {
            b.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", system))));
        }
        JSONObject h = new JSONObject().put("content-type", "application/json").put("x-goog-api-key", key);
        String[] r = null;
        for (String model : new String[]{"gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash"}) {
            r = httpCall("POST", "https://generativelanguage.googleapis.com/v1beta/models/" + model
                    + ":generateContent", h, b.toString(), 90000);
            if ("200".equals(r[0])) {
                JSONArray out = new JSONObject(r[1]).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < out.length(); i++) {
                    if (!out.getJSONObject(i).optBoolean("thought")) sb.append(out.getJSONObject(i).optString("text"));
                }
                return new String[]{"200", new JSONObject().put("text", sb.toString().trim()).toString()};
            }
            if (!"404".equals(r[0])) break;
        }
        return r;
    }

    private String[] visionClaude(String key, String imageB64, String question, String system, int maxTokens)
            throws Exception {
        JSONArray content = new JSONArray()
                .put(new JSONObject().put("type", "image").put("source", new JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg").put("data", imageB64)))
                .put(new JSONObject().put("type", "text").put("text", question));
        JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
        return callClaude(key, msgs, system, maxTokens);
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
