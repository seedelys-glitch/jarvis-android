package fr.jabran.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Montre (08/11/2026, idée validée par Jabran le 7/11) : chaque réponse de Jarvis dite à voix haute arrive aussi en
 * notification discrète, que la montre affiche. « Répondre » sur la montre = tu dictes ta réponse sur la montre,
 * elle revient à Jarvis comme si tu l'avais dite (sans sortir le téléphone). Réglage : « Montre » (oui par défaut).
 */
final class Montre {

    private Montre() { }

    static final String PREF = "montre";
    private static final String CANAL = "jarvis_montre";
    private static final int NOTIF = 4250;

    static boolean active(Context c) {
        return c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean(PREF, true);
    }

    static void reponse(Context c, String texte) {
        if (texte == null || texte.trim().isEmpty() || !active(c)) return;
        poster(c, "Jarvis", texte.trim());
    }

    /** Ta réponse dictée sur la montre est partie : la notification le montre en attendant la réponse. */
    static void enCours(Context c, String texte) {
        if (!active(c)) return;
        poster(c, "Toi", texte + "  (Jarvis réfléchit…)");
    }

    private static void poster(Context c, String qui, String texte) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CANAL, "Jarvis sur la montre", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Les réponses de Jarvis, pour les lire et lui répondre depuis la montre");
                nm.createNotificationChannel(ch);
            }
            RemoteInput ri = new RemoteInput.Builder(ParlerJarvis.CLE_REPONSE).setLabel("Ta réponse à Jarvis")
                    .setChoices(new CharSequence[]{"Merci", "Répète", "Quelle heure est-il ?"}).build();
            int drapeaux = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent rep = PendingIntent.getActivity(c, 42,
                    new Intent(c, ParlerJarvis.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    drapeaux);
            Notification.Action repondre = new Notification.Action.Builder(null, "Répondre", rep).addRemoteInput(ri).build();
            PendingIntent ouvrir = PendingIntent.getActivity(c, 43,
                    new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CANAL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_jarvis)
                    .setContentTitle(qui)
                    .setContentText(texte)
                    .setStyle(new Notification.BigTextStyle().bigText(texte))
                    .setContentIntent(ouvrir)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setLocalOnly(false)
                    .addAction(repondre)
                    .extend(new Notification.WearableExtender().addAction(repondre));
            nm.notify(NOTIF, b.build());
        } catch (Exception ignored) { }
    }
}
