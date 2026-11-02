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
        vrai(ReveilTexte.contientReveil("Les jarvis sont"), "les jarvis");
        vrai(ReveilTexte.contientReveil("Ai-jari? Ouvre chrome."), "ai jari");
        eq(ReveilTexte.apresReveil("Ai-jari? Ouvre chrome."), "ouvre chrome", "après ai jari");
        vrai(ReveilTexte.contientReveil("lesjarvis"), "collé");
        eq(ReveilTexte.apresReveil("Les jarvis sont"), "sont", "après les jarvis");
        vrai(ReveilTexte.tropCourte("sont"), "trop courte");
        vrai(!ReveilTexte.tropCourte("merci"), "merci ok");
        vrai(!ReveilTexte.tropCourte("c'est quoi le prix"), "vraie commande");
        eq(ReveilTexte.enleveReveil("Jarvis, ouvre YouTube"), "ouvre youtube", "enlève");
        eq(ReveilTexte.enleveReveil("c'est quoi le prix"), "c'est quoi le prix", "rien à enlever");

        vrai(ReveilTexte.contientReveil("Ai-je Harvy ferme chrome"), "ai-je harvy");
        vrai(ReveilTexte.contientReveil("Et je revisse"), "et je revisse (S24 de Jabran)");
        eq(ReveilTexte.apresReveil("Ai-je à revie. Ouvre chrome"), "ouvre chrome", "ai-je à revie");
        eq(ReveilTexte.apresReveil("Et je revisse, quelle heure est-il ?"), "quelle heure est il", "après et je revisse");
        vrai(ReveilTexte.contientReveil("Et j'arvis"), "et j'arvis");
        vrai(ReveilTexte.contientReveil("Eh je revis"), "eh je revis");
        vrai(!ReveilTexte.contientReveil("et je reviens dans cinq minutes"), "pas : je reviens");
        vrai(!ReveilTexte.contientReveil("je revisse la roue de la voiture"), "pas : revisse au milieu");
        eq(ReveilTexte.apresReveil("Ai-je Harvy ferme chrome"), "ferme chrome", "après ai-je harvy");
        eq(ReveilTexte.apresReveil("Eh Arvis, quelle heure"), "quelle heure", "eh arvis");
        vrai(!ReveilTexte.contientReveil("j'ai vu Harvey au garage"), "pas harvey au milieu");
        vrai(ReveilTexte.estVenir("viens"), "viens");
        vrai(ReveilTexte.estVenir("Montre-toi"), "montre-toi");
        vrai(ReveilTexte.estVenir("vient."), "vient (mal écrit)");
        eq(ReveilTexte.apresReveil("Aijarvie vient."), "vient", "aijarvie vient");
        eq(ReveilTexte.apresReveil("Jarvis viens"), "viens", "jarvis viens");
        vrai(!ReveilTexte.estVenir("viens on va manger"), "pas venir");
        vrai(ReveilTexte.estPartir("C'est bon, tu peux partir"), "partir");
        vrai(ReveilTexte.estPartir("vas-y tu peux y aller"), "partir 2");
        vrai(ReveilTexte.estPartir("cache-toi"), "cache-toi");
        vrai(!ReveilTexte.estPartir("dis au client qu'il peut partir quand il veut ce soir à la gare"), "pas partir");
        java.util.Set<String> vs = new java.util.HashSet<>();
        vs.add(ReveilTexte.varianteApprise("Et chaque Ravi."));
        ReveilTexte.reglerPerso(vs);
        vrai(ReveilTexte.contientReveil("Et chaque ravi, ouvre Waze"), "variante apprise");
        eq(ReveilTexte.apresReveil("Et chaque ravi, ouvre Waze"), "ouvre waze", "après variante apprise");
        vrai(!ReveilTexte.contientReveil("chaque ravi"), "variante : seulement en entier au début");
        eq(ReveilTexte.varianteApprise("une phrase beaucoup trop longue pour un réveil"), "", "trop long pour une variante");
        eq(ReveilTexte.varianteApprise("Et je."), "", "variante banale refusée");
        String[] cor = ReveilTexte.lireCorrection("Quand tu entends Wase, c'est Waze");
        vrai(cor != null && cor[0].equals("wase") && cor[1].equals("waze"), "correction lue");
        java.util.Map<String, String> tab = new java.util.HashMap<>();
        tab.put("wase", "waze");
        eq(ReveilTexte.corriger("ouvre wase", tab), "ouvre waze", "correction appliquée");
        eq(ReveilTexte.corriger("ouvre wasemaker", tab), "ouvre wasemaker", "mot entier seulement");
        eq(ReveilTexte.enleveReveil("Et je revisse, t'enlèves Spotify"), "t'enleves spotify", "google : et je revisse");
        eq(ReveilTexte.enleveReveil("ok, j'enrevisse musique suivante"), "musique suivante", "j'enrevisse");
        eq(ReveilTexte.enleveReveil("ok j'en revisse musique suivante"), "musique suivante", "j'en revisse");
        eq(ReveilTexte.enleveReveil("ai jarisse ouvre waze"), "ouvre waze", "ai jarisse");
        eq(ReveilTexte.enleveReveil("je revisse"), "", "je revisse seul = Jarvis seul");
        eq(ReveilTexte.enleveReveil("ouvre waze"), "ouvre waze", "commande normale");
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
