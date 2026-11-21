package ge.planview;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Small persistent things: recent files, last page and drawing scale per document. */
final class Store {
    static final class Recent { String uri, name; long time; }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("planview", Context.MODE_PRIVATE);
    }

    /** {display name, size} of a document. */
    static String[] describe(Context c, Uri uri) {
        String name = null;
        long size = -1;
        try {
            if ("file".equals(uri.getScheme())) {
                File f = new File(uri.getPath());
                name = f.getName();
                size = f.length();
            } else {
                try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
                    if (cur != null && cur.moveToFirst()) {
                        int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int si = cur.getColumnIndex(OpenableColumns.SIZE);
                        if (ni >= 0) name = cur.getString(ni);
                        if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
                    }
                }
            }
        } catch (Throwable ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "Document.pdf";
        return new String[]{name, String.valueOf(size)};
    }

    static boolean canReopenLater(Context c, Uri uri) {
        if ("file".equals(uri.getScheme())) return true;
        try {
            for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
                if (p.isReadPermission() && p.getUri().equals(uri)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    static List<Recent> recents(Context c) {
        List<Recent> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("recents", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Recent r = new Recent();
                r.uri = o.getString("uri");
                r.name = o.getString("name");
                r.time = o.optLong("time");
                out.add(r);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static void saveRecents(Context c, List<Recent> list) {
        try {
            JSONArray a = new JSONArray();
            for (Recent r : list) {
                a.put(new JSONObject().put("uri", r.uri).put("name", r.name).put("time", r.time));
            }
            prefs(c).edit().putString("recents", a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static void addRecent(Context c, Uri uri, String name) {
        List<Recent> list = recents(c);
        String u = uri.toString();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).uri.equals(u) || list.get(i).name.equals(name)) list.remove(i);
        }
        Recent r = new Recent();
        r.uri = u; r.name = name; r.time = System.currentTimeMillis();
        list.add(0, r);
        ContentResolver cr = c.getContentResolver();
        while (list.size() > 20) {
            Recent old = list.remove(list.size() - 1);
            try { cr.releasePersistableUriPermission(Uri.parse(old.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) { }
        }
        saveRecents(c, list);
    }

    static void removeRecent(Context c, String uri) {
        List<Recent> list = recents(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).uri.equals(uri)) list.remove(i);
        saveRecents(c, list);
    }

    static boolean continuous(Context c) { return prefs(c).getBoolean("continuous", true); }
    static void setContinuous(Context c, boolean on) { prefs(c).edit().putBoolean("continuous", on).apply(); }

    /** 0 = follow the phone, 1 = light, 2 = dark. */
    static int theme(Context c) { return prefs(c).getInt("theme", 0); }
    static void setTheme(Context c, int t) { prefs(c).edit().putInt("theme", t).apply(); }
    static int unit(Context c) { return prefs(c).getInt("unit", 0); }
    static void setUnit(Context c, int u) { prefs(c).edit().putInt("unit", u).apply(); }

    static boolean hairline(Context c) { return prefs(c).getBoolean("hairline", false); }
    static void setHairline(Context c, boolean on) { prefs(c).edit().putBoolean("hairline", on).apply(); }

    static boolean snap(Context c) { return prefs(c).getBoolean("snap", true); }
    static void setSnap(Context c, boolean on) { prefs(c).edit().putBoolean("snap", on).apply(); }

    static int lastPage(Context c, String docKey) { return prefs(c).getInt("page:" + docKey, 0); }
    static void setLastPage(Context c, String docKey, int page) { prefs(c).edit().putInt("page:" + docKey, page).apply(); }

    /** Drawing scale for a page: the page's own, else the document's last one, else 1:100. */
    static double ratio(Context c, String docKey, int page) {
        SharedPreferences p = prefs(c);
        float v = p.getFloat("scale:" + docKey + "#" + page, 0);
        if (v <= 0) v = p.getFloat("scale:" + docKey, 0);
        return v > 0 ? v : 100;
    }

    static boolean hasRatio(Context c, String docKey) {
        return prefs(c).contains("scale:" + docKey);
    }

    static void setRatio(Context c, String docKey, int page, double ratio) {
        prefs(c).edit().putFloat("scale:" + docKey + "#" + page, (float) ratio)
                .putFloat("scale:" + docKey, (float) ratio).apply();
    }
}
