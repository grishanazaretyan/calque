package ge.planview;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/** Marks drawn over the sheets (pen, arrow, box, text). Kept in the app, one small file per PDF. */
final class Markup {
    static final int PEN = 0, ARROW = 1, RECT = 2, TEXT = 3;

    static final class Item {
        int type, page, color, n;
        float width;          // stroke width, or text size, in PDF points
        float[] pts = new float[8];   // x,y pairs in PDF points of the sheet
        String text;

        void add(float x, float y) {
            if (n + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[n++] = x;
            pts[n++] = y;
        }
    }

    static ArrayList<Item> load(File f) {
        ArrayList<Item> out = new ArrayList<>();
        try {
            if (!f.exists()) return out;
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.type = o.getInt("t");
                it.page = o.getInt("p");
                it.color = o.getInt("c");
                it.width = (float) o.getDouble("w");
                it.text = o.optString("s", null);
                JSONArray p = o.getJSONArray("pts");
                for (int j = 0; j + 1 < p.length(); j += 2) it.add((float) p.getDouble(j), (float) p.getDouble(j + 1));
                out.add(it);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void save(File f, ArrayList<Item> items) {
        try {
            if (items.isEmpty()) { f.delete(); return; }
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject().put("t", it.type).put("p", it.page).put("c", it.color).put("w", it.width);
                if (it.text != null) o.put("s", it.text);
                JSONArray p = new JSONArray();
                for (int j = 0; j < it.n; j++) p.put(Math.round(it.pts[j] * 100) / 100.0);
                a.put(o.put("pts", p));
            }
            f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
    }
}
