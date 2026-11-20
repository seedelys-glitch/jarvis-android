import fr.jabran.jarvis.AnnoncesTradesAcces;

/** Lancer : javac -encoding UTF-8 -d /tmp/t app/src/main/java/fr/jabran/jarvis/AnnoncesTrades.java tests/AnnoncesTradesAcces.java tests/AnnoncesTradesTest.java && java -cp /tmp/t AnnoncesTradesTest */
public class AnnoncesTradesTest {
    static int ok = 0, ko = 0;
    static void egal(String vu, String attendu, String what) {
        if ((vu == null && attendu == null) || (vu != null && vu.equals(attendu))) ok++;
        else { ko++; System.out.println("RATE " + what + " : « " + vu + " »"); }
    }

    public static void main(String[] a) {
        String tg = "org.telegram.messenger";
        String entree = "L'université entre en trade : vente à 3552,9. Objectif 3526,8, stop 3564,3.\n\n"
                + "L'ÉQUIPE ENTRE EN TRADE (démo, argent fictif) : VENTE\n  Prix d'entrée : 3 552,9";
        egal(AnnoncesTradesAcces.phrase(tg, "Jarvis Trades", null, entree),
                "L'université entre en trade : vente à 3552,9. Objectif 3526,8, stop 3564,3.", "entrée");
        egal(AnnoncesTradesAcces.phrase(tg, null, "Jarvis Trades", "L'université sort du trade : stop touché à 3564,3. Perte de 22 dollars."),
                "L'université sort du trade : stop touché à 3564,3. Perte de 22 dollars.", "sortie (titre de conversation)");
        egal(AnnoncesTradesAcces.phrase("org.telegram.messenger.web", "Telegram",
                null, "Jarvis Trades: L'université sort du trade : objectif atteint à 3526,8"),
                "L'université sort du trade : objectif atteint à 3526,8.", "notification groupée");
        egal(AnnoncesTradesAcces.phrase(tg, "JarvisTradesBot", null, "Essai"), "Essai.", "nom du bot");
        // jamais les autres conversations
        egal(AnnoncesTradesAcces.phrase(tg, "Jarvis", null, "Rapport de Claude"), null, "conversation principale");
        egal(AnnoncesTradesAcces.phrase(tg, "Maman", null, "Jarvis Trades c'est quoi ?"), null, "autre conversation");
        egal(AnnoncesTradesAcces.phrase("com.whatsapp", "Jarvis Trades", null, "Coucou"), null, "pas Telegram");
        egal(AnnoncesTradesAcces.phrase(tg, "Jarvis Trades", null, "3 nouveaux messages"), null, "compteur de messages");
        egal(AnnoncesTradesAcces.phrase(tg, "Jarvis Trades", null, "  "), null, "vide");
        System.out.println("AnnoncesTradesTest : " + ok + " ok, " + ko + " raté(s)");
        if (ko > 0) System.exit(1);
    }
}
