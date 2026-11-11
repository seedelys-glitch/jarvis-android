package fr.jabran.jarvis;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
                    + "(harv(y|ey|i|is|ie)|arvis+e?|arvi|harbis|r[ea]?viss?e?|r[ea]?vie?|revy|ervis+e?)(?![a-z])");

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
        return REVEIL.matcher(n).find() || RESSEMBLE_REVEIL.matcher(n).find() || REVEIL_SALUT.matcher(n).find()
                || longueurPerso(n) > 0;
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
            int lp = longueurPerso(n);
            if (lp > 0) return enleveReveil(n.substring(lp));
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
            Matcher abime = DEBUT_ABIME.matcher(t);
            if (abime.lookingAt()) {
                if (abime.end() >= t.length()) return "";   // « et je revisse » tout seul = « Jarvis » tout seul
                t = nettoie(t.substring(abime.end()));
                continue;
            }
            int lp = longueurPerso(t);
            if (lp > 0 && lp < t.length()) { t = nettoie(t.substring(lp)); continue; }
            String u = SALUT.matcher(t).replaceFirst("");
            Matcher m = REVEIL.matcher(u);
            if (!m.lookingAt()) return t;
            t = nettoie(u.substring(m.end()));
        }
    }

    /**
     * « Jarvis » tel que la dictée de Google l'écrit en début de commande (journal du PC du 02/11 : « et je revisse »
     * 14 fois, « ai jarisse », « ok, j'enrevisse ») : on l'enlève avant d'exécuter la commande.
     */
    private static final Pattern DEBUT_ABIME = Pattern.compile(
            "^((ok|bon|alors|oui),? )?((et|eh|ai|hey|he|e) )?(je |j'|j |jen? |j'en )?"
                    + "(revisse|enrevisse|revis|reviss|revise|arvis+e?|harvy|harvey|jarisse|jarris+e?|jervis+e?|revi)(?![a-z])[,.!?]*\\s*");

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

    // ------------------------------------------------------------------ « viens » / « tu peux partir »
    private static final Pattern VENIR = Pattern.compile(
            "^(vien[st]?|vien[st]? ici|vien[st]? voir|revien[st]?|montre toi|ouvre toi|affiche toi|apparais|t'es ou|tu es ou|"
                    + "ouvre l'appli|ouvre l'application|ouvre jarvis|reveille toi|j'ai besoin de toi)( stp| s'il te plait)?$");
    private static final Pattern PARTIR = Pattern.compile(
            "(tu peux (partir|y aller|disparaitre|te cacher|t'en aller|te fermer|fermer)|va t'en|vas y tu peux|cache toi|"
                    + "disparais|ferme toi|reduis toi|degage|casse toi|au revoir|bye|a plus tard|a toute)");

    /** « Jarvis, viens » : l'appli passe devant. */
    public static boolean estVenir(String cmd) {
        return VENIR.matcher(nettoie(normalise(cmd))).matches();
    }

    /** « C'est bon, tu peux partir » : fin de la discussion et l'appli se remet derrière. */
    public static boolean estPartir(String cmd) {
        String c = nettoie(normalise(cmd));
        return c.split(" ").length <= 8 && PARTIR.matcher(c).find();
    }

    // ------------------------------------------------------------------ ta façon de dire « Eh Jarvis » (apprise)
    private static final Set<String> PERSO = new LinkedHashSet<>();
    private static final Set<String> BANALS = new java.util.HashSet<>(java.util.Arrays.asList(
            "et", "je", "j'", "eh", "he", "hey", "ai", "a", "le", "la", "les", "de", "il", "elle", "on", "tu", "est",
            "c'est", "ce", "que", "qui", "un", "une", "oui", "non", "ok", "euh", "ah", "oh", "bon", "alors", "mais"));

    /** Les façons de dire « Eh Jarvis » apprises avec « Jarvis, apprends ma voix » (début de phrase). */
    public static synchronized void reglerPerso(Set<String> variantes) {
        PERSO.clear();
        for (String v : variantes) {
            String n = nettoie(normalise(v));
            if (n.length() >= 4) PERSO.add(n);
        }
    }

    /** Ce que la reconnaissance a écrit pour un « Eh Jarvis » d'apprentissage -> la variante à retenir (ou ""). */
    public static String varianteApprise(String entendu) {
        String n = nettoie(normalise(entendu)).replaceAll("[,.!?;:]", "");
        String[] w = n.split(" ");
        if (n.length() < 4 || w.length > 4) return "";
        boolean banal = true;
        for (String x : w) if (!BANALS.contains(x)) banal = false;
        return banal ? "" : n;   // « et je » tout seul réveillerait Jarvis à chaque phrase
    }

    /** Longueur de la variante apprise au début de la phrase (0 = aucune). */
    static synchronized int longueurPerso(String n) {
        for (String v : PERSO) {
            StringBuilder r = new StringBuilder("^");
            String[] w = v.split(" ");
            for (int i = 0; i < w.length; i++) r.append(i == 0 ? "" : "[\\s,.!?;:]+").append(Pattern.quote(w[i]));
            Matcher m = Pattern.compile(r + "(?![a-z0-9])").matcher(n);
            if (m.find()) return Math.max(1, m.end());
        }
        return 0;
    }

    // ------------------------------------------------------------------ « quand tu entends X, c'est Y »
    private static final Pattern CORRIGE = Pattern.compile(
            "^quand tu entends (.+?),? (c'est|ca veut dire|je dis|je veux dire|il faut comprendre) (.+)$");

    /** « quand tu entends Waz, c'est Waze » -> {"waz", "waze"} ; sinon null. */
    public static String[] lireCorrection(String cmd) {
        Matcher m = CORRIGE.matcher(nettoie(normalise(cmd)));
        if (!m.matches()) return null;
        String de = m.group(1).replaceAll("^(le mot |« |\")|( »|\")$", "").trim(), vers = m.group(3).trim();
        if (de.length() < 2 || vers.isEmpty() || de.equals(vers)) return null;
        return new String[]{de, vers};
    }

    /** Applique tes corrections (mot entier) à la commande déjà normalisée. */
    public static String corriger(String cmd, Map<String, String> corrections) {
        String c = cmd;
        for (Map.Entry<String, String> e : corrections.entrySet()) {
            c = c.replaceAll("(?<![a-z0-9])" + Pattern.quote(e.getKey()) + "(?![a-z0-9])", Matcher.quoteReplacement(e.getValue()));
        }
        return c;
    }

    static Map<String, String> nouvelleTable() { return new LinkedHashMap<>(); }

    private static String nettoie(String s) {
        return s.replaceAll("^[\\s,.!?;:]+|[\\s,.!?;:]+$", "");
    }
}
