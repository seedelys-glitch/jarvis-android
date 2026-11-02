package fr.jabran.jarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * « Eh Jarvis » sur le téléphone (01/11/2026).
 *
 * Le micro écoute en continu, mais TOUT reste dans le téléphone : deux petits modèles de reconnaissance
 * (sherpa-onnx, le même moteur que « l'oreille plus » du PC) cherchent le mot « Jarvis ».
 * - un modèle français (Kroko) qui écrit ce que tu dis, avec « Jarvis » favorisé : il comprend « Eh Jarvis »
 *   dit à la française et il écrit aussi la commande qui suit ;
 * - le petit détecteur anglais du PC (mêmes mots-clés, mêmes seuils) pour « Hey Jarvis » dit à l'anglaise.
 * Rien n'est envoyé nulle part tant que tu n'as pas dit « Jarvis ». Ensuite la commande part comme si tu
 * l'avais tapée dans l'appli : au PC s'il répond, sinon à l'IA du téléphone.
 *
 * Mêmes réglages que le PC : « Jarvis » seul = bip et 8 s pour la commande ; après la réponse, 12 s pour
 * enchaîner sans redire « Jarvis » ; « merci » / « c'est tout » = fin ; fin de phrase après 0,8 s de silence.
 * Batterie : on ne fait tourner les modèles que quand quelqu'un parle ; pause pendant les appels
 * téléphoniques et sous 15 % de batterie (sauf en charge).
 */
public class EcouteJarvis extends Service {

    static final String PREF = "reveil";            // true par défaut
    private static final String TAG = "JarvisReveil";
    private static final String CANAL = "jarvis_ecoute";
    private static final String CANAL_ALERTE = "jarvis_reveil";
    private static final String ARRETER = "fr.jabran.jarvis.ARRETER_ECOUTE";
    private static final int NOTIF = 4242, NOTIF_OUVRIR = 4243;
    private static final int RATE = 16000, BLOC = 1600;   // blocs de 100 ms

    // Mot-clés anglais : exactement ceux du PC (oreille_plus.py, MOTS_CLES)
    private static final String MOTS_CLES_EN =
            "▁JA R VI S :1.5 #0.15 @JARVIS\n"
            + "▁JA R VE ES :1.5 #0.15 @JARVEES\n"
            + "▁G AR VI S :1.5 #0.15 @GARVIS\n"
            + "▁CHA R VI S :1.5 #0.15 @CHARVIS\n"
            + "▁JA R VI S S :1.5 #0.15 @JARVISS\n"
            + "▁ Z H AR VE ES :1.5 #0.15 @ZHARVEES\n";
    // « Jarvis » favorisé dans le modèle français (réglé par essais : 3.0)
    private static final String MOTS_FAVORIS_FR =
            "▁ J ar vi s :3.0\n▁ J ar vi :3.0\n▁ G ar vi s :3.0\n▁ j ar vi s :3.0\n▁ J ar vi ce :3.0\n▁ j ar vi :3.0\n";

    private static final String[] FICHIERS = {
            "fr/encoder.onnx", "fr/decoder.onnx", "fr/joiner.onnx", "fr/tokens.txt",
            "en/encoder.int8.onnx", "en/decoder.int8.onnx", "en/joiner.int8.onnx", "en/tokens.txt"};

    private enum Etat { VEILLE, COMMANDE, ATTENTE, SUITE, APPRENDRE, GOOGLE }

    // ------------------------------------------------------------------ « Eh Jarvis » -> « Oui ? » -> Google écoute
    // (02/11, demande de Jabran : savoir quand il écoute + mieux comprendre). Le petit modèle du téléphone ne sert
    // qu'au mot « Jarvis » ; la commande dite après « Oui ? » est comprise par la reconnaissance de Google (bien
    // meilleure en français). Si elle n'existe pas ou tombe en panne : le petit modèle comme avant.
    private android.speech.tts.TextToSpeech voixService;
    private volatile boolean voixPrete = false;
    private volatile boolean googleOk = true;
    private volatile int googleFin = 0;          // 0 en cours, 1 compris, 2 rien entendu, 3 panne
    private volatile String resultatGoogle = null;
    private android.speech.SpeechRecognizer reco;
    private final android.os.Handler principal = new android.os.Handler(android.os.Looper.getMainLooper());

    private void preparerVoix() {
        try {
            voixService = new android.speech.tts.TextToSpeech(this, st -> {
                if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                    voixService.setLanguage(java.util.Locale.FRANCE);
                    voixPrete = true;
                }
            });
        } catch (Exception ignored) { }
    }

    /** Dit « Oui ? » puis laisse Google écouter la commande (le micro du réveil est coupé pendant ce temps). */
    private void ouiPuisGoogle() {
        googleFin = 0;
        resultatGoogle = null;
        principal.post(() -> {
            if (voixPrete) {
                voixService.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { principal.post(EcouteJarvis.this::demarrerGoogle); }
                    @Override public void onError(String id) { principal.post(EcouteJarvis.this::demarrerGoogle); }
                });
                if (voixService.speak("Oui ?", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "oui") != android.speech.tts.TextToSpeech.SUCCESS) demarrerGoogle();
            } else {
                demarrerGoogle();
            }
        });
    }

    private void demarrerGoogle() {
        try {
            if (reco != null) { reco.destroy(); reco = null; }
            reco = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            reco.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle b) { etatAffiche = "je t'écoute (dis ta commande)"; }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(android.os.Bundle b) { }
                @Override public void onEvent(int t, android.os.Bundle b) { }
                @Override public void onResults(android.os.Bundle b) {
                    java.util.ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    String t = r != null && !r.isEmpty() ? r.get(0) : "";
                    resultatGoogle = t;
                    googleFin = t.trim().isEmpty() ? 2 : 1;
                }
                @Override public void onError(int e) {
                    Log.i(TAG, "Google : erreur " + e);
                    googleFin = (e == android.speech.SpeechRecognizer.ERROR_NO_MATCH || e == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ? 2 : 3;
                }
            });
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                    .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            reco.startListening(i);
        } catch (Throwable e) {
            Log.w(TAG, "Google indisponible", e);
            googleFin = 3;
        }
    }

    private void arreterGoogle() {
        principal.post(() -> {
            try { if (reco != null) { reco.destroy(); reco = null; } } catch (Exception ignored) { }
        });
    }

    // ------------------------------------------------------------------ ta voix, tes mots, le journal
    private static final String PREF_VARIANTES = "reveil_variantes", PREF_CORRECTIONS = "corrections";
    private static final java.util.regex.Pattern APPRENDS = java.util.regex.Pattern.compile(
            "^(apprends|apprend|apprendre|retiens|ecoute) (ma voix|mon nom|comment je (te )?(dis|parle|t'appelle))|^entraine toi|^apprends a me comprendre");
    private static final java.util.regex.Pattern RECAP = java.util.regex.Pattern.compile(
            "^((fais|fait|fai|donne|dis)( moi)? )?(ton |le |mon )?recap( du jour| de la journee)?$|^comment s'est passee? (ta|la) journee$");
    private static final java.util.regex.Pattern OUBLIE_VOIX = java.util.regex.Pattern.compile("^oublie (ma voix|mes corrections|mes mots)$");
    private java.util.Map<String, String> corrections = new java.util.LinkedHashMap<>();
    private static File fichierJournal;
    private static final ArrayDeque<String> JOURNAL = new ArrayDeque<>();

    /** Journal de la discussion à la voix (reste dans le téléphone ; « Envoyer mon journal » dans les réglages). */
    static void journal(String ligne) {
        if (ligne == null || ligne.trim().isEmpty()) return;
        String l = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE).format(new java.util.Date())
                + "  " + ligne.trim();
        synchronized (JOURNAL) {
            JOURNAL.addLast(l);
            while (JOURNAL.size() > 300) JOURNAL.removeFirst();
            if (fichierJournal != null) {
                try { java.nio.file.Files.write(fichierJournal.toPath(), String.join("\n", JOURNAL).getBytes("UTF-8")); }
                catch (Exception ignored) { }
            }
        }
    }

    static String journalTexte(Context c) {
        File f = new File(c.getFilesDir(), "journal-voix.txt");
        try { return f.isFile() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8") : ""; }
        catch (Exception e) { return ""; }
    }

    private void chargeMaVoix() {
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        ReveilTexte.reglerPerso(p.getStringSet(PREF_VARIANTES, new java.util.HashSet<>()));
        corrections = new java.util.LinkedHashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(p.getString(PREF_CORRECTIONS, "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); corrections.put(k, o.getString(k)); }
        } catch (Exception ignored) { }
    }

    /**
     * Récap du jour + amélioration toute seule (Ameliore.java) : lu dans le journal vocal. Les nouvelles façons de
     * dire « Eh Jarvis » trouvées sont ajoutées tout de suite. Fait chaque soir à 22 h (notification) ou à la voix.
     */
    private String faireRecap() {
        String jour = new java.text.SimpleDateFormat("dd/MM", java.util.Locale.FRANCE).format(new java.util.Date());
        java.util.List<String> lignes;
        synchronized (JOURNAL) { lignes = new java.util.ArrayList<>(JOURNAL); }
        SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
        java.util.Set<String> connues = new java.util.LinkedHashSet<>(p.getStringSet(PREF_VARIANTES, new java.util.HashSet<>()));
        Ameliore.Recap r = Ameliore.analyser(Ameliore.duJour(lignes, jour), connues);
        if (!r.nouvellesVariantes.isEmpty()) {
            connues.addAll(r.nouvellesVariantes);
            while (connues.size() > 25) connues.remove(connues.iterator().next());
            p.edit().putStringSet(PREF_VARIANTES, new java.util.HashSet<>(connues)).apply();
            ReveilTexte.reglerPerso(connues);
        }
        p.edit().putString("recap_jour", jour).apply();
        Log.i(TAG, "récap : " + r.texte);
        journal("récap : " + r.texte);
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
            PendingIntent pi = PendingIntent.getActivity(this, 5, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = b.setSmallIcon(R.drawable.ic_jarvis).setContentTitle("Récap du jour de Jarvis")
                    .setContentText(r.texte).setStyle(new Notification.BigTextStyle().bigText(r.texte))
                    .setContentIntent(pi).setAutoCancel(true).build();
            getSystemService(NotificationManager.class).notify(4244, n);
        } catch (Exception ignored) { }
        return r.texte;
    }

    private void gardeCorrections() {
        getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                .putString(PREF_CORRECTIONS, new org.json.JSONObject(corrections).toString()).apply();
    }

    // ------------------------------------------------------------------ appelé par l'appli
    private static volatile EcouteJarvis vivant;
    private static volatile boolean microAppli = false;   // le bouton micro de l'appli (Google) écoute

    static boolean active(Context c) {
        return c.getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean(PREF, true);
    }

    /** Démarre l'écoute (à appeler quand l'appli est devant : Android l'exige pour le micro). */
    static void demarrer(Context c) {
        if (!active(c) || vivant != null) return;
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Intent i = new Intent(c, EcouteJarvis.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "démarrage impossible", e);
        }
    }

    static void arreter(Context c) {
        c.stopService(new Intent(c, EcouteJarvis.class));
    }

    static boolean enMarche() { return vivant != null; }

    // Ce que l'écoute fait et entend : montré dans l'appli (petite ligne au-dessus de la case de texte)
    static volatile String etatAffiche = "je démarre…";
    static volatile String dernierEntendu = "";
    static volatile long reveilA = 0;

    static String etatJson() {
        try {
            return new org.json.JSONObject()
                    .put("actif", vivant != null)
                    .put("etat", etatAffiche)
                    .put("entendu", dernierEntendu)
                    .put("reveil", System.currentTimeMillis() - reveilA < 4000)
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Le bouton micro de l'appli prend le micro : on le lui laisse. */
    static void microAppli(boolean oui) { microAppli = oui; }

    /** L'appli a fini de dire sa réponse : 12 s pour enchaîner sans redire « Jarvis ». */
    static void finDuTour() {
        EcouteJarvis e = vivant;
        if (e != null) e.tourFini = true;
    }

    // ------------------------------------------------------------------ service
    private volatile boolean marche = false;
    private volatile boolean tourFini = false;
    private volatile boolean apprendreApres = false;
    private Thread fil;
    private PowerManager.WakeLock verrou;
    private ToneGenerator bip;
    private String texteNotif = "";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        canaux();
        try {
            Notification n = notification("Je me prépare… (la première fois : quelques secondes)");
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIF, n);
        } catch (Exception e) {
            // Android refuse le micro si l'appli n'est pas devant (relance automatique) : on s'arrête proprement
            Log.w(TAG, "micro refusé en arrière-plan", e);
            stopSelf();
            return;
        }
        vivant = this;
        preparerVoix();
        try { bip = new ToneGenerator(AudioManager.STREAM_MUSIC, 80); } catch (Exception ignored) { }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        verrou = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:ecoute");
        verrou.setReferenceCounted(false);
        verrou.acquire();
        marche = true;
        fil = new Thread(this::boucle, "jarvis-reveil");
        fil.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ARRETER.equals(intent.getAction())) {
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean(PREF, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        marche = false;
        if (vivant == this) vivant = null;
        if (fil != null) fil.interrupt();
        if (verrou != null && verrou.isHeld()) verrou.release();
        if (bip != null) bip.release();
        arreterGoogle();
        if (voixService != null) voixService.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ notifications
    private void canaux() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel c = new NotificationChannel(CANAL, "Jarvis t'écoute (« Eh Jarvis »)",
                NotificationManager.IMPORTANCE_LOW);
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
        NotificationChannel a = new NotificationChannel(CANAL_ALERTE, "Jarvis t'a entendu",
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(a);
    }

    private Notification notification(String texte) {
        texteNotif = texte;
        Intent ouvrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent po = PendingIntent.getActivity(this, 1, ouvrir, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pa = PendingIntent.getService(this, 2, new Intent(this, EcouteJarvis.class).setAction(ARRETER),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("Jarvis t'écoute")
                .setContentText(texte)
                .setOngoing(true)
                .setContentIntent(po)
                .addAction(new Notification.Action.Builder(null, "Arrêter d'écouter", pa).build())
                .build();
    }

    private void majNotif(String texte) {
        etatAffiche = texte.startsWith("Dis ") ? "j'écoute" : texte;
        if (texte.equals(texteNotif)) return;
        try { getSystemService(NotificationManager.class).notify(NOTIF, notification(texte)); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ modèles
    /** Les modèles sont dans l'appli ; on les recopie une fois dans le téléphone (après chaque mise à jour). */
    private File installeModeles() throws Exception {
        File dir = new File(getFilesDir(), "reveil");
        File marque = new File(dir, "version.txt");
        String version = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        boolean ok = marque.isFile() && version.equals(lire(marque));
        if (!ok) {
            for (String f : FICHIERS) {
                File out = new File(dir, f);
                out.getParentFile().mkdirs();
                try (InputStream in = getAssets().open("reveil/" + f); OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
            ecrire(marque, version);
        }
        ecrire(new File(dir, "en/mots-cles.txt"), MOTS_CLES_EN);
        ecrire(new File(dir, "fr/favoris.txt"), MOTS_FAVORIS_FR);
        return dir;
    }

    private static String lire(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void ecrire(File f, String s) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    private OnlineRecognizer francais(File d) {
        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(RATE);
        feat.setFeatureDim(80);
        OnlineTransducerModelConfig t = new OnlineTransducerModelConfig();
        t.setEncoder(new File(d, "fr/encoder.onnx").getPath());
        t.setDecoder(new File(d, "fr/decoder.onnx").getPath());
        t.setJoiner(new File(d, "fr/joiner.onnx").getPath());
        OnlineModelConfig m = new OnlineModelConfig();
        m.setTransducer(t);
        m.setTokens(new File(d, "fr/tokens.txt").getPath());
        m.setModelingUnit("cjkchar");   // obligatoire avec les mots favoris (sinon plantage net, essai du 01/11)
        m.setNumThreads(1);
        m.setProvider("cpu");
        OnlineRecognizerConfig c = new OnlineRecognizerConfig();
        c.setFeatConfig(feat);
        c.setModelConfig(m);
        c.setEnableEndpoint(true);
        c.setEndpointConfig(new EndpointConfig(
                new EndpointRule(false, 2.4f, 0f),                         // rien dit : on coupe après 2,4 s
                new EndpointRule(true, ReveilTexte.FIN_PHRASE_S, 0f),      // fin de phrase : 0,8 s de silence
                new EndpointRule(false, 0f, ReveilTexte.PHRASE_MAX_S)));   // 15 s maximum
        c.setDecodingMethod("modified_beam_search");
        c.setMaxActivePaths(4);
        c.setHotwordsFile(new File(d, "fr/favoris.txt").getPath());
        c.setHotwordsScore(3.0f);
        return new OnlineRecognizer(null, c);
    }

    private KeywordSpotter anglais(File d) {
        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(RATE);
        feat.setFeatureDim(80);
        OnlineTransducerModelConfig t = new OnlineTransducerModelConfig();
        t.setEncoder(new File(d, "en/encoder.int8.onnx").getPath());
        t.setDecoder(new File(d, "en/decoder.int8.onnx").getPath());
        t.setJoiner(new File(d, "en/joiner.int8.onnx").getPath());
        OnlineModelConfig m = new OnlineModelConfig();
        m.setTransducer(t);
        m.setTokens(new File(d, "en/tokens.txt").getPath());
        m.setNumThreads(1);
        m.setProvider("cpu");
        KeywordSpotterConfig c = new KeywordSpotterConfig();
        c.setFeatConfig(feat);
        c.setModelConfig(m);
        c.setKeywordsFile(new File(d, "en/mots-cles.txt").getPath());
        c.setKeywordsThreshold(0.15f);   // comme le PC
        c.setKeywordsScore(2.0f);
        return new KeywordSpotter(null, c);
    }

    // ------------------------------------------------------------------ la boucle d'écoute
    private void boucle() {
        OnlineRecognizer asr;
        KeywordSpotter kws = null;
        try {
            File d = installeModeles();
            asr = francais(d);
            try { kws = anglais(d); } catch (Exception e) { Log.w(TAG, "détecteur anglais absent", e); }
        } catch (Throwable e) {
            Log.e(TAG, "modèles impossibles à charger", e);
            majNotif("Le détecteur « Jarvis » n'a pas pu démarrer : " + e.getMessage());
            return;
        }
        Log.i(TAG, "modèles chargés (anglais : " + (kws != null) + ")");
        fichierJournal = new File(getFilesDir(), "journal-voix.txt");
        if (JOURNAL.isEmpty()) {
            String ancien = journalTexte(this);
            if (!ancien.isEmpty()) for (String l : ancien.split("\n")) JOURNAL.addLast(l);
        }
        chargeMaVoix();
        java.util.Set<String> apprises = new java.util.LinkedHashSet<>();
        int compteApprises = 0;
        OnlineStream sFr = asr.createStream("");
        OnlineStream sEn = kws != null ? kws.createStream("") : null;

        AudioRecord rec = null;
        short[] pcm = new short[BLOC];
        ArrayDeque<float[]> avant = new ArrayDeque<>();   // 0,6 s gardées pour ne pas couper le début du mot
        double fond = 300;                                 // bruit de fond (s'adapte tout seul)
        long dernierSon = 0, verifBatterie = 0;
        boolean ouvert = false, pause = false;
        Etat etat = Etat.VEILLE;
        boolean premierePhrase = false;   // la phrase qui contient « Jarvis »
        boolean ouiDit = false;           // « Oui ? » déjà dit pour ce réveil
        String injecte = null;            // commande comprise par Google
        long depuis = 0;
        String raisonPause = "";

        majNotif("Dis « Eh Jarvis » pour me parler");
        while (marche) {
            long now = System.currentTimeMillis();
            // --- pauses : appel téléphonique, micro de l'appli, batterie faible
            if (now - verifBatterie > 60_000) {
                verifBatterie = now;
                raisonPause = batterieFaible() ? "batterie faible : je reprends dès que tu branches le téléphone" : "";
                java.util.Calendar cal = java.util.Calendar.getInstance();
                String jour = new java.text.SimpleDateFormat("dd/MM", java.util.Locale.FRANCE).format(cal.getTime());
                if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= 22 && etat == Etat.VEILLE
                        && !jour.equals(getSharedPreferences("jarvis", MODE_PRIVATE).getString("recap_jour", ""))) {
                    try { faireRecap(); } catch (Exception e) { Log.w(TAG, "récap impossible", e); }
                }
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int mode = am.getMode();
            boolean enAppel = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_RINGTONE;
            boolean doitPauser = microAppli || enAppel || !raisonPause.isEmpty() || etat == Etat.ATTENTE || etat == Etat.GOOGLE;
            if (doitPauser) {
                if (rec != null) { arreteMicro(rec); rec = null; }
                if (!pause) {
                    pause = true;
                    if (enAppel) majNotif("En pause pendant ton appel");
                    else if (!raisonPause.isEmpty()) majNotif("En pause (" + raisonPause + ")");
                }
                if (etat == Etat.GOOGLE) {
                    int f = googleFin;
                    if (f == 1) {
                        Log.i(TAG, "Google a compris : " + resultatGoogle);
                        injecte = resultatGoogle;
                        etat = Etat.COMMANDE;
                        depuis = now;
                    } else if (f == 2 || (f == 0 && now - depuis > 20_000)) {
                        if (f == 0) arreterGoogle();
                        Log.i(TAG, "Google : rien entendu, je me rendors");
                        bip(false);
                        etat = Etat.VEILLE;
                        etatAffiche = "j'écoute";
                        ouvert = false;
                        asr.reset(sFr);
                        finConversation();
                    } else if (f == 3) {
                        googleOk = false;   // pas de Google sur ce téléphone : le petit modèle écoute la commande
                        Log.i(TAG, "Google en panne : le petit modèle écoute la commande");
                        etat = Etat.COMMANDE;
                        depuis = now;
                    }
                    dors(100);
                    continue;
                }
                if (etat == Etat.ATTENTE) {
                    if (tourFini && apprendreApres) {
                        tourFini = false;
                        apprendreApres = false;
                        etat = Etat.APPRENDRE;
                        etatAffiche = "j'apprends ta voix : dis « Eh Jarvis »";
                        apprises.clear();
                        compteApprises = 0;
                        depuis = System.currentTimeMillis();
                        asr.reset(sFr);
                        Log.i(TAG, "apprentissage : j'écoute tes « Eh Jarvis »");
                        continue;
                    }
                    if (tourFini) {
                        tourFini = false;
                        etat = Etat.SUITE;
                        Log.i(TAG, "réponse dite : 12 s pour enchaîner sans redire « Jarvis »");
                        depuis = System.currentTimeMillis();
                        asr.reset(sFr);
                        continue;
                    }
                    if (now - depuis > 90_000) { etat = Etat.VEILLE; finConversation(); continue; }
                }
                dors(150);
                continue;
            }
            if (pause) {
                pause = false;
                majNotif("Dis « Eh Jarvis » pour me parler");
            }
            if (rec == null) {
                rec = ouvreMicro();
                if (rec == null) { dors(2000); continue; }
                avant.clear();
                ouvert = false;
            }
            int n;
            if (essai == null) essai = lireEssai();
            if (essai != null) {
                // Essai automatique : un enregistrement remplace le micro (voir tests/essai-emulateur.sh)
                n = Math.min(BLOC, essai.length - essaiPos);
                System.arraycopy(essai, essaiPos, pcm, 0, n);
                essaiPos += n;
                rec.read(new short[BLOC], 0, BLOC);   // garde le rythme du vrai micro
                if (essaiPos >= essai.length) { essai = null; essaiPos = 0; Log.i(TAG, "essai : fin de l'enregistrement"); }
            } else {
                n = rec.read(pcm, 0, BLOC);
            }
            if (n <= 0) { dors(50); continue; }
            float[] x = new float[n];
            double e = 0;
            for (int i = 0; i < n; i++) { x[i] = pcm[i] / 32768f; e += (double) pcm[i] * pcm[i]; }
            double rms = Math.sqrt(e / n);
            fond = rms < fond ? fond * 0.9 + rms * 0.1 : fond * 0.998 + rms * 0.002;
            boolean fort = rms > Math.max(fond * 1.8, 100);
            now = System.currentTimeMillis();
            if (fort) dernierSon = now;

            if (etat == Etat.VEILLE) {
                // Batterie : les modèles ne tournent que si quelqu'un parle (et 2 s après)
                if (!ouvert) {
                    avant.addLast(x);
                    while (avant.size() > 6) avant.removeFirst();
                    if (!fort) continue;
                    ouvert = true;
                    for (float[] b : avant) { sFr.acceptWaveform(b, RATE); if (sEn != null) sEn.acceptWaveform(b, RATE); }
                    avant.clear();
                } else {
                    sFr.acceptWaveform(x, RATE);
                    if (sEn != null) sEn.acceptWaveform(x, RATE);
                }
                while (asr.isReady(sFr)) asr.decode(sFr);
                boolean entendu = ReveilTexte.contientReveil(asr.getResult(sFr).getText());
                if (sEn != null) {
                    while (kws.isReady(sEn)) {
                        kws.decode(sEn);
                        if (!kws.getResult(sEn).getKeyword().isEmpty()) { entendu = true; kws.reset(sEn); }
                    }
                }
                if (entendu) {
                    Log.i(TAG, "« Jarvis » entendu : " + asr.getResult(sFr).getText());
                    journal("réveil sur : « " + asr.getResult(sFr).getText().trim() + " »");
                    noteEntendu(asr.getResult(sFr).getText());
                    reveilA = System.currentTimeMillis();
                    etatAffiche = "je t'écoute, dis ta commande";
                    bip(true);
                    etat = Etat.COMMANDE;
                    premierePhrase = true;
                    ouiDit = false;
                    depuis = now;
                    continue;
                }
                if (asr.isEndpoint(sFr)) { pasReveille(asr.getResult(sFr).getText()); asr.reset(sFr); }
                if (now - dernierSon > 2000) {   // plus personne ne parle : on se rendort
                    pasReveille(asr.getResult(sFr).getText());
                    ouvert = false;
                    asr.reset(sFr);
                    if (sEn != null) { sEn.release(); sEn = kws.createStream(""); }
                }
                continue;
            }

            if (etat == Etat.APPRENDRE) {
                sFr.acceptWaveform(x, RATE);
                while (asr.isReady(sFr)) asr.decode(sFr);
                if (asr.isEndpoint(sFr)) {
                    String b = asr.getResult(sFr).getText();
                    asr.reset(sFr);
                    if (!b.trim().isEmpty()) {
                        compteApprises++;
                        String v = ReveilTexte.varianteApprise(b);
                        Log.i(TAG, "apprentissage " + compteApprises + " : " + b + (v.isEmpty() ? " (pas gardé)" : ""));
                        journal("apprentissage : « " + b.trim() + " »");
                        noteEntendu(b);
                        if (!v.isEmpty()) apprises.add(v);
                        bip(true);
                    }
                }
                if (compteApprises >= 5 || now - depuis > 45_000) {
                    SharedPreferences p = getSharedPreferences("jarvis", MODE_PRIVATE);
                    java.util.LinkedHashSet<String> toutes = new java.util.LinkedHashSet<>(p.getStringSet(PREF_VARIANTES, new java.util.HashSet<>()));
                    toutes.addAll(apprises);
                    while (toutes.size() > 25) toutes.remove(toutes.iterator().next());
                    p.edit().putStringSet(PREF_VARIANTES, new java.util.HashSet<>(toutes)).apply();
                    ReveilTexte.reglerPerso(toutes);
                    String fin = compteApprises == 0 ? "Je ne t'ai pas entendu. Redis « Jarvis, apprends ma voix » quand tu veux."
                            : "Merci, c'est appris. J'ai retenu " + apprises.size() + " façon" + (apprises.size() > 1 ? "s" : "")
                            + " dont tu dis « Eh Jarvis ».";
                    Log.i(TAG, "apprentissage fini : " + apprises);
                    parler(fin);
                    etat = Etat.ATTENTE;
                    depuis = now;
                }
                continue;
            }

            // COMMANDE (après « Jarvis ») ou SUITE (12 s après la réponse) : on écoute tout
            sFr.acceptWaveform(x, RATE);
            while (asr.isReady(sFr)) asr.decode(sFr);
            final boolean deGoogle = injecte != null;
            String brut = deGoogle ? injecte : asr.getResult(sFr).getText();
            injecte = null;
            if (deGoogle || asr.isEndpoint(sFr)) {
                asr.reset(sFr);
                // phrase du réveil : ce qui suit « Jarvis » ; phrase suivante : tout (sans « Jarvis » devant)
                String cmd = premierePhrase ? ReveilTexte.apresReveil(brut) : ReveilTexte.enleveReveil(brut);
                if (!brut.trim().isEmpty()) premierePhrase = false;
                if (!cmd.isEmpty() && !corrections.isEmpty()) cmd = ReveilTexte.corriger(cmd, corrections);
                if (!cmd.isEmpty()) {
                    String c0 = cmd.replaceAll("[.!?]+$", "").trim();
                    String reponse = null;
                    boolean apprendre = false, partir = false;
                    String[] cor = ReveilTexte.lireCorrection(c0);
                    if (ReveilTexte.estVenir(c0)) {
                        montrer();
                        reponse = "Oui, je suis là.";
                    } else if (ReveilTexte.estPartir(c0)) {
                        partir = true;
                    } else if (APPRENDS.matcher(c0).find()) {
                        montrer();
                        apprendre = true;
                        reponse = "D'accord. Dis « Eh Jarvis » cinq fois, comme d'habitude, avec une petite pause entre chaque. Je fais un bip à chaque fois. Vas-y.";
                    } else if (cor != null) {
                        corrections.put(cor[0], cor[1]);
                        gardeCorrections();
                        reponse = "D'accord. Quand j'entends « " + cor[0] + " », je comprendrai « " + cor[1] + " ».";
                    } else if (RECAP.matcher(c0).find()) {
                        reponse = faireRecap();
                    } else if (OUBLIE_VOIX.matcher(c0).find()) {
                        getSharedPreferences("jarvis", MODE_PRIVATE).edit().remove(PREF_VARIANTES).remove(PREF_CORRECTIONS).apply();
                        chargeMaVoix();
                        reponse = "C'est oublié. Je repars de zéro pour ta voix et tes mots.";
                    }
                    if (partir) {
                        Log.i(TAG, "fin de la discussion (tu peux partir) : " + cmd);
                        journal("toi : " + cmd);
                        journal("Jarvis : À plus tard. (l'appli se remet derrière)");
                        noteEntendu(brut);
                        dire("À plus tard.");
                        etat = Etat.VEILLE;
                        etatAffiche = "j'écoute";
                        partir();
                        continue;
                    }
                    if (reponse != null) {
                        Log.i(TAG, "commande du téléphone : " + cmd);
                        journal("toi : " + cmd);
                        noteEntendu(brut);
                        apprendreApres = apprendre;
                        parler(reponse);
                        etat = Etat.ATTENTE;
                        depuis = now;
                        continue;
                    }
                }
                if (!cmd.isEmpty() && !deGoogle && ReveilTexte.tropCourte(cmd)) {
                    noteEntendu(brut);
                    cmd = "";   // un mot tout seul (« sont ») : ce n'est pas une commande, j'attends la suite
                    depuis = now;
                }
                if (!cmd.isEmpty()) {
                    if (ReveilTexte.estArret(cmd)) {
                        dire("D'accord, je n'écoute plus. Ouvre l'appli et coche « Eh Jarvis » dans les réglages pour me réveiller à nouveau.");
                        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean(PREF, false).apply();
                        finConversation();
                        stopSelf();
                        return;
                    }
                    if (etat == Etat.SUITE && ReveilTexte.estFin(cmd)) {
                        Log.i(TAG, "fin de la discussion : " + cmd);
                        dire("Avec plaisir.");
                        etat = Etat.VEILLE;
                        finConversation();
                        continue;
                    }
                    Log.i(TAG, "commande : " + cmd);
                    journal("toi : " + cmd + (brut.trim().equalsIgnoreCase(cmd) ? "" : "   (entendu : « " + brut.trim() + " »)"));
                    noteEntendu(brut);
                    etatAffiche = "commande envoyée";
                    envoyer(cmd);
                    etat = Etat.ATTENTE;
                    depuis = System.currentTimeMillis();
                    tourFini = false;
                    continue;
                }
                if (!brut.trim().isEmpty() && etat == Etat.COMMANDE) {   // « Jarvis » seul : « Oui ? » et j'attends la suite
                    depuis = now;
                    if (!ouiDit) {
                        ouiDit = true;
                        journal("Jarvis : Oui ?");
                        if (essai == null && googleOk && android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
                            etat = Etat.GOOGLE;
                            etatAffiche = "oui ? (dis ta commande)";
                            ouiPuisGoogle();
                            continue;
                        }
                        principal.post(() -> { if (voixPrete) voixService.speak("Oui ?", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "oui"); });
                    }
                }
            }
            long delai = etat == Etat.COMMANDE ? ReveilTexte.ATTENTE_COMMANDE_MS : ReveilTexte.SUITE_MS;
            if (now - depuis > delai && brut.trim().isEmpty() && now - dernierSon > 1000) {
                if (etat == Etat.COMMANDE) bip(false);
                etat = Etat.VEILLE;
                etatAffiche = "j'écoute";
                ouvert = false;
                asr.reset(sFr);
                finConversation();
            }
        }
        if (rec != null) arreteMicro(rec);
    }

    private short[] essai = null;
    private int essaiPos = 0;

    /** Fichier d'essai déposé dans l'appli (seulement par un essai automatique, jamais par une autre appli). */
    private short[] lireEssai() {
        File f = new File(getFilesDir(), "essai-audio.wav");
        if (!f.isFile()) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            f.delete();
            int n = (b.length - 44) / 2;
            short[] x = new short[n];
            java.nio.ByteBuffer.wrap(b, 44, n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(x);
            Log.i(TAG, "essai : enregistrement de " + (n / RATE) + " s");
            return x;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    /** Phrase courte entendue sans réveil : peut-être un « Eh Jarvis » raté (gardé dans le journal pour régler). */
    private static void pasReveille(String t) {
        noteEntendu(t);
        if (t != null && !t.trim().isEmpty() && t.trim().split("\\s+").length <= 5) journal("pas réveillé : « " + t.trim() + " »");
    }

    private static void noteEntendu(String t) {
        if (t != null && !t.trim().isEmpty() && !t.trim().equals(dernierEntendu)) Log.i(TAG, "entendu : " + t.trim());
        if (t != null && !t.trim().isEmpty()) dernierEntendu = t.trim();
    }

    private AudioRecord ouvreMicro() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, BLOC * 2 * 4));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return null; }
            r.startRecording();
            return r;
        } catch (SecurityException e) {
            majNotif("Il me faut le droit d'utiliser le micro (ouvre l'appli)");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void arreteMicro(AudioRecord r) {
        try { r.stop(); } catch (Exception ignored) { }
        r.release();
    }

    private boolean batterieFaible() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int niveau = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100), max = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charge = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        return !charge && max > 0 && niveau * 100 / max < 15;
    }

    private static void dors(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void bip(boolean reveil) {
        try {
            if (bip != null) bip.startTone(reveil ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_NACK, 150);
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && reveil) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, 120)); else v.vibrate(40);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ vers l'appli
    private void dire(String texte) {
        MainActivity a = MainActivity.vivante();
        if (a != null) a.direVoix(texte);
    }

    /** Dit une phrase puis l'écoute reprend (12 s pour enchaîner) quand elle est finie. */
    private void parler(String texte) {
        journal("Jarvis : " + texte);
        MainActivity a = MainActivity.vivante();
        if (a != null) { tourFini = false; a.direPuisEcoute(texte); }
        else tourFini = true;
    }

    /** « Jarvis, viens » : l'appli passe devant (par-dessus l'écran verrouillé si besoin). */
    private void montrer() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_VENIR, true);
        Context lanceur = MainsLibres.actif != null ? MainsLibres.actif : this;
        try { lanceur.startActivity(i); } catch (Exception e) { Log.w(TAG, "impossible de passer devant", e); }
        for (int k = 0; k < 30 && MainActivity.vivante() == null; k++) dors(100);
    }

    /** « C'est bon, tu peux partir » : l'appli se remet derrière. */
    private void partir() {
        MainActivity a = MainActivity.vivante();
        if (a != null) a.partir();
    }

    private void finConversation() {
        MainActivity a = MainActivity.vivante();
        if (a != null) a.finConversationVocale();
    }

    /** La commande part dans l'appli (qui reste derrière ton GPS si elle est déjà ouverte). */
    private void envoyer(String cmd) {
        MainActivity a = MainActivity.vivante();
        if (a != null) { a.commandeVocale(cmd); return; }
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_VOIX, cmd);
        boolean peutOuvrir = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this);
        if (peutOuvrir) {
            try { startActivity(i); return; } catch (Exception ignored) { }
        }
        // Android interdit d'ouvrir l'appli toute seule : une notification à toucher
        PendingIntent pi = PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL_ALERTE)
                : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("Jarvis t'a entendu")
                .setContentText("« " + cmd + " » : touche pour la réponse")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build();
        try { getSystemService(NotificationManager.class).notify(NOTIF_OUVRIR, n); } catch (Exception ignored) { }
        tourFini = true;
    }
}
