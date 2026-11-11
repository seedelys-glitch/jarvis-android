package fr.jabran.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * « Parler à Jarvis » sans ouvrir l'appli (08/11/2026) : le bouton du widget, l'appui long sur les écouteurs
 * (Jarvis choisi comme assistant du téléphone) et la réponse dictée sur la montre arrivent ici.
 * Écran invisible : il passe la main au service d'écoute (« Oui ? » puis Google écoute) et se ferme aussitôt.
 */
public class ParlerJarvis extends Activity {

    static final String CLE_REPONSE = "reponse_montre";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String texte = null;
        Bundle r = i != null ? RemoteInput.getResultsFromIntent(i) : null;
        if (r != null) {
            CharSequence cs = r.getCharSequence(CLE_REPONSE);
            if (cs != null && cs.toString().trim().length() > 0) texte = cs.toString().trim();
        }
        String d = i != null ? i.getAction() : null;
        Log.i("JarvisReveil", "parler à Jarvis : " + (texte != null ? "réponse de la montre « " + texte + " »" : String.valueOf(d)));
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && texte == null) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            EcouteJarvis.parler(this, texte);
            if (texte != null) Montre.enCours(this, texte);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
