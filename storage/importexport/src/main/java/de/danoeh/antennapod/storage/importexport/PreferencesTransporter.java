package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Exports and imports the app's default SharedPreferences (i.e. user settings) as JSON,
 * so that settings can be backed up and restored alongside the database backup.
 * Value types are preserved so that booleans, ints, longs, floats, strings and string sets
 * round-trip correctly.
 */
public class PreferencesTransporter {
    private static final String TAG = "PreferencesTransporter";

    private PreferencesTransporter() {
    }

    /**
     * Returns the same SharedPreferences instance that
     * androidx PreferenceManager.getDefaultSharedPreferences would return,
     * without pulling in the androidx.preference dependency.
     */
    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(
                context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    public static void exportToDocument(Uri uri, Context context) throws IOException {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "wt");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            exportToStream(out, context);
        }
    }

    /**
     * Writes the preferences JSON to the given stream. The caller is responsible for closing it.
     */
    public static void exportToStream(OutputStream out, Context context) throws IOException {
        try {
            out.write(serialize(context).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (JSONException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw new IOException("Unable to serialize preferences", e);
        }
    }

    private static String serialize(Context context) throws JSONException {
        SharedPreferences prefs = getPreferences(context);
        JSONObject root = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            if (value instanceof Boolean) {
                item.put("t", "b");
                item.put("v", (Boolean) value);
            } else if (value instanceof Integer) {
                item.put("t", "i");
                item.put("v", (Integer) value);
            } else if (value instanceof Long) {
                item.put("t", "l");
                item.put("v", (Long) value);
            } else if (value instanceof Float) {
                item.put("t", "f");
                item.put("v", ((Float) value).doubleValue());
            } else if (value instanceof String) {
                item.put("t", "s");
                item.put("v", (String) value);
            } else if (value instanceof Set) {
                item.put("t", "ss");
                JSONArray array = new JSONArray();
                for (Object element : (Set<?>) value) {
                    array.put(String.valueOf(element));
                }
                item.put("v", array);
            } else {
                continue; // unsupported type
            }
            root.put(entry.getKey(), item);
        }
        return root.toString(2);
    }

    public static void importBackup(Uri inputUri, Context context) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(inputUri)) {
            if (in == null) {
                throw new IOException("Unable to open preferences backup");
            }
            importFromStream(in, context);
        }
    }

    /**
     * Restores preferences from the given stream. The caller is responsible for closing it.
     */
    public static void importFromStream(InputStream in, Context context) throws IOException {
        String content = readStream(in);
        try {
            deserialize(content, context);
        } catch (JSONException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw new IOException("Preferences backup is not valid", e);
        }
    }

    private static void deserialize(String content, Context context) throws JSONException {
        JSONObject root = new JSONObject(content);
        SharedPreferences.Editor editor = getPreferences(context).edit();
        for (Iterator<String> it = root.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject item = root.getJSONObject(key);
            String type = item.getString("t");
            switch (type) {
                case "b":
                    editor.putBoolean(key, item.getBoolean("v"));
                    break;
                case "i":
                    editor.putInt(key, item.getInt("v"));
                    break;
                case "l":
                    editor.putLong(key, item.getLong("v"));
                    break;
                case "f":
                    editor.putFloat(key, (float) item.getDouble("v"));
                    break;
                case "s":
                    editor.putString(key, item.getString("v"));
                    break;
                case "ss":
                    JSONArray array = item.getJSONArray("v");
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < array.length(); i++) {
                        set.add(array.getString(i));
                    }
                    editor.putStringSet(key, set);
                    break;
                default:
                    Log.w(TAG, "Skipping unknown preference type '" + type + "' for key " + key);
                    break;
            }
        }
        editor.apply();
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = in.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }
}
