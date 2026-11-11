package fr.jabran.jarvis;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Widget de l'écran d'accueil (08/11/2026, idée validée par Jabran le 7/11) : un gros bouton « Parler à Jarvis »
 * (même chose que « Eh Jarvis » : « Oui ? » puis il écoute) et sa dernière réponse.
 */
public class JarvisWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, vue(c));
    }

    static RemoteViews vue(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_jarvis);
        String t = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
                .getString("widget_texte", "Appuie sur Parler, ou dis « Eh Jarvis ».");
        v.setTextViewText(R.id.widget_texte, t);
        PendingIntent parler = PendingIntent.getActivity(c, 40,
                new Intent(c, ParlerJarvis.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent ouvrir = PendingIntent.getActivity(c, 41,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_parler, parler);
        v.setOnClickPendingIntent(R.id.widget_titre, ouvrir);
        v.setOnClickPendingIntent(R.id.widget_texte, ouvrir);
        return v;
    }

    /** Nouvelle réponse de Jarvis : affichée sur le widget. */
    static void maj(Context c, String texte) {
        if (texte == null || texte.trim().isEmpty()) return;
        String t = texte.trim();
        if (t.length() > 160) t = t.substring(0, 157) + "…";
        c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putString("widget_texte", t).apply();
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, JarvisWidget.class));
            if (ids.length > 0) m.updateAppWidget(ids, vue(c));
        } catch (Exception ignored) { }
    }
}
