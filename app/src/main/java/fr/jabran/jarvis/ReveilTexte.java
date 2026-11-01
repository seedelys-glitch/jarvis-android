package fr.jabran.jarvis;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les mots du réveil « Eh Jarvis » du téléphone, réglés comme sur le PC (voix.py, 01/11/2026) :
 * mêmes façons d'écrire « Jarvis », mêmes mots de fin (« merci », « c'est tout »…), mêmes délais.
 * Aucune dépendance Android : testé tout seul (tests/ReveilTexteTest.java).
 */
public final class ReveilTexte {

    private ReveilTexte() { }

    // Délais du PC (voix.py) : FOLLOWUP_S, CONVERSATION_S, CONV_AFTER_IA_S, PHRASE_END_S, PHRASE_MAX_S
    public static final long ATTENTE_COMMANDE_MS = 8_000;   // « Jarvis » seul, le bip : 8 s pour la commande
    public static final long SUITE_MS = 12_000;             // après la réponse : 12 s pour enchaîner sans redire « Jarvis »
    public static final float FIN_PHRASE_S = 0.8f;          // fin de phrase : 0,8 s de silence
    public static final float PHRASE_MAX_S = 15f;           // une phrase ne dure jamais plus de 15 s

    /**
     * « Jarvis » tel que la reconnaissance française l'écrit. Liste du PC (WAKE_RE) + ce que le modèle
     * du téléphone écrit souvent (« Jarvie », « Gervi », « Garvie »…).
     */
    public static final Pattern REVEIL = Pattern.compile(
            "(?<![a-z])(jarvis+e?|jarvi[es]?|jar vis|jarre vis|jervis|garvis|garvie?|charvis|charvie|djarvis|"
                    + "jarvice|jarbis|jervice|gervi[es]?|gervice|gervy|j ?'?arrive is|gervais)(?![a-z])");

    /** Mot collé ou abîmé qui ressemble à « Jarvis » (« aijarvi ») : seulement pour couper la commande. */
    private static final Pattern RESSEMBLE = Pattern.compile("\\S*(jarv|garv|gerv|charv|djarv)\\S*");

    private static final Pattern SALUT = Pattern.compile("^(hey|he|eh|et|ai|salam\\w*|salut|bonjour)[\\s,.!?]+");

    /** Fin de discussion (PC : END_WORDS). */
    private static final String[] FIN = {"merci", "c'est tout", "c'est bon", "rien", "ca ira", "laisse",
            "fin de conversation"};

    /** « Jarvis, arrête d'écouter » : coupe le réveil du téléphone. */
    private static final Pattern ARRET = Pattern.compile(
            "(arrete|stop|coupe)\\w* (d'|de |l')?(t'?)?ecout|ne m'?ecoute plus|eteins? (le |ton )?micro|coupe (le |ton )?micro");

    /** Minuscules, sans accents, apostrophes droites, espaces simples (comme normalize() du PC). */
    public static String normalise(String t) {
        if (t == null) return "";
        String s = Normalizer.normalize(t.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('’', '\'').replace('‘', '\'')
                .replaceAll("[-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    /** Le mot de réveil est-il dans la phrase ? */
    public static boolean contientReveil(String texte) {
        return REVEIL.matcher(normalise(texte)).find();
    }

    /**
     * Ce qu'il faut faire après « Jarvis » : ce qui suit le premier « Jarvis » de la phrase, sans
     * « Jarvis » répété ni salutation. « Eh Jarvis, c'est quoi le prix du gold ? » -> « c'est quoi le prix du gold ».
     * Rien après « Jarvis » -> "" (le téléphone fait le bip et attend la commande).
     */
    public static String apresReveil(String texte) {
        String n = normalise(texte);
        Matcher m = REVEIL.matcher(n);
        if (!m.find()) {
            m = RESSEMBLE.matcher(n);
            if (!m.find()) return nettoie(n);
        }
        return enleveReveil(n.substring(m.end()));
    }

    /** Enlève « Jarvis » (même répété) et « Salam / Eh » du début de la phrase (PC : strip_wake). */
    public static String enleveReveil(String texte) {
        String t = nettoie(normalise(texte));
        while (true) {
            String u = SALUT.matcher(t).replaceFirst("");
            Matcher m = REVEIL.matcher(u);
            if (!m.lookingAt()) return t;
            t = nettoie(u.substring(m.end()));
        }
    }

    /** « merci », « c'est tout »… : on arrête de discuter (PC : END_WORDS). */
    public static boolean estFin(String commande) {
        String c = nettoie(normalise(commande));
        if (c.split(" ").length > 4) return false;   // « merci de mettre la musique » : c'est une commande
        for (String w : FIN) {
            if (c.equals(w) || c.startsWith(w + " ") || c.endsWith(" " + w)) return true;
        }
        return false;
    }

    /** « Jarvis, arrête d'écouter » : on coupe le réveil du téléphone. */
    public static boolean estArret(String commande) {
        return ARRET.matcher(normalise(commande)).find();
    }

    private static String nettoie(String s) {
        return s.replaceAll("^[\\s,.!?;:]+|[\\s,.!?;:]+$", "");
    }
}
