package fr.jabran.jarvis;

import android.app.Notification;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Alertes TradingView lues à voix haute sur le téléphone (31/10/2026).
 *
 * Quand l'appli TradingView du téléphone affiche une alerte (avec la note que Jabran a écrite dans
 * l'alerte), Jarvis la dit à voix haute : « Alerte TradingView : MGC1! croise 4000. Note : ... ».
 * Marche même si le PC est éteint et même si l'appli Jarvis est fermée : Android prévient ce service
 * à chaque nouvelle notification. On ne lit QUE les notifications de TradingView, rien d'autre,
 * et rien ne sort du téléphone.
 *
 * À faire une fois : Réglages de Jarvis > « Lire les alertes TradingView » > autoriser l'accès
 * aux notifications pour Jarvis.
 */
public class AlertesTradingView extends NotificationListenerService {

    static final String PREF = "alertes_tv";          // true par défaut
    private static final long MEME_TEXTE_MS = 90_000;  // même alerte affichée 2 fois : dite une seule fois
    private static final int MAX_CARACTERES = 300;

    private TextToSpeech tts;
    private boolean ttsPret = false;
    private final Map<String, Long> recents = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.FRANCE);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                ttsPret = true;
            }
        });
    }

    @Override
    public void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    static boolean estTradingView(String paquet) {
        return paquet != null && paquet.toLowerCase(Locale.ROOT).contains("tradingview");
    }

    /** Titre + texte de l'alerte, sans liens ni émojis, coupé proprement. */
    static String phrase(CharSequence titre, CharSequence texte) {
        String t = nettoie(titre);
        String m = nettoie(texte);
        StringBuilder sb = new StringBuilder("Alerte TradingView");
        if (!t.isEmpty() && !t.equalsIgnoreCase("TradingView")) sb.append(" : ").append(t);
        if (!m.isEmpty() && !m.equalsIgnoreCase(t)) sb.append(". ").append(m);
        String s = sb.toString();
        if (s.length() > MAX_CARACTERES) {
            s = s.substring(0, MAX_CARACTERES);
            int esp = s.lastIndexOf(' ');
            if (esp > 0) s = s.substring(0, esp);
            s += "…";
        }
        return s;
    }

    static String nettoie(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString()
                .replaceAll("https?://\\S+|www\\.\\S+", "")
                .replaceAll("[^\\p{L}\\p{N}\\s.,;:!?%€$+\\-/'()@&#*=<>\"«»…]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        while (s.endsWith(".") || s.endsWith(",") || s.endsWith(":")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || !estTradingView(sbn.getPackageName())) return;
            SharedPreferences prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
            if (!prefs.getBoolean(PREF, true)) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;  // résumé de groupe : déjà dit
            Bundle ex = n.extras;
            if (ex == null) return;
            CharSequence titre = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence texte = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (texte == null || texte.length() == 0) texte = ex.getCharSequence(Notification.EXTRA_TEXT);
            if ((titre == null || titre.length() == 0) && (texte == null || texte.length() == 0)) return;
            String p = phrase(titre, texte);
            long now = System.currentTimeMillis();
            synchronized (recents) {
                Iterator<Map.Entry<String, Long>> it = recents.entrySet().iterator();
                while (it.hasNext()) if (now - it.next().getValue() > MEME_TEXTE_MS) it.remove();
                if (recents.containsKey(p)) return;
                recents.put(p, now);
            }
            if (ttsPret) tts.speak(p, TextToSpeech.QUEUE_ADD, null, "tv" + now);
        } catch (Exception ignored) {
            // une notification bizarre ne doit jamais faire planter le service
        }
    }
}
