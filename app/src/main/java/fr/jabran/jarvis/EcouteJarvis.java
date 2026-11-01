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
            "▁ J ar vi s :3.0\n▁ J ar vi :3.0\n▁ G ar vi s :3.0\n▁ j ar vi s :3.0\n▁ J ar vi ce :3.0\n";

    private static final String[] FICHIERS = {
            "fr/encoder.onnx", "fr/decoder.onnx", "fr/joiner.onnx", "fr/tokens.txt",
            "en/encoder.int8.onnx", "en/decoder.int8.onnx", "en/joiner.int8.onnx", "en/tokens.txt"};

    private enum Etat { VEILLE, COMMANDE, ATTENTE, SUITE }

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
        long depuis = 0;
        String raisonPause = "";

        majNotif("Dis « Eh Jarvis » pour me parler");
        while (marche) {
            long now = System.currentTimeMillis();
            // --- pauses : appel téléphonique, micro de l'appli, batterie faible
            if (now - verifBatterie > 60_000) {
                verifBatterie = now;
                raisonPause = batterieFaible() ? "batterie faible : je reprends dès que tu branches le téléphone" : "";
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int mode = am.getMode();
            boolean enAppel = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_RINGTONE;
            boolean doitPauser = microAppli || enAppel || !raisonPause.isEmpty() || etat == Etat.ATTENTE;
            if (doitPauser) {
                if (rec != null) { arreteMicro(rec); rec = null; }
                if (!pause) {
                    pause = true;
                    if (enAppel) majNotif("En pause pendant ton appel");
                    else if (!raisonPause.isEmpty()) majNotif("En pause (" + raisonPause + ")");
                }
                if (etat == Etat.ATTENTE) {
                    if (tourFini) {
                        tourFini = false;
                        etat = Etat.SUITE;
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
            int n = rec.read(pcm, 0, BLOC);
            if (n <= 0) { dors(50); continue; }
            float[] x = new float[n];
            double e = 0;
            for (int i = 0; i < n; i++) { x[i] = pcm[i] / 32768f; e += (double) pcm[i] * pcm[i]; }
            double rms = Math.sqrt(e / n);
            fond = rms < fond ? fond * 0.9 + rms * 0.1 : fond * 0.998 + rms * 0.002;
            boolean fort = rms > Math.max(fond * 2.2, 150);
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
                    bip(true);
                    etat = Etat.COMMANDE;
                    premierePhrase = true;
                    depuis = now;
                    continue;
                }
                if (asr.isEndpoint(sFr)) asr.reset(sFr);
                if (now - dernierSon > 2000) {   // plus personne ne parle : on se rendort
                    ouvert = false;
                    asr.reset(sFr);
                    if (sEn != null) { sEn.release(); sEn = kws.createStream(""); }
                }
                continue;
            }

            // COMMANDE (après « Jarvis ») ou SUITE (12 s après la réponse) : on écoute tout
            sFr.acceptWaveform(x, RATE);
            while (asr.isReady(sFr)) asr.decode(sFr);
            String brut = asr.getResult(sFr).getText();
            if (asr.isEndpoint(sFr)) {
                asr.reset(sFr);
                // phrase du réveil : ce qui suit « Jarvis » ; phrase suivante : tout (sans « Jarvis » devant)
                String cmd = premierePhrase ? ReveilTexte.apresReveil(brut) : ReveilTexte.enleveReveil(brut);
                if (!brut.trim().isEmpty()) premierePhrase = false;
                if (!cmd.isEmpty()) {
                    if (ReveilTexte.estArret(cmd)) {
                        dire("D'accord, je n'écoute plus. Ouvre l'appli et coche « Eh Jarvis » dans les réglages pour me réveiller à nouveau.");
                        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean(PREF, false).apply();
                        finConversation();
                        stopSelf();
                        return;
                    }
                    if (etat == Etat.SUITE && ReveilTexte.estFin(cmd)) {
                        dire("Avec plaisir.");
                        etat = Etat.VEILLE;
                        finConversation();
                        continue;
                    }
                    Log.i(TAG, "commande : " + cmd);
                    envoyer(cmd);
                    etat = Etat.ATTENTE;
                    depuis = System.currentTimeMillis();
                    tourFini = false;
                    continue;
                }
                if (!brut.trim().isEmpty() && etat == Etat.COMMANDE) depuis = now;   // « Jarvis » seul : j'attends la suite
            }
            long delai = etat == Etat.COMMANDE ? ReveilTexte.ATTENTE_COMMANDE_MS : ReveilTexte.SUITE_MS;
            if (now - depuis > delai && brut.trim().isEmpty() && now - dernierSon > 1000) {
                if (etat == Etat.COMMANDE) bip(false);
                etat = Etat.VEILLE;
                ouvert = false;
                asr.reset(sFr);
                finConversation();
            }
        }
        if (rec != null) arreteMicro(rec);
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
