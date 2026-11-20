package fr.jabran.jarvis;

/** Ouvre les méthodes « paquet » d'AnnoncesTrades au test (le test est hors du paquet). */
public final class AnnoncesTradesAcces {
    public static String phrase(String p, CharSequence t, CharSequence c, CharSequence x) {
        return AnnoncesTrades.phrase(p, t, c, x);
    }
}
