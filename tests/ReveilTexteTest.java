import fr.jabran.jarvis.ReveilTexte;

/** Lancer : javac -d /tmp/t app/src/main/java/fr/jabran/jarvis/ReveilTexte.java tests/ReveilTexteTest.java && java -cp /tmp/t ReveilTexteTest */
public class ReveilTexteTest {
    static int ok = 0, ko = 0;
    static void eq(String got, String want, String what) {
        if (want.equals(got)) ok++; else { ko++; System.out.println("RATE " + what + " : « " + got + " » au lieu de « " + want + " »"); }
    }
    static void vrai(boolean b, String what) { if (b) ok++; else { ko++; System.out.println("RATE " + what); } }

    public static void main(String[] a) {
        // ce que le modèle du téléphone a écrit pendant les essais
        String[] reveils = {"Et Jarvie, c’est quoi le prix du gold?", "Ai-jarvi.", "Salam Garvie", "Et Gervi, c’est quoi le prix",
                "Jarvis", "Hey Jarvis", "Eh Jarvis", "Salam, Gervy,", "Jarvie, quelle heure est-il?", "Et Jarvis"};
        for (String r : reveils) vrai(ReveilTexte.contientReveil(r), "réveil : " + r);
        String[] pasReveil = {"Je vais chercher un client à la gare de Lyon", "Bonjour monsieur, vous allez où",
                "J’arrive dans cinq minutes", "Il fait beau aujourd’hui à Paris", "Et regarde ça, c’est pas mal",
                "Le service est terminé à demain", "Et t’as vu le match hier soir", "Je vais voir la vie en rose", "garvieux"};
        for (String r : pasReveil) vrai(!ReveilTexte.contientReveil(r), "pas réveil : " + r);

        eq(ReveilTexte.apresReveil("Et Jarvie, c’est quoi le prix du gold?"), "c'est quoi le prix du gold", "commande 1");
        eq(ReveilTexte.apresReveil("Jarvis, Jarvis, allume la lumière"), "allume la lumiere", "commande répétée");
        eq(ReveilTexte.apresReveil("Salam Jarvis"), "", "réveil seul");
        eq(ReveilTexte.apresReveil("Eh Jarvis."), "", "réveil seul 2");
        eq(ReveilTexte.apresReveil("Ai-jarvi."), "", "réveil seul 3");
        eq(ReveilTexte.apresReveil("euh Jarvis mets la musique"), "mets la musique", "avant le mot");
        eq(ReveilTexte.apresReveil("Aijarvi"), "", "mot collé");
        eq(ReveilTexte.apresReveil("Aijarvi, rentre à 4000"), "rentre a 4000", "mot collé + commande");
        eq(ReveilTexte.enleveReveil("Jarvis, ouvre YouTube"), "ouvre youtube", "enlève");
        eq(ReveilTexte.enleveReveil("c'est quoi le prix"), "c'est quoi le prix", "rien à enlever");

        vrai(ReveilTexte.estFin("Merci."), "fin merci");
        vrai(ReveilTexte.estFin("ok merci"), "fin ok merci");
        vrai(ReveilTexte.estFin("C’est tout"), "fin c'est tout");
        vrai(!ReveilTexte.estFin("merci de mettre la musique"), "pas fin : merci de...");
        vrai(!ReveilTexte.estFin("c'est quoi le prix du gold"), "pas fin : question");

        vrai(ReveilTexte.estArret("arrête d'écouter"), "arrêt 1");
        vrai(ReveilTexte.estArret("Ne m’écoute plus"), "arrêt 2");
        vrai(ReveilTexte.estArret("coupe le micro"), "arrêt 3");
        vrai(!ReveilTexte.estArret("arrête la musique"), "pas arrêt");
        System.out.println(ok + " réussis, " + ko + " ratés");
        if (ko > 0) System.exit(1);
    }
}
