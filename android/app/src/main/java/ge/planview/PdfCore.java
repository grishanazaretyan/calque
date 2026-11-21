package ge.planview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All PDFium work happens on one background thread. A few parsed sheets are kept open, so
 * zooming and panning only redraw and never re-read the file.
 */
final class PdfCore {

    interface Listener {
        void onOpened(float[] w, float[] h, String[] titles);
        void onNeedPassword(boolean wrong);
        /** Overview bitmap of one sheet (null if the sheet cannot be drawn). */
        void onBase(int page, Bitmap bm, int seq);
        void onPatch(Bitmap bm, float scale, int left, int top, int gen);
        void onRendering(boolean busy);
        void onError(String message);
        void onModeApplied();
        void onSnap(int page, SnapIndex index);
        /** A DXF was converted: the PDF that is shown and the drawing scale it was drawn at. */
        void onDxf(File pdf, double ratio);
    }

    /** What the view currently wants; a newer request replaces an older one. */
    static final class Req {
        int gen, ackSeq;
        int[] basePages = new int[0];      // sheets whose overview is missing, most wanted first
        int baseMaxPx = 1600;
        boolean wantPatch;                 // sharp render of the visible region
        float scale;
        int left, top, w, h, bgColor;      // region, in layout pixels at this zoom
        int[] pages, pageX, pageY;         // sheets inside the region and where they start
        float[] pageScale;                 // pixels per PDF point for each of them
        volatile boolean aborted;
    }

    private static final int MAX_PAGES = 4;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context app;
    private final Listener listener;
    private volatile boolean closed;

    // worker-thread state
    private ParcelFileDescriptor pfd;
    private volatile long doc;
    private float[] pw, ph;
    private int[] deliveredSeq;
    private int seq;
    private boolean busyShown, hairline;
    private float hairPx = 2;
    private final LinkedHashMap<Integer, Long> pages = new LinkedHashMap<>(8, 0.75f, true);
    private final Paint white = new Paint();

    // shared state
    private final Object lock = new Object();
    private Req pending, running;

    PdfCore(Context ctx, Listener l) {
        app = ctx.getApplicationContext();
        listener = l;
        white.setColor(0xFFFFFFFF);
    }

    private void post(Runnable r) {
        ui.post(() -> { if (!closed) r.run(); });
    }

    private void fail(String what, Throwable t) {
        String m = t != null ? t.getMessage() : null;
        post(() -> listener.onError(what + (m != null ? ": " + m : "")));
    }

    // ---------- opening ----------

