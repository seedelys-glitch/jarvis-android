package fr.jabran.jarvis;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Ouvrir / fermer les applis du téléphone à la voix (01/11/2026) : « ouvre Waze », « ferme Snapchat »,
 * « écran d'accueil », « retour ». Renvoie la phrase à dire, ou null si la demande n'est pas pour le téléphone
 * (elle part alors au PC ou à l'IA comme avant). « … sur le PC » part toujours au PC.
 */
final class AppsTelephone {

    private AppsTelephone() { }

    static String essayer(Context c, String phrase) {
        AppsTexte.Demande d = AppsTexte.comprendre(phrase);
        if (d == null) return null;
        if (d.action == AppsTexte.ACCUEIL) {
            if (MainsLibres.accueil()) return "Écran d'accueil.";
            Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(h); return "Écran d'accueil."; } catch (Exception e) { return null; }
        }
        if (d.action == AppsTexte.RETOUR) {
            return MainsLibres.retour() ? "Retour." : null;
        }
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> lanceurs = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<String> noms = new ArrayList<>();
        List<String> paquets = new ArrayList<>();
        for (ResolveInfo r : lanceurs) {
            String p = r.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || paquets.contains(p)) continue;
            noms.add(String.valueOf(r.loadLabel(pm)));
            paquets.add(p);
        }
        int i = AppsTexte.trouver(d.nom, noms);
        if (i < 0) return null;   // pas une appli du téléphone : on laisse passer (PC, IA)
        String nom = noms.get(i), paquet = paquets.get(i);
        Log.i("JarvisApps", (d.action == AppsTexte.OUVRIR ? "ouvre " : "ferme ") + nom + " (" + paquet + ")");
        if (d.action == AppsTexte.OUVRIR) {
            Intent l = pm.getLaunchIntentForPackage(paquet);
            if (l == null) return "Je ne sais pas ouvrir " + nom + ".";
            l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                Context lanceur = MainsLibres.actif != null ? MainsLibres.actif : c;
                lanceur.startActivity(l);
                return "J'ouvre " + nom + ".";
            } catch (Exception e) {
                return "Android m'empêche d'ouvrir " + nom + " : active « Jarvis mains libres » dans les réglages de Jarvis.";
            }
        }
        // FERMER : par l'écran des applis récentes (la seule vraie fermeture qu'Android permet), sinon arrière-plan
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        final String nomF = nom, paquetF = paquet;
        boolean lance = MainsLibres.fermerParRecents(nom, ferme -> {
            if (!ferme) {
                try { am.killBackgroundProcesses(paquetF); } catch (Exception ignored) { }
                MainActivity a = MainActivity.vivante();
                if (a != null) a.direVoix("Je n'ai pas réussi à fermer complètement " + nomF + " : je l'ai mise en arrière-plan.");
            }
        });
        if (lance) return "Je ferme " + nom + ".";
        boolean devant = paquet.equals(MainsLibres.appDevant);
        if (devant && !MainsLibres.accueil()) {
            return "Pour fermer " + nom + ", active « Jarvis mains libres » dans les réglages de Jarvis.";
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { am.killBackgroundProcesses(paquet); } catch (Exception ignored) { }
        }, devant ? 1200 : 0);
        return "J'ai mis " + nom + " en arrière-plan. Pour la fermer complètement, active « Jarvis mains libres » dans les réglages.";
    }

    /** Le service « Jarvis mains libres » est-il activé dans Accessibilité ? */
    static boolean mainsLibresActif(Context c) {
        String l = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return l != null && l.contains(c.getPackageName() + "/");
    }
}
