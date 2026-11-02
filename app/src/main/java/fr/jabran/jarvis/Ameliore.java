package fr.jabran.jarvis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Le récap du soir et l'amélioration toute seule (02/11/2026, demande de Jabran : « tous les jours il fait un
 * récap de ce qui s'est passé et essaie de s'améliorer »). Lit le journal vocal du jour (EcouteJarvis.journal) :
 * - compte les réveils, les commandes, les « Eh Jarvis » ratés, les réponses ratées ;
 * - apprend tout seul : une phrase courte non reconnue, suivie dans les 15 s d'un vrai réveil, c'était sans doute
 *   un « Eh Jarvis » mal entendu -> gardée comme ta façon de le dire (si elle revient au moins 2 fois) ;
 * - propose un raccourci quand tu enchaînes souvent les mêmes deux commandes.
 * Aucune dépendance Android : testé tout seul (tests/AmelioreTest.java).
 */
public final class Ameliore {

    private Ameliore() { }

    public static final class Recap {
        public int reveils, commandes, rates, ratees;
        public final Set<String> nouvellesVariantes = new LinkedHashSet<>();
        public final List<String> commandesRatees = new ArrayList<>();
        public String raccourciPropose = "";
        public String texte = "";
    }

    /** Lignes du journal (« dd/MM HH:mm:ss  texte ») d'un jour donné (« dd/MM »). */
    public static List<String> duJour(List<String> journal, String jour) {
        List<String> r = new ArrayList<>();
        for (String l : journal) if (l.startsWith(jour + " ")) r.add(l);
        return r;
    }

    private static int secondes(String l) {
        try {
            String h = l.substring(6, 14);
            return Integer.parseInt(h.substring(0, 2)) * 3600 + Integer.parseInt(h.substring(3, 5)) * 60 + Integer.parseInt(h.substring(6, 8));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String texte(String l) {
        return l.length() > 16 ? l.substring(16).trim() : "";
    }

    private static String entreGuillemets(String t) {
        int a = t.indexOf('«'), b = t.lastIndexOf('»');
        return a >= 0 && b > a ? t.substring(a + 1, b).trim() : "";
    }

    private static final String[] RATE = {"je n'ai pas compris", "petit souci", "ajoute ta cle", "je n'ai pas reussi",
            "pas réussi", "n'ai pas compris", "je ne sais pas ouvrir", "code incorrect"};

    public static Recap analyser(List<String> lignes, Set<String> variantesConnues) {
        Recap r = new Recap();
        Map<String, Integer> candidats = new LinkedHashMap<>();
        Map<String, Integer> paires = new LinkedHashMap<>();
        String derniereCommande = "", commandeEnCours = "";
        int tDerniere = -1;
        for (int i = 0; i < lignes.size(); i++) {
            String l = lignes.get(i), t = texte(l);
            int s = secondes(l);
            if (t.startsWith("réveil sur")) r.reveils++;
            else if (t.startsWith("pas réveillé")) {
                r.rates++;
                // suivi d'un vrai réveil dans les 15 s ?
                for (int j = i + 1; j < lignes.size(); j++) {
                    int sj = secondes(lignes.get(j));
                    if (s < 0 || sj < 0 || sj - s > 15) break;
                    if (texte(lignes.get(j)).startsWith("réveil sur")) {
                        String v = ReveilTexte.varianteApprise(entreGuillemets(t));
                        if (!v.isEmpty() && !ReveilTexte.contientReveil(v) && !variantesConnues.contains(v)) {
                            candidats.merge(v, 1, Integer::sum);
                        }
                        break;
                    }
                }
            } else if (t.startsWith("toi : ")) {
                r.commandes++;
                String c = t.substring(6).replaceAll("\\s+\\(entendu : .*$", "").trim();
                if (!derniereCommande.isEmpty() && s >= 0 && tDerniere >= 0 && s - tDerniere < 120 && !c.equals(derniereCommande)) {
                    paires.merge(derniereCommande + " puis " + c, 1, Integer::sum);
                }
                derniereCommande = c;
                commandeEnCours = c;
                tDerniere = s;
            } else if (t.startsWith("Jarvis : ")) {
                String rep = ReveilTexte.normalise(t.substring(9));
                for (String m : RATE) {
                    if (rep.contains(ReveilTexte.normalise(m))) {
                        r.ratees++;
                        if (!commandeEnCours.isEmpty() && r.commandesRatees.size() < 5) r.commandesRatees.add(commandeEnCours);
                        break;
                    }
                }
                commandeEnCours = "";
            }
        }
        for (Map.Entry<String, Integer> e : candidats.entrySet()) if (e.getValue() >= 2) r.nouvellesVariantes.add(e.getKey());
        int best = 0;
        for (Map.Entry<String, Integer> e : paires.entrySet()) {
            if (e.getValue() >= 3 && e.getValue() > best) { best = e.getValue(); r.raccourciPropose = e.getKey(); }
        }
        r.texte = phrase(r);
        return r;
    }

    private static String phrase(Recap r) {
        if (r.reveils == 0 && r.commandes == 0 && r.rates == 0) return "Récap du jour : on ne s'est pas parlé aujourd'hui.";
        StringBuilder b = new StringBuilder("Récap du jour : ");
        b.append(r.reveils).append(" réveil").append(r.reveils > 1 ? "s" : "").append(", ")
                .append(r.commandes).append(" demande").append(r.commandes > 1 ? "s" : "");
        if (r.ratees > 0) b.append(", dont ").append(r.ratees).append(" où j'ai mal répondu");
        b.append(". ");
        if (r.rates > 0) b.append(r.rates).append(" phrase").append(r.rates > 1 ? "s courtes" : " courte").append(" ne m'ont pas réveillé. ");
        if (!r.nouvellesVariantes.isEmpty()) {
            b.append("Je me suis amélioré : j'ai appris ").append(r.nouvellesVariantes.size())
                    .append(" nouvelle").append(r.nouvellesVariantes.size() > 1 ? "s façons" : " façon")
                    .append(" dont tu dis « Eh Jarvis » (").append(String.join(", ", r.nouvellesVariantes)).append("). ");
        }
        if (!r.commandesRatees.isEmpty()) {
            b.append("À revoir : « ").append(String.join(" », « ", r.commandesRatees)).append(" ». ")
                    .append("Si je t'ai mal compris, dis-moi « quand tu entends …, c'est … ». ");
        }
        if (!r.raccourciPropose.isEmpty()) {
            b.append("Tu fais souvent « ").append(r.raccourciPropose).append(" » : dis « quand je dis mode route, fais ")
                    .append(r.raccourciPropose).append(" » et je le ferai d'un coup. ");
        }
        return b.toString().trim();
    }
}