    void open(Uri uri) {
        exec.execute(() -> {
            try {
                String kind = sniff(uri);
                if ("dxf".equals(kind) || "dwg".equals(kind)) {
                    File pdf = convertDrawing(uri, "dwg".equals(kind));
                    if (pdf == null) return;
                    pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                    opened(Pdfium.open(pfd.getFd(), pfd.getStatSize(), null), false);
                    return;
                }
                pfd = "file".equals(uri.getScheme())
                        ? ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY)
                        : app.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new java.io.IOException("no data");
                long h = Pdfium.open(pfd.getFd(), pfd.getStatSize(), null);
                if (h == Pdfium.ERR_UNREADABLE) {
                    // source is not a plain file (e.g. a cloud provider stream): copy it once
                    closeFd();
                    pfd = copyToCache(uri);
                    h = Pdfium.open(pfd.getFd(), pfd.getStatSize(), null);
                }
                opened(h, false);
            } catch (Throwable t) {
                fail("Cannot open this file", t);
            }
        });
    }

    // ---------- search and export ----------

    interface Result<T> { void done(T value); }

    void search(String query, Result<float[]> done) {
        exec.execute(() -> {
            float[] r = null;
            try { if (doc != 0) r = Pdfium.search(doc, query, 999); } catch (Throwable ignored) { }
            float[] res = r;
            post(() -> done.done(res));
        });
    }

    /** Writes the sheets of `source` with markup drawn in to `out`. */
    void exportMarked(Uri source, float[] ops, String[] texts, File out, Result<Boolean> done) {
        exec.execute(() -> {
            boolean ok = false;
            ParcelFileDescriptor fd = null;
            try {
                fd = "file".equals(source.getScheme())
                        ? ParcelFileDescriptor.open(new File(source.getPath()), ParcelFileDescriptor.MODE_READ_ONLY)
                        : app.getContentResolver().openFileDescriptor(source, "r");
                if (fd != null) {
                    ok = Pdfium.exportMarked(fd.getFd(), fd.getStatSize(), password, out.getAbsolutePath(),
                            ops, texts, font(false), font(true));
                    if (!ok) {
                        // not readable by offset (a cloud stream): retry from a local copy
                        fd.close();
                        fd = copyToCache(source, "export-source.pdf");
                        ok = Pdfium.exportMarked(fd.getFd(), fd.getStatSize(), password, out.getAbsolutePath(),
                                ops, texts, font(false), font(true));
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (fd != null) fd.close(); } catch (Exception ignored) { }
            }
            boolean res = ok;
            post(() -> done.done(res));
        });
    }

    // ---------- DXF ----------

    /** "pdf", "dxf", "dwg" or "other", from the first bytes of the file. */
    private String sniff(Uri uri) {
        byte[] b = new byte[1024];
        int n = 0;
        try (InputStream in = openStream(uri)) {
            int r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
        } catch (Exception e) {
            return "other";
        }
        String head = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (head.contains("%PDF")) return "pdf";
        if (head.startsWith("AC10") || head.startsWith("AC1.")) return "dwg";
        if (head.startsWith("AutoCAD Binary DXF")) return "dxf";
        String t = head.replace("\uFEFF", "").replace("\u00EF\u00BB\u00BF", "").trim();
        if ((t.startsWith("0") || t.startsWith("999")) && head.contains("SECTION")) return "dxf";
        return "other";
    }

    private InputStream openStream(Uri uri) throws Exception {
        if ("file".equals(uri.getScheme())) return new java.io.FileInputStream(new File(uri.getPath()));
        InputStream in = app.getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("no data");
        return in;
    }

    /** Converts the DXF or DWG once (cached by file identity) and returns the PDF, or null after an error. */
    private File convertDrawing(Uri uri, boolean dwg) throws Exception {
        File dir = new File(app.getCacheDir(), "dxf");
        dir.mkdirs();
        String[] d = Store.describe(app, uri);
        String id = Integer.toHexString((uri + "|" + d[1] + "|" + modified(uri)).hashCode());
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                String name = f.getName();
                if (name.startsWith(id + "_") && name.endsWith(".pdf") && f.length() > 0) {
                    double r = Double.parseDouble(name.substring(id.length() + 1, name.length() - 4));
                    post(() -> listener.onDxf(f, r));
                    return f;
                }
            }
            // keep the cache small: the ten most recent conversions
            java.util.Arrays.sort(old, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 9; i < old.length; i++) old[i].delete();
        }
        post(() -> listener.onRendering(true));
        File src = new File(dir, dwg ? "source.dwg" : "source.dxf");
        try (InputStream in = openStream(uri); OutputStream out = new FileOutputStream(src)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        File tmp = new File(dir, "converting.pdf");
        double r = dwg
                ? Pdfium.dwgToPdf(src.getAbsolutePath(), new File(dir, "converting.dxf").getAbsolutePath(),
                        tmp.getAbsolutePath(), font(false), font(true))
                : Pdfium.dxfToPdf(src.getAbsolutePath(), tmp.getAbsolutePath(), font(false), font(true));
        src.delete();
        post(() -> listener.onRendering(false));
        if (r <= 0) {
            tmp.delete();
            int e = (int) -r;
            String why = e == 2 ? "it is a binary DXF. Save it again as an ASCII (text) DXF."
                    : e == 3 ? "it is not a DXF drawing."
                    : e == 4 ? "there is nothing visible in its model space."
                    : e == 6 ? "the drawing is too big for this device's memory."
                    : e == 7 ? "this DWG version or file could not be read. Try saving it as DXF."
                    : "it could not be read.";
            String what = dwg ? "DWG" : "DXF";
            post(() -> listener.onError("Cannot open this " + what + ": " + why));
            return null;
        }
        File out = new File(dir, id + "_" + r + ".pdf");
        if (!tmp.renameTo(out)) out = tmp;
        File f = out;
        post(() -> listener.onDxf(f, r));
        return out;
    }

    private long modified(Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).lastModified();
        try (android.database.Cursor c = app.getContentResolver().query(uri,
                new String[]{android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) { }
        return 0;
    }

    /** A system font for text that is not plain Latin: Georgian, or general (Cyrillic etc.). */
    private static String font(boolean georgian) {
        File dir = new File("/system/fonts");
        String[] names = dir.list();
        if (names == null) return null;
        java.util.Arrays.sort(names);
        String[][] wanted = georgian
                ? new String[][]{{"NotoSansGeorgian", ".ttf"}, {"NotoSansGeorgian", ".otf"}, {"NotoSerifGeorgian", ".ttf"}}
                : new String[][]{{"Roboto-Regular", ".ttf"}, {"RobotoStatic-Regular", ".ttf"}, {"NotoSans-Regular", ".ttf"}, {"DroidSans", ".ttf"}};
        for (String[] w : wanted) {
            for (String n : names) {
                if (n.startsWith(w[0]) && n.endsWith(w[1]) && !n.contains("Bold") && !n.contains("UI-")) {
                    return new File(dir, n).getAbsolutePath();
                }
            }
        }
        return null;
    }

    private ParcelFileDescriptor copyToCache(Uri uri) throws Exception {
        return copyToCache(uri, "current.pdf");
    }

    private ParcelFileDescriptor copyToCache(Uri uri, String name) throws Exception {
        File tmp = new File(app.getCacheDir(), name);
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new java.io.IOException("no data");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private volatile String password;

    void authenticate(String password) {
        this.password = password;
        exec.execute(() -> {
            try {
                opened(Pdfium.open(pfd.getFd(), pfd.getStatSize(), password), true);
            } catch (Throwable t) {
                fail("Cannot open this file", t);
            }
        });
    }

    private void opened(long h, boolean afterPassword) {
        if (h == Pdfium.ERR_PASSWORD) {
            post(() -> listener.onNeedPassword(afterPassword));
            return;
        }
        if (Pdfium.isError(h)) {
            String why = h == -3 ? "the file is damaged or not a PDF"
                    : h == -5 ? "unsupported encryption" : "error " + (-h);
            post(() -> listener.onError("Cannot open this file: " + why));
            return;
        }
        doc = h;
        describe();
    }

    /** Reads sheet sizes and titles (bookmarks, else page labels). */
    private void describe() {
        int n = Pdfium.pageCount(doc);
        pw = new float[n];
        ph = new float[n];
        deliveredSeq = new int[n];
        String[] titles = new String[n];
        for (int i = 0; i < n; i++) {
            float[] s = Pdfium.pageSize(doc, i);
            boolean ok = s != null && s[0] >= 1 && s[1] >= 1;
            pw[i] = ok ? s[0] : 595;
            ph[i] = ok ? s[1] : 842;
            String label = Pdfium.pageLabel(doc, i);
            if (label != null && !label.trim().isEmpty() && !label.trim().equals(String.valueOf(i + 1))) {
                titles[i] = label.trim();
            }
        }
        try {
            // the deepest bookmark pointing at a sheet names it (folders point at their first sheet)
            Object[] o = Pdfium.outline(doc);
            int[] depth = (int[]) o[0], page = (int[]) o[1];
            String[] title = (String[]) o[2];
            int[] best = new int[n];
            for (int k = 0; k < title.length; k++) {
                int p = page[k];
                String t = title[k] == null ? "" : title[k].trim().replaceAll("\\s+", " ");
                if (p >= 0 && p < n && !t.isEmpty() && depth[k] > best[p]) {
                    titles[p] = t;
                    best[p] = depth[k];
                }
            }
        } catch (Throwable ignored) { }
        final float[] fw = pw.clone(), fh = ph.clone();
        post(() -> listener.onOpened(fw, fh, titles));
    }

    private void closeFd() {
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        pfd = null;
    }

    // ---------- pages ----------

    /** An open sheet, kept for the next renders (0 if it cannot be read). */
    private long page(int index) {
        Long p = pages.get(index);
        if (p != null) return p;
        long h = Pdfium.loadPage(doc, index);
        if (h == 0) return 0;
        pages.put(index, h);
        if (pages.size() > MAX_PAGES) {
            Iterator<Map.Entry<Integer, Long>> it = pages.entrySet().iterator();
            Map.Entry<Integer, Long> eldest = it.next();
            it.remove();
            Pdfium.closePage(eldest.getValue());
        }
        return h;
    }

    private void closePages() {
        for (long h : pages.values()) Pdfium.closePage(h);
        pages.clear();
    }

    /** Collects the line geometry of a sheet for snapping (null index if it cannot be read). */
    void requestSnap(int index) {
        if (closed) return;
        exec.execute(() -> {
            if (closed || doc == 0 || pw == null || index < 0 || index >= pw.length) return;
            SnapIndex ix = null;
            try {
                long p = page(index);
                float[] seg = p == 0 ? null : Pdfium.geometry(p, SnapIndex.MAX);
                if (seg != null) {
                    ix = new SnapIndex();
                    ix.setSegments(seg);
                    ix.build(pw[index], ph[index]);
                }
            } catch (Throwable ignored) { }
            final SnapIndex out = ix;
            post(() -> listener.onSnap(index, out));
        });
    }

    /**
     * Switches hairline drawing (lines drawn thickPx pixels wide at any zoom);
     * onModeApplied tells the view when to throw its bitmaps away.
     */
    void setHairline(boolean on, float thickPx) {
        if (closed) return;
        synchronized (lock) {
            pending = null;
            abortRunning();
        }
        exec.execute(() -> {
            hairPx = thickPx;
            if (hairline != on) {
                hairline = on;
                // reopen the sheets so their original line widths come back
                closePages();
            }
            post(listener::onModeApplied);
        });
    }

    // ---------- rendering ----------

    /** UI thread: tells the worker what the screen needs now. */
    void request(Req r) {
        if (closed) return;
        synchronized (lock) {
            pending = r;
            // Interrupt a sharp render in progress only when it no longer matches the view
            // (other zoom or layout). While panning we let it finish so the screen keeps filling in.
            if (running != null && (running.gen != r.gen || !r.wantPatch || running.scale != r.scale)) {
                abortRunning();
            }
        }
        exec.execute(this::drain);
    }

    private void abortRunning() {
        if (running == null) return;
        running.aborted = true;
        long d = doc;
        if (d != 0) Pdfium.abort(d, true);
    }

    private void busy(boolean on) {
        if (busyShown == on) return;
        busyShown = on;
        post(() -> listener.onRendering(on));
    }

    private void drain() {
        while (!closed && doc != 0 && pw != null) {
            final Req r;
            synchronized (lock) { r = pending; }
            if (r == null) break;

            // 1) overviews first, one at a time, so every visible sheet shows something quickly
            int p = -1;
            for (int c : r.basePages) {
                if (c >= 0 && c < pw.length && deliveredSeq[c] <= r.ackSeq) { p = c; break; }
            }
            if (p >= 0) {
                busy(true);
                renderBase(p, r.baseMaxPx);
                continue;
            }

            // 2) then the sharp patch for the visible region
            synchronized (lock) {
                if (pending != r) continue;
                pending = null;
                if (!r.wantPatch) break;
                running = r;
                Pdfium.abort(doc, false);
            }
            busy(true);
            Bitmap bm = null;
            boolean ok = false;
            try {
                bm = obtain(r.w, r.h);
                ok = drawPatch(bm, r);
            } catch (Throwable ignored) {
                // out of memory or a broken sheet: keep showing the overview
            }
            synchronized (lock) { running = null; }
            final Bitmap out = bm;
            if (ok && !r.aborted) {
                post(() -> listener.onPatch(out, r.scale, r.left, r.top, r.gen));
            } else if (out != null) {
                giveBack(out);
            }
        }
        boolean more;
        synchronized (lock) { more = pending != null; }
        if (!more) busy(false);
    }

    private void renderBase(int index, int maxPx) {
        Bitmap bm = null;
        try {
            float w = pw[index], h = ph[index];
            float s = Math.min(maxPx / Math.max(w, h), (float) Math.sqrt(2.2e6 / (w * h)));
            int bw = Math.max(1, Math.round(w * s)), bh = Math.max(1, Math.round(h * s));
            bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            long pg = page(index);
            if (pg == 0 || !Pdfium.render(pg, bm, 0, 0, bw, bh, 0, 0, bw, bh, s,
                    hairline ? hairPx : 0, false)) {
                bm = null;
            }
        } catch (Throwable t) {
            bm = null;
        }
        final int sq = ++seq;
        deliveredSeq[index] = sq;
        final Bitmap out = bm;
        post(() -> listener.onBase(index, out, sq));
    }

    /** Draws every sheet that falls inside the region. False if aborted. */
    private boolean drawPatch(Bitmap bm, Req r) {
        bm.eraseColor(r.bgColor);
        Canvas cv = new Canvas(bm);
        for (int j = 0; j < r.pages.length; j++) {
            if (r.aborted) return false;
            int index = r.pages[j];
            float ps = r.pageScale[j];
            int sx = r.pageX[j] - r.left, sy = r.pageY[j] - r.top;
            int sw = Math.round(pw[index] * ps), sh = Math.round(ph[index] * ps);
            int x0 = Math.max(0, sx), y0 = Math.max(0, sy);
            int x1 = Math.min(r.w, sx + sw), y1 = Math.min(r.h, sy + sh);
            if (x1 <= x0 || y1 <= y0) continue;
            long pg = page(index);
            if (pg == 0) {
                cv.drawRect(x0, y0, x1, y1, white);
                continue;
            }
            if (!Pdfium.render(pg, bm, x0, y0, x1, y1, sx, sy, sw, sh, ps, hairline ? hairPx : 0, true)) {
                return false;
            }
        }
        return !r.aborted;
    }

    private Bitmap spare;

    private Bitmap obtain(int w, int h) {
        synchronized (lock) {
            if (spare != null) {
                Bitmap s = spare;
                spare = null;
                if (!s.isRecycled() && s.getWidth() == w && s.getHeight() == h) return s;
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    /** Returns a bitmap that is no longer shown, so the next render can reuse its memory. */
    void giveBack(Bitmap bm) {
        synchronized (lock) { spare = bm; }
    }

    void close() {
        closed = true;
        synchronized (lock) {
            pending = null;
            abortRunning();
        }
        exec.execute(() -> {
            try {
                closePages();
                if (doc != 0) Pdfium.close(doc);
            } catch (Throwable ignored) { }
            doc = 0;
            closeFd();
        });
        exec.shutdown();
    }
}
