package fr.jabran.jarvis;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * « Ouvre Waze », « ferme Snapchat », « écran d'accueil » : comprendre la phrase et trouver l'appli
 * du téléphone (01/11/2026). Aucune dépendance Android : testé tout seul (tests/AppsTexteTest.java).
 * « … sur le PC » : la phrase part au PC comme avant.
 */
public final class AppsTexte {

    private AppsTexte() { }

    public static final int OUVRIR = 1, FERMER = 2, ACCUEIL = 3, RETOUR = 4;

    /** Ce que la phrase demande : action + nom d'appli dit (null = pas pour le téléphone). */
    public static final class Demande {
        public final int action;
        public final String nom;
        Demande(int action, String nom) { this.action = action; this.nom = nom; }
    }

    private static final Pattern PC = Pattern.compile("\\b(sur|dans) (le|mon|l') ?(pc|ordi|ordinateur|ecran)\\b");
    private static final Pattern TEL = Pattern.compile("\\s+(sur|dans) (le|mon) (tel|telephone|portable|phone)$");
    private static final Pattern OUVRE = Pattern.compile(
            "^(ouvre|ouvrir|ouvres|lance|lancer|demarre|demarrer|affiche|va sur|vas sur|mets|met)( moi)? (.+)$");
    private static final Pattern FERME = Pattern.compile(
            "^(ferme|fermer|fermes|quitte|quitter|arrete|tue|kill|eteins)( moi)? (.+)$");
    /** « Ai-je à revi ? Ouvre Chrome » : un « Eh Jarvis » abîmé devant l'ordre (jusqu'à 4 mots). */
    private static final Pattern DEBUT_ABIME = Pattern.compile("^(hey|he|eh|et|ai|euh)\\b[^,.!?]{0,30}?[,.!?]? (?=(ouvre|ouvrir|lance|demarre|ferme|fermer|quitte)\\b)");
    private static final Pattern ARTICLE = Pattern.compile(
            "^(l'application |l'appli |l'app |application |appli |app |l'|le |la |les |mon |ma |mes |un |une )+");
    private static final Pattern ACCUEIL_RE = Pattern.compile(
            "^(ecran d'accueil|accueil|va (a|sur) l'accueil|retourne (a|sur) l'accueil|ferme (tout|toutes les applis|les applis|les applications))$");
    private static final Pattern RETOUR_RE = Pattern.compile("^(retour|reviens en arriere|page precedente)$");

    public static Demande comprendre(String phrase) {
        String n = ReveilTexte.normalise(phrase).replaceAll("[.!?,;]+$", "").trim();
        if (n.isEmpty() || PC.matcher(n).find()) return null;
        if (ACCUEIL_RE.matcher(n).matches()) return new Demande(ACCUEIL, "");
        if (RETOUR_RE.matcher(n).matches()) return new Demande(RETOUR, "");
        n = TEL.matcher(n).replaceFirst("");
        n = DEBUT_ABIME.matcher(n).replaceFirst("");
        Matcher m = OUVRE.matcher(n);
        int action = OUVRIR;
        if (!m.matches()) {
            m = FERME.matcher(n);
            action = FERMER;
            if (!m.matches()) return null;
        }
        String nom = ARTICLE.matcher(m.group(3)).replaceFirst("").trim();
        if (nom.length() < 2 || nom.split(" ").length > 4) return null;
        return new Demande(action, nom);
    }

    /** L'appli dont le nom ressemble le plus à ce qui a été dit, ou -1. */
    public static int trouver(String dit, List<String> noms) {
        String d = compact(dit);
        for (String[] syn : SYNONYMES) {
            if (d.equals(syn[0])) {
                int i = trouver(syn[1], noms);
                if (i >= 0) return i;
            }
        }
        if (d.length() < 2) return -1;
        int best = -1, bestScore = 0;
        for (int i = 0; i < noms.size(); i++) {
            String n = compact(noms.get(i));
            if (n.isEmpty()) continue;
            int score = 0;
            if (n.equals(d)) score = 100;
            else if (n.startsWith(d) && d.length() >= 3) score = 80 - Math.min(20, n.length() - d.length());
            else if (d.startsWith(n) && n.length() >= 4) score = 70;
            else if (n.contains(d) && d.length() >= 4) score = 60 - Math.min(20, n.length() - d.length());
            else if (d.length() >= 4 && distance(n, d) <= (d.length() >= 7 ? 2 : 1)) score = 50;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private static final String[][] SYNONYMES = {
            {"camera", "appareil photo"}, {"photo", "appareil photo"}, {"photos", "galerie"},
            {"sms", "messages"}, {"texto", "messages"}, {"appel", "telephone"}, {"telephone", "telephone"},
            {"reglages", "parametres"}, {"parametre", "parametres"}, {"parametres", "settings"}, {"reglages", "settings"}, {"youtubemusique", "youtube music"},
            {"insta", "instagram"}, {"facebook", "facebook"}, {"gmail", "gmail"}, {"mail", "gmail"}};

    /** « You Tube », « what's app » -> « youtube », « whatsapp ». */
    public static String compact(String s) {
        return ReveilTexte.normalise(s).replaceAll("[^a-z0-9]", "");
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
