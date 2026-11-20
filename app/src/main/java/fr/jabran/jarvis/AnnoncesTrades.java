package fr.jabran.jarvis;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Trades de l'université dits à voix haute (20/11/2026, demandé par Jabran).
 *
 * Le PC envoie chaque entrée et chaque sortie de trade de l'université dans une conversation Telegram à part,
 * celle du bot « Jarvis Trades ». Chaque message commence par une phrase courte, par exemple
 * « L'université entre en trade : vente à 3552,9. Objectif 3526,8, stop 3564,3. ».
 * Quand la notification Telegram de « Jarvis Trades » arrive, le téléphone dit cette première phrase.
 * Les autres conversations Telegram ne sont JAMAIS lues.
 *
 * Que de la logique pure ici (pas d'Android) : testée par tests/AnnoncesTradesTest.java.
 */
public final class AnnoncesTrades {

    static final String PREF = "trades_voix";       // true par défaut
    private static final int MAX_CARACTERES = 250;

    private AnnoncesTrades() { }

    static boolean estTelegram(String paquet) {
        if (paquet == null) return false;
        String p = paquet.toLowerCase(Locale.ROOT);
        return p.contains("telegram") || p.contains("challegram");
    }

    static String plat(CharSequence cs) {
        if (cs == null) return "";
        String s = Normalizer.normalize(cs.toString(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase(Locale.ROOT);
    }

    /** Sans liens ni émojis (même nettoyage que les alertes TradingView). */
    static String nettoie(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString()
                .replaceAll("https?://\\S+|www\\.\\S+", "")
                .replaceAll("[^\\p{L}\\p{N}\\s.,;:!?%€$+\\-/'’()@&#*=<>\"«»…]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        while (s.endsWith(".") || s.endsWith(",") || s.endsWith(":")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    /** « Jarvis Trades », « JarvisTradesBot », « Jarvis_Trades »… */
    static boolean estConversationTrades(CharSequence nom) {
        return plat(nom).matches("(?s).*jarvis[\\s_-]*trades?.*");
    }

    /**
     * La phrase à dire, ou null si la notification ne vient pas de la conversation « Jarvis Trades ».
     * titre = titre de la notification, conversation = titre de conversation (MessagingStyle), texte = le message.
     */
    static String phrase(String paquet, CharSequence titre, CharSequence conversation, CharSequence texte) {
        if (!estTelegram(paquet)) return null;
        String msg = texte == null ? "" : texte.toString();
        boolean trades = estConversationTrades(titre) || estConversationTrades(conversation);
        if (!trades) {
            // Notification groupée : « Jarvis Trades: L'université entre en trade… »
            int deux = msg.indexOf(':');
            if (deux > 0 && deux < 40 && estConversationTrades(msg.substring(0, deux))) {
                msg = msg.substring(deux + 1);
                trades = true;
            }
        }
        if (!trades) return null;
        String ligne = "";
        for (String l : msg.split("\\r?\\n")) {
            String t = nettoie(l);
            if (!t.isEmpty()) { ligne = t; break; }
        }
        if (ligne.isEmpty()) return null;
        String p = plat(ligne);
        if (p.matches(".*\\d+\\s+(nouveaux )?(new )?messages?.*") && !p.contains("trade")) return null;
        if (ligne.length() > MAX_CARACTERES) {
            ligne = ligne.substring(0, MAX_CARACTERES);
            int esp = ligne.lastIndexOf(' ');
            if (esp > 0) ligne = ligne.substring(0, esp);
        }
        return ligne.endsWith(".") ? ligne : ligne + ".";
    }
}
