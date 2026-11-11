package fr.jabran.jarvis;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Photo prise avec l'appareil photo (idée GitHub 39, 01/11/2026) : l'appli Appareil photo écrit l'image
 * en pleine taille dans le cache de Jarvis, à cette adresse. Un seul fichier, effacé à la photo suivante.
 */
public class PhotoProvider extends ContentProvider {
    public static final String AUTORITE = "fr.jabran.jarvis.photo";
    public static final Uri URI = Uri.parse("content://" + AUTORITE + "/photo.jpg");

    static File fichier(android.content.Context c) {
        return new File(c.getCacheDir(), "photo.jpg");
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/photo.jpg".equals(uri.getPath())) throw new FileNotFoundException(uri.toString());
        int m = mode.contains("w")
                ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(fichier(getContext()), m);
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
