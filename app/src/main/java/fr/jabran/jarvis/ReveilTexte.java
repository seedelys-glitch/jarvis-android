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
            "(?<![a-z])(jarvis+e?|jarvi[es]?|jari|jarvy|jar vis|jarre vis|jervis|garvis|garvie?|charvis|charvie|djarvis|"
                    + "jarvice|jarbis|jervice|gervi[es]?|gervice|gervy|j ?'?arrive is|gervais)(?![a-z])");

    /** Mot collé ou abîmé qui ressemble à « Jarvis » (« aijarvi ») : seulement pour couper la commande. */
    private static final Pattern RESSEMBLE = Pattern.compile("\\S*(jarv|garv|gerv|charv|djarv)\\S*");
    /** Pour le réveil : « jarv… » collé à un autre mot, mais pas « gerv… » (Gervais, Gervaise) ni « garv… ». */
    private static final Pattern RESSEMBLE_REVEIL = Pattern.compile("[a-z]*(jarv|charv|djarv|jharv)[a-z]*");

    /**
     * « Eh Jarvis » dont le « J » s'est perdu (« Ai-je Harvy », « et Arvis ») : accepté SEULEMENT en tout
     * début de phrase et après « eh / et / hey / ai (je) », pour ne pas se réveiller sur « Harvey » au milieu d'une phrase.
     */
    private static final Pattern REVEIL_SALUT = Pattern.compile(
            "^(hey|he|eh|et|ai|e|ai je|eh je|et je|hey je|e je) (j |j'|a )?"
                    + "(harv(y|ey|i|is|ie)|arvis+e?|arvi|harbis|r[ea]?viss?e?|r[ea]?vi|revy|ervis+e?)(?![a-z])");

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

    /** Le mot de réveil est-il dans la phrase ? (aussi les mots abîmés : « lesjarvis », « jarvisson ») */
    public static boolean contientReveil(String texte) {
        String n = normalise(texte);
        return REVEIL.matcher(n).find() || RESSEMBLE_REVEIL.matcher(n).find() || REVEIL_SALUT.matcher(n).find();
    }

    /** Commande trop courte pour être vraie (« sont », « euh ») : on attend la suite. */
    public static boolean tropCourte(String cmd) {
        String c = nettoie(normalise(cmd));
        if (c.isEmpty()) return true;
        if (c.contains(" ")) return false;
        for (String w : COURTES_OK) if (c.equals(w)) return false;
        return true;
    }

    private static final String[] COURTES_OK = {"merci", "stop", "heure", "meteo", "gold", "musique", "pause",
            "suivant", "positions", "annule", "confirme", "reviens"};

    /**
     * Ce qu'il faut faire après « Jarvis » : ce qui suit le premier « Jarvis » de la phrase, sans
     * « Jarvis » répété ni salutation. « Eh Jarvis, c'est quoi le prix du gold ? » -> « c'est quoi le prix du gold ».
     * Rien après « Jarvis » -> "" (le téléphone fait le bip et attend la commande).
     */
    public static String apresReveil(String texte) {
        String n = normalise(texte);
        Matcher m = REVEIL.matcher(n);
        if (!m.find()) {
            m = REVEIL_SALUT.matcher(n);
            if (m.find()) return enleveReveil(n.substring(m.end()));
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
