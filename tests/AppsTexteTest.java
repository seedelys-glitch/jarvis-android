import fr.jabran.jarvis.AppsTexte;

import java.util.Arrays;
import java.util.List;

/** Lancer : javac -d /tmp/t app/src/main/java/fr/jabran/jarvis/{ReveilTexte,AppsTexte}.java tests/AppsTexteTest.java && java -cp /tmp/t AppsTexteTest */
public class AppsTexteTest {
    static int ok = 0, ko = 0;
    static void vrai(boolean b, String what) { if (b) ok++; else { ko++; System.out.println("RATE " + what); } }

    static void demande(String phrase, int action, String nom) {
        AppsTexte.Demande d = AppsTexte.comprendre(phrase);
        vrai(d != null && d.action == action && d.nom.equals(nom),
                phrase + " -> " + (d == null ? "null" : d.action + " « " + d.nom + " »"));
    }

    static void rien(String phrase) {
        vrai(AppsTexte.comprendre(phrase) == null, "pas pour le téléphone : " + phrase);
    }

    public static void main(String[] a) {
        demande("Ouvre Waze", AppsTexte.OUVRIR, "waze");
        demande("ouvre l'application Snapchat.", AppsTexte.OUVRIR, "snapchat");
        demande("lance Spotify sur le téléphone", AppsTexte.OUVRIR, "spotify");
        demande("Ferme Snapchat", AppsTexte.FERMER, "snapchat");
        demande("quitte YouTube", AppsTexte.FERMER, "youtube");
        demande("ferme tout", AppsTexte.ACCUEIL, "");
        demande("écran d'accueil", AppsTexte.ACCUEIL, "");
        demande("retour", AppsTexte.RETOUR, "");
        demande("Ai-je à revi? Ouvre chrome", AppsTexte.OUVRIR, "chrome");
        demande("eh harvy, ferme Snapchat", AppsTexte.FERMER, "snapchat");
        rien("et après on ira manger");
        rien("ouvre YouTube sur le PC");
        rien("ferme la page YouTube sur l'écran de gauche");
        rien("c'est quoi le prix du gold");
        rien("rentre à 4000, stop à 4200");

        List<String> apps = Arrays.asList("Waze", "Snapchat", "YouTube", "YouTube Music", "WhatsApp", "Spotify",
                "Uber Driver", "Bolt Driver", "Heetch", "Google Maps", "TradingView", "Téléphone", "Messages", "Appareil photo");
        vrai(apps.get(AppsTexte.trouver("waze", apps)).equals("Waze"), "waze");
        vrai(apps.get(AppsTexte.trouver("you tube", apps)).equals("YouTube"), "you tube");
        vrai(apps.get(AppsTexte.trouver("whatsapp", apps)).equals("WhatsApp"), "whatsapp");
        vrai(apps.get(AppsTexte.trouver("what's app", apps)).equals("WhatsApp"), "what's app");
        vrai(apps.get(AppsTexte.trouver("uber", apps)).equals("Uber Driver"), "uber");
        vrai(apps.get(AppsTexte.trouver("bolt", apps)).equals("Bolt Driver"), "bolt");
        vrai(apps.get(AppsTexte.trouver("maps", apps)).equals("Google Maps"), "maps");
        vrai(apps.get(AppsTexte.trouver("trading view", apps)).equals("TradingView"), "trading view");
        vrai(apps.get(AppsTexte.trouver("snap", apps)).equals("Snapchat"), "snap");
        vrai(apps.get(AppsTexte.trouver("spotifi", apps)).equals("Spotify"), "spotifi (mal entendu)");
        vrai(apps.get(AppsTexte.trouver("caméra", apps)).equals("Appareil photo"), "caméra");
        vrai(apps.get(AppsTexte.trouver("sms", apps)).equals("Messages"), "sms");
        vrai(AppsTexte.trouver("mes positions", apps) == -1, "positions : pas une appli");
        vrai(AppsTexte.trouver("la musique", apps) == -1, "la musique : pas une appli");
        System.out.println(ok + " réussis, " + ko + " ratés");
        if (ko > 0) System.exit(1);
    }
}
