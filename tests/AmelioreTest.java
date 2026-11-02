import fr.jabran.jarvis.Ameliore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Lancer : javac -d /tmp/t app/src/main/java/fr/jabran/jarvis/{ReveilTexte,AppsTexte,Ameliore}.java tests/AmelioreTest.java && java -cp /tmp/t AmelioreTest */
public class AmelioreTest {
    static int ok = 0, ko = 0;
    static void vrai(boolean b, String what) { if (b) ok++; else { ko++; System.out.println("RATE " + what); } }

    public static void main(String[] a) {
        List<String> j = Arrays.asList(
                "02/11 15:00:01  pas réveillé : « et chez revis »",
                "02/11 15:00:06  réveil sur : « eh jarvis »",
                "02/11 15:00:09  toi : ouvre waze",
                "02/11 15:00:10  Jarvis : J'ouvre Waze.",
                "02/11 15:00:40  toi : ouvre uber driver",
                "02/11 15:00:41  Jarvis : J'ouvre Uber Driver.",
                "02/11 16:00:01  pas réveillé : « Et chez revis. »",
                "02/11 16:00:05  réveil sur : « eh jarvis »",
                "02/11 16:00:08  toi : ouvre waze",
                "02/11 16:00:40  toi : ouvre uber driver",
                "02/11 17:00:08  réveil sur : « eh jarvis »",
                "02/11 17:00:09  toi : ouvre waze",
                "02/11 17:00:40  toi : ouvre uber driver",
                "02/11 18:00:00  toi : met la meteau",
                "02/11 18:00:01  Jarvis : Je n'ai pas compris.",
                "02/11 19:00:00  pas réveillé : « bonjour monsieur »",
                "01/11 19:00:00  toi : hier");
        List<String> jour = Ameliore.duJour(j, "02/11");
        vrai(jour.size() == 16, "lignes du jour : " + jour.size());
        Ameliore.Recap r = Ameliore.analyser(jour, new HashSet<>());
        vrai(r.reveils == 3, "réveils " + r.reveils);
        vrai(r.commandes == 7, "commandes " + r.commandes);
        vrai(r.rates == 3, "ratés " + r.rates);
        vrai(r.ratees == 1, "réponses ratées " + r.ratees);
        vrai(r.nouvellesVariantes.contains("et chez revis"), "variante apprise toute seule " + r.nouvellesVariantes);
        vrai(!r.nouvellesVariantes.contains("bonjour monsieur"), "pas bonjour monsieur");
        vrai(r.raccourciPropose.equals("ouvre waze puis ouvre uber driver"), "raccourci proposé : " + r.raccourciPropose);
        vrai(r.commandesRatees.contains("met la meteau"), "commande ratée notée");
        vrai(r.texte.startsWith("Récap du jour : 3 réveils, 7 demandes"), r.texte);
        System.out.println(r.texte);
        Ameliore.Recap vide = Ameliore.analyser(Arrays.asList(), new HashSet<>());
        vrai(vide.texte.contains("pas parlé"), "jour vide");
        System.out.println(ok + " réussis, " + ko + " ratés");
        if (ko > 0) System.exit(1);
    }
}
