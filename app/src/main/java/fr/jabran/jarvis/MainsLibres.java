package fr.jabran.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

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

    interface Fin { void fait(boolean ferme); }

    /**
     * Fermer vraiment une appli (01/11/2026) : Android ne laisse pas une appli en tuer une autre, alors on fait
     * comme toi : écran des applis récentes, on trouve la carte qui porte son nom, on la fait glisser vers le haut,
     * puis retour à l'accueil. Ne lit que l'écran des récentes (noms des applis).
     */
    static boolean fermerParRecents(String nom, Fin fin) {
        MainsLibres m = actif;
        if (m == null || Build.VERSION.SDK_INT < 24) return false;
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            if (!m.performGlobalAction(GLOBAL_ACTION_RECENTS)) { fin.fait(false); return; }
            h.postDelayed(() -> m.balayer(nom, 0, fin, h), 900);
        });
        return true;
    }

    private void balayer(String nom, int essai, Fin fin, Handler h) {
        Rect r = trouve(nom);
        if (r == null) {
            if (essai < 4) { h.postDelayed(() -> balayer(nom, essai + 1, fin, h), 500); return; }
            trouve(nom, true);
            Log.i("JarvisApps", "carte « " + nom + " » introuvable dans les récentes");
            performGlobalAction(GLOBAL_ACTION_HOME);
            fin.fait(false);
            return;
        }
        int w = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        float x = Math.max(w * 0.15f, Math.min(w * 0.85f, r.centerX()));
        float y0 = Math.min(H * 0.70f, r.bottom + H * 0.22f);
        Path p = new Path();
        p.moveTo(x, y0);
        p.lineTo(x, H * 0.03f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 220)).build();
        boolean parti = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) {
                h.postDelayed(() -> {
                    boolean encore = trouve(nom) != null;
                    Log.i("JarvisApps", "carte « " + nom + " » glissée : " + (encore ? "encore là" : "fermée"));
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    fin.fait(!encore);
                }, 700);
            }
            @Override public void onCancelled(GestureDescription d) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                fin.fait(false);
            }
        }, null);
        if (!parti) { performGlobalAction(GLOBAL_ACTION_HOME); fin.fait(false); }
    }

    /** La carte de l'appli dans l'écran des récentes (son nom écrit ou lu), ou null. */
    private Rect trouve(String nom) {
        return trouve(nom, false);
    }

    private Rect trouve(String nom, boolean journal) {
        java.util.List<AccessibilityNodeInfo> racines = new java.util.ArrayList<>();
        AccessibilityNodeInfo actif = getRootInActiveWindow();
        if (actif != null) racines.add(actif);
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && (r.getPackageName() == null || !getPackageName().contentEquals(r.getPackageName()))) racines.add(r);
            }
        } catch (Exception ignored) { }
        String cible = AppsTexte.compact(nom);
        StringBuilder vus = new StringBuilder();
        for (AccessibilityNodeInfo root : racines) {
            ArrayDeque<AccessibilityNodeInfo> file = new ArrayDeque<>();
            file.add(root);
            int n0 = 0;
            while (!file.isEmpty() && n0++ < 800) {
                AccessibilityNodeInfo n = file.removeFirst();
                if (n == null) continue;
                CharSequence t = n.getText(), d = n.getContentDescription();
                String s = AppsTexte.compact((t != null ? t : "") + " " + (d != null ? d : ""));
                if (!s.isEmpty() && vus.length() < 600) vus.append(s).append(" | ");
                if (!s.isEmpty() && (s.equals(cible) || s.startsWith(cible) && s.length() <= cible.length() + 12)) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (r.width() > 0 && r.height() > 0) return r;
                }
                for (int i = 0; i < n.getChildCount(); i++) file.add(n.getChild(i));
            }
        }
        if (journal) Log.i("JarvisApps", "écran des récentes (" + racines.size() + " fenêtres) : " + vus);
        return null;
    }

    static boolean retour() {
        MainsLibres m = actif;
        return m != null && m.performGlobalAction(GLOBAL_ACTION_BACK);
    }
}
