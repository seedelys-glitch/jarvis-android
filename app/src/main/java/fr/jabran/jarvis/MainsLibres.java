package fr.jabran.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * « Jarvis mains libres » (01/11/2026) : service d'accessibilité qui permet à Jarvis de revenir à l'écran
 * d'accueil, de faire « retour », et de savoir quelle appli est devant toi (pour la fermer à la voix).
 * Il ne lit AUCUN texte des autres applis : seulement le nom de l'appli qui passe devant.
 * À activer une fois : Réglages de Jarvis > « Activer Jarvis mains libres ».
 */
public class MainsLibres extends AccessibilityService {

    static volatile MainsLibres actif;
    static volatile String appDevant = "";

    @Override
    protected void onServiceConnected() {
        actif = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e != null && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String p = e.getPackageName().toString();
            // le clavier, la barre de notifications ou l'interface système ne sont pas « l'appli devant toi »
            if (!p.equals("com.android.systemui") && !p.contains("inputmethod") && !p.contains("honeyboard")) appDevant = p;
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (actif == this) actif = null;
        super.onDestroy();
    }

    static boolean accueil() {
        MainsLibres m = actif;
        return m != null && m.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    static boolean retour() {
        MainsLibres m = actif;
        return m != null && m.performGlobalAction(GLOBAL_ACTION_BACK);
    }
}
