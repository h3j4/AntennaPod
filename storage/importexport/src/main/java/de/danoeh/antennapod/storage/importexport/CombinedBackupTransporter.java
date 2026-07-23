package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports and imports a single combined backup file (a ZIP) that contains both the database
 * and the app settings. Import also accepts a plain SQLite database file (older backups),
 * in which case only the database is restored.
 */
public class CombinedBackupTransporter {
    private static final String TAG = "CombinedBackup";
    private static final String ENTRY_DATABASE = "database.db";
    private static final String ENTRY_PREFERENCES = "preferences.json";
    // ZIP local file header magic number: "PK\x03\x04"
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    private CombinedBackupTransporter() {
    }

    public static void exportToDocument(Uri uri, Context context) throws IOException {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "wt");
             ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pfd.getFileDescriptor()))) {
            zip.putNextEntry(new ZipEntry(ENTRY_DATABASE));
            DatabaseExporter.exportToStream(zip, context);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(ENTRY_PREFERENCES));
            PreferencesTransporter.exportToStream(zip, context);
            zip.closeEntry();
        }
    }

    public static void importBackup(Uri inputUri, Context context) throws IOException {
        if (isZip(inputUri, context)) {
            importZip(inputUri, context);
        } else {
            // Older / database-only backup: restore just the database.
            DatabaseExporter.importBackup(inputUri, context);
        }
    }

    private static boolean isZip(Uri uri, Context context) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Unable to open backup file");
            }
            byte[] signature = new byte[ZIP_SIGNATURE.length];
            int read = IOUtils.read(in, signature);
            if (read < ZIP_SIGNATURE.length) {
                return false;
            }
            for (int i = 0; i < ZIP_SIGNATURE.length; i++) {
                if (signature[i] != ZIP_SIGNATURE[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void importZip(Uri inputUri, Context context) throws IOException {
        File tempDb = null;
        boolean databaseFound = false;
        try (InputStream in = context.getContentResolver().openInputStream(inputUri);
             ZipInputStream zip = new ZipInputStream(in)) {
            if (in == null) {
                throw new IOException("Unable to open backup file");
            }
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (ENTRY_DATABASE.equals(entry.getName())) {
                    tempDb = File.createTempFile("apbackup", ".db", context.getCacheDir());
                    try (OutputStream out = new FileOutputStream(tempDb)) {
                        IOUtils.copy(zip, out);
                    }
                    databaseFound = true;
                } else if (ENTRY_PREFERENCES.equals(entry.getName())) {
                    PreferencesTransporter.importFromStream(zip, context);
                }
                zip.closeEntry();
            }

            if (!databaseFound || tempDb == null) {
                throw new IOException("Backup does not contain a database");
            }
            try (InputStream dbStream = new FileInputStream(tempDb)) {
                DatabaseExporter.importFromStream(dbStream, context);
            }
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            if (tempDb != null && tempDb.exists() && !tempDb.delete()) {
                Log.w(TAG, "Unable to delete temporary database file");
            }
        }
    }
}
