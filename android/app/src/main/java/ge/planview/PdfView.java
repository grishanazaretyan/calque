package ge.planview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;

/**
 * Shows the sheets either as one continuous vertical strip or one sheet at a time:
 * pan, pinch zoom, sharp re-render of the visible part, and the measuring overlay.
 *
 * Layout space: every sheet is scaled to a common width W, sheets are stacked with a gap.
 * screen = layout * scale + offset;  sheet point = (layout - sheetTop) / k[sheet].
 */
public class PdfView extends View {

    interface Listener {
        void onSingleTap();
        void onMeasureChanged();
        void onPageChanged(int page);
        double ratioOf(int page);
        void onMarkupChanged();
        /** A tap on empty space with the Text tool: write a new note there. */
        void onTextRequested(int page, float x, float y, float screenX, float screenY);
        /** A tap on the selected note: edit its wording in place. */
        void onTextEdit(Markup.Item item);
        /** Any touch on the drawing while marking. True if it closed a note being typed. */
        boolean onCanvasTouch();
        /** The selected note changed (null: none). */
        void onSelectionChanged(Markup.Item item);
    }

    static final int MODE_DISTANCE = 0, MODE_AREA = 1;
    private static final float MM_PER_PT = 25.4f / 72f;
    private static final float MAX_SCALE = 480f;

    private PdfCore core;
    private Listener listener;
    private final float dp;

    // document
    private int n;
    private float[] pw, ph;
    private boolean continuous = true;
    private int lo, hi;              // sheets present in the layout
    private float W, docH;
    private float[] k;
    private double[] top;   // doubles: at deep zoom in a long document floats lose whole pixels
    private int gen;
    private int current = -1;

    // bitmaps
    private final SparseArray<Bitmap> bases = new SparseArray<>();
    private final SparseArray<Boolean> failed = new SparseArray<>();
    private int ackSeq;
    private Bitmap patch, retired;
    private float patchScale;
    private int patchLeft, patchTop;

    // view transform
    private float scale = 1;
    private double ox, oy;
    private int padTop, padBottom;
    private boolean atFit = true, laidOut;
    private boolean scaling, scaledInGesture;

    // measuring
    private boolean measuring;
    private int mode = MODE_DISTANCE;
    private int mPage = -1;
    private final ArrayList<float[]> pts = new ArrayList<>();

    /** Measurements closed with Finish; they stay on the sheet until Clear or leaving measure mode. */
    private static final class Meas { int page, mode; ArrayList<float[]> pts; }
    private final ArrayList<Meas> done = new ArrayList<>();

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pagePaint = new Paint();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint livePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path path = new Path();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private final OverScroller scroller;
    private ValueAnimator zoomAnim;

    private final Runnable syncRunnable = this::sync;

    public PdfView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        pagePaint.setColor(0xFFFFFFFF);
        linePaint.setColor(Ui.MARK);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * dp);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        livePaint.set(linePaint);
        livePaint.setStrokeWidth(2f * dp);
        livePaint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
        fillPaint.setColor((Ui.MARK & 0x00FFFFFF) | 0x33000000);
        dotPaint.setColor(0xFFFFFFFF);
        dotRing.setColor(Ui.MARK);
        dotRing.setStyle(Paint.Style.STROKE);
        dotRing.setStrokeWidth(2f * dp);
        crossPaint.setColor(0xFFE0115F);
        crossPaint.setStrokeWidth(1.5f * dp);
        crossPaint.setStyle(Paint.Style.STROKE);
        crossHalo.setColor(0xCCFFFFFF);
        crossHalo.setStrokeWidth(3.5f * dp);
        crossHalo.setStyle(Paint.Style.STROKE);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(13 * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);
        tagPaint.setColor(0xE61A1C1F);

        scroller = new OverScroller(c);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                scaling = true;
                scaledInGesture = true;
                stopMotion();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAbout(d.getFocusX(), d.getFocusY(), scale * d.getScaleFactor());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
                scheduleSync(0);
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                stopMotion();
                scaledInGesture = false;
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling) return false;
                ox -= dx;
                oy -= dy;
                atFit = false;
                changed(0);
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaling || n == 0) return false;
                float[] b = bounds();
                scroller.fling((int) Math.round(ox), (int) Math.round(oy), Math.round(vx), Math.round(vy),
                        Math.round(b[0]), Math.round(b[1]), Math.round(b[2]), Math.round(b[3]));
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onDoubleTapEvent(MotionEvent e) {
                // act on the release of the second tap, unless it became a double-tap-and-drag zoom
                if (e.getActionMasked() != MotionEvent.ACTION_UP || scaledInGesture || n == 0) return false;
                float fit = fitScale();
                animateZoom(e.getX(), e.getY(), scale < fit * 1.6f ? Math.min(MAX_SCALE, scale * 3.5f) : fit);
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onSingleTap();
                return true;
            }
        });
    }

    void attach(PdfCore core, Listener l) {
        this.core = core;
        this.listener = l;
    }

    // ---------- document and layout ----------

    void setDocument(float[] w, float[] h, boolean continuousMode, int startPage) {
        n = w.length;
        pw = w;
        ph = h;
        continuous = continuousMode;
        k = new float[n];
        top = new double[n];
        bases.clear();
        failed.clear();
        current = -1;
        relayout(Math.max(0, Math.min(n - 1, startPage)));
    }

    boolean isContinuous() { return continuous; }

    void setContinuous(boolean on) {
        if (n == 0 || on == continuous) { continuous = on; return; }
        int p = currentPage();
        continuous = on;
        relayout(p);
    }

    /** Rebuilds the layout and shows the given sheet fitted to the screen. */
    private void relayout(int page) {
        stopMotion();
        gen++;
        patch = null;
        retired = null;
        pts.clear();
        mPage = -1;
        if (continuous) { lo = 0; hi = n - 1; } else { lo = hi = page; }
        W = 0;
        for (int i = lo; i <= hi; i++) W = Math.max(W, pw[i]);
        float gap = W * 0.012f;
        double y = 0;
        for (int i = lo; i <= hi; i++) {
            k[i] = W / pw[i];
            top[i] = y;
            y += ph[i] * k[i] + gap;
        }
        docH = (float) (y - gap);
        laidOut = getWidth() > 0;
        if (laidOut) {
            scale = fitScale();
            atFit = true;
            showTop(page);
        }
        current = page;
        if (listener != null) listener.onPageChanged(page);
    }

    private void showTop(int page) {
        ox = (getWidth() - W * scale) / 2;
        oy = padTop + 8 * dp - top[page] * scale;
        changed(0);
    }

    void goToPage(int page) {
        if (n == 0) return;
        page = Math.max(0, Math.min(n - 1, page));
        if (continuous) {
            stopMotion();
            showTop(page);
        } else if (page != lo) {
            relayout(page);
        }
    }

    /** The sheet under the middle of the visible area. */
    int currentPage() {
        if (n == 0) return 0;
        if (!continuous || !laidOut) return lo;
        return pageAt((centerY() - oy) / scale);
    }

    private int pageAt(double layoutY) {
        int a = lo, b = hi;
        while (a < b) {
            int m = (a + b + 1) >>> 1;
            if (top[m] <= layoutY) a = m; else b = m - 1;
        }
        // in the gap below a sheet, pick whichever neighbour is closer
        if (a < hi && layoutY > top[a] + ph[a] * k[a] && top[a + 1] - layoutY < layoutY - (top[a] + ph[a] * k[a])) a++;
        return a;
    }

    /** Space covered by the floating bars; the drawing is fitted and aimed inside the rest. */
    void setPads(int topPx, int bottomPx) {
        if (topPx == padTop && bottomPx == padBottom) return;
        padTop = topPx;
        padBottom = bottomPx;
        if (!laidOut || n == 0) return;
        if (atFit && !continuous) { scale = fitScale(); }
        changed(0);
    }

    private float centerY() { return (padTop + getHeight() - padBottom) / 2f; }

    private float fitScale() {
        if (W <= 0 || getWidth() == 0) return 1;
        float m = 8 * dp, byWidth = (getWidth() - 2 * m) / W;
        if (continuous) return byWidth;
        return Math.min(byWidth, (getHeight() - padTop - padBottom - 2 * m) / docH);
    }

    private float minScale() {
        return fitScale() * (continuous ? 0.4f : 0.7f);
    }

    /** Allowed range of the offset: {minX, maxX, minY, maxY}. */
    private float[] bounds() {
        float vw = getWidth(), cw = W * scale, ch = docH * scale, m = 8 * dp;
        float safeTop = padTop, safeBottom = getHeight() - padBottom;
        float minX, maxX, minY, maxY;
        if (measuring) {
            // any point of a sheet must be able to reach the cross
            minX = vw / 2 - cw; maxX = vw / 2;
            minY = centerY() - ch; maxY = centerY();
        } else {
            if (cw <= vw - 2 * m) minX = maxX = (vw - cw) / 2; else { minX = vw - m - cw; maxX = m; }
            if (ch <= safeBottom - safeTop - 2 * m) minY = maxY = (safeTop + safeBottom - ch) / 2;
            else { minY = safeBottom - m - ch; maxY = safeTop + m; }
        }
        return new float[]{minX, maxX, minY, maxY};
    }

    private void clamp() {
        float[] b = bounds();
        ox = Math.max(b[0], Math.min(b[1], ox));
        oy = Math.max(b[2], Math.min(b[3], oy));
    }

    private void changed(int syncDelay) {
        if (n == 0 || !laidOut) return;
        clamp();
        invalidate();
        scheduleSync(syncDelay);
        int p = currentPage();
        if (p != current) {
            current = p;
            if (listener != null) listener.onPageChanged(p);
        }
        if (measuring) ensureSnap();
        if (measuring && listener != null) listener.onMeasureChanged();
    }

    private void zoomAbout(float fx, float fy, float newScale) {
        if (n == 0) return;
        newScale = Math.max(minScale(), Math.min(MAX_SCALE, newScale));
        double px = (fx - ox) / scale, py = (fy - oy) / scale;
        scale = newScale;
        ox = fx - px * scale;
        oy = fy - py * scale;
        atFit = Math.abs(scale - fitScale()) < 1e-4f;
        changed(120);
    }

    private void stopMotion() {
        scroller.forceFinished(true);
        if (zoomAnim != null) { zoomAnim.cancel(); zoomAnim = null; }
    }

    private void animateZoom(float fx, float fy, float target) {
        stopMotion();
        final float from = scale;
        zoomAnim = ValueAnimator.ofFloat(0, 1).setDuration(220);
        zoomAnim.setInterpolator(new DecelerateInterpolator());
        zoomAnim.addUpdateListener(a -> zoomAbout(fx, fy, from + (target - from) * (float) a.getAnimatedValue()));
        zoomAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (n == 0 || w == 0) return;
        if (!laidOut) {
            laidOut = true;
            scale = fitScale();
            atFit = true;
            showTop(continuous ? Math.max(lo, current) : lo);
        } else if (atFit) {
            int p = currentPage();
            scale = fitScale();
            showTop(p);
        } else {
            // keep the same point of the drawing in the middle
            double px = (ow / 2f - ox) / scale, py = (oh / 2f - oy) / scale;
            ox = w / 2f - px * scale;
            oy = h / 2f - py * scale;
            changed(0);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            ox = scroller.getCurrX();
            oy = scroller.getCurrY();
            changed(0);
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (n == 0) return true;
        if (marking) return markTouch(e);
        scaleDetector.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    // ---------- bitmaps ----------

    private float sheetTop(int i) { return (float) (oy + top[i] * scale); }
    private float sheetBottom(int i) { return (float) (oy + (top[i] + ph[i] * k[i]) * scale); }

    private int firstVisible() {
        int a = pageAt(-oy / scale);
        while (a > lo && sheetBottom(a - 1) > 0) a--;
        return a;
    }

    private void scheduleSync(int delayMs) {
        removeCallbacks(syncRunnable);
        if (delayMs <= 0) post(syncRunnable); else postDelayed(syncRunnable, delayMs);
    }

    /** Tells the renderer which overviews are missing and whether a sharp patch is needed. */
    private void sync() {
        if (n == 0 || core == null || !laidOut || getWidth() == 0 || scaling) return;
        int vw = getWidth(), vh = getHeight();
        int first = firstVisible(), last = first;
        while (last < hi && sheetTop(last + 1) < vh) last++;

        // forget overviews far from the screen
        for (int i = bases.size() - 1; i >= 0; i--) {
            int p = bases.keyAt(i);
            if (p < first - 3 || p > last + 3) bases.removeAt(i);
        }

        PdfCore.Req r = new PdfCore.Req();
        r.gen = gen;
        r.ackSeq = ackSeq;
        r.bgColor = Ui.BG;
        r.baseMaxPx = Math.max(1200, Math.min(2000, Math.max(vw, vh)));
        ArrayList<Integer> want = new ArrayList<>();
        boolean needSharp = false;
        for (int i = first; i <= last; i++) {
            Bitmap b = bases.get(i);
            if (b == null) { if (failed.get(i) == null) want.add(i); }
            else if (scale * k[i] > b.getWidth() / pw[i] * 1.02f) needSharp = true;
        }
        for (int i : new int[]{last + 1, first - 1}) {
            if (i >= lo && i <= hi && bases.get(i) == null && failed.get(i) == null) want.add(i);
        }
        r.basePages = new int[want.size()];
        for (int i = 0; i < r.basePages.length; i++) r.basePages[i] = want.get(i);

        if (!needSharp) {
            // the overview bitmaps are sharp enough at this zoom
            if (patch != null) { patch = null; retired = null; invalidate(); }
        } else {
            int tw = (int) Math.ceil(W * scale), th = (int) Math.ceil(docH * scale);
            int vl = Math.max(0, (int) Math.floor(-ox)), vt = Math.max(0, (int) Math.floor(-oy));
            int vr = Math.min(tw, (int) Math.ceil(vw - ox)), vb = Math.min(th, (int) Math.ceil(vh - oy));
            boolean covered = patch != null && patchScale == scale
                    && patchLeft <= vl && patchTop <= vt
                    && patchLeft + patch.getWidth() >= vr && patchTop + patch.getHeight() >= vb;
            if (vr > vl && vb > vt && !covered) {
                // render a bit more than the screen so small pans stay sharp
                int mx = Math.round(vw * 0.12f), my = Math.round(vh * 0.12f);
                r.w = Math.min(tw, vw + 2 * mx);
                r.h = Math.min(th, vh + 2 * my);
                r.left = Math.max(0, Math.min(tw - r.w, (int) Math.round(-ox) - mx));
                r.top = Math.max(0, Math.min(th - r.h, (int) Math.round(-oy) - my));
                r.scale = scale;
                r.wantPatch = true;
                int a = pageAt((double) r.top / scale), z = a;
                while (a > lo && (top[a - 1] + ph[a - 1] * k[a - 1]) * scale > r.top) a--;
                while (z < hi && top[z + 1] * scale < r.top + r.h) z++;
                int cnt = z - a + 1;
                r.pages = new int[cnt]; r.pageX = new int[cnt]; r.pageY = new int[cnt];
                r.pageScale = new float[cnt];
                for (int i = 0; i < cnt; i++) {
                    r.pages[i] = a + i;
                    r.pageX[i] = 0;
                    r.pageY[i] = (int) Math.round(top[a + i] * scale);
                    r.pageScale[i] = scale * k[a + i];
                }
            }
        }
        core.request(r);
    }

    /** Drops every rendered bitmap so the sheets are drawn again (after a render setting changed). */
    void rerender() {
        gen++;
        bases.clear();
        failed.clear();
        patch = null;
        retired = null;
        invalidate();
        scheduleSync(0);
    }

    void setBase(int page, Bitmap bm, int seq) {
        ackSeq = Math.max(ackSeq, seq);
        if (page < 0 || page >= n) return;
        if (bm == null) failed.put(page, Boolean.TRUE); else bases.put(page, bm);
        invalidate();
        scheduleSync(0);
    }

    void setPatch(Bitmap bm, float s, int left, int topPx, int generation) {
        if (generation != gen) return;
        // A bitmap is only handed back for reuse two swaps later, so a frame that is
        // still being drawn never sees it change.
        if (retired != null && core != null) core.giveBack(retired);
        retired = patch;
        patch = bm;
        patchScale = s;
        patchLeft = left;
        patchTop = topPx;
        invalidate();
    }

    // ---------- measuring ----------

    void setMeasuring(boolean on) {
        measuring = on;
        if (on) ensureSnap();
        if (laidOut) changed(0);
    }

    boolean isMeasuring() { return measuring; }

    void setMode(int m) { mode = m; invalidate(); }
    int getMode() { return mode; }

    int pointCount() { return pts.size(); }

    /** The sheet being measured: the one of the first point, else the one under the cross. */
    int measurePage() {
        if (!pts.isEmpty() && mPage >= lo && mPage <= hi) return mPage;
        if (n == 0 || !laidOut) return Math.max(0, lo);
        return pageAt((centerY() - oy) / scale);
    }

    private double ratio() { return ratio(measurePage()); }

    private double ratio(int page) {
        return listener != null ? listener.ratioOf(page) : 100;
    }

    /** Cross position in points of the measured sheet. */
    private float[] cursor() {
        int p = measurePage();
        double lx = (getWidth() / 2f - ox) / scale, ly = (centerY() - oy) / scale;
        float x = (float) (lx / k[p]), y = (float) ((ly - top[p]) / k[p]);
        snapKind = 0;
        if (snap) {
            SnapIndex ix = snaps.get(p);
            float[] hit = ix == null ? null : ix.query(x, y, 16 * dp / (scale * k[p]));
            if (hit != null) {
                snapKind = (int) hit[2];
                return new float[]{hit[0], hit[1]};
            }
        }
        return new float[]{x, y};
    }

    // snapping to the drawing's own lines
    private boolean snap = true;
    private int snapKind, snapPending = -1;
    private final SparseArray<SnapIndex> snaps = new SparseArray<>();
    private static final SnapIndex NONE = new SnapIndex();

    boolean isSnap() { return snap; }

    void setSnap(boolean on) {
        snap = on;
        ensureSnap();
        invalidate();
    }

    /** Asks for the geometry of the sheet being measured, once. */
    private void ensureSnap() {
        if (!measuring || !snap || core == null || n == 0 || !laidOut) return;
        int p = measurePage();
        if (snaps.get(p) == null && snapPending != p) {
            snapPending = p;
            core.requestSnap(p);
        }
    }

    void setSnapIndex(int page, SnapIndex ix) {
        if (snaps.size() >= 3) snaps.removeAt(0);
        snaps.put(page, ix != null ? ix : NONE);
        if (snapPending == page) snapPending = -1;
        ensureSnap();
        invalidate();
        if (measuring && listener != null) listener.onMeasureChanged();
    }

    /** Finished measurements as text, to keep them between sessions. */
    String exportMeasures() {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Meas m : done) {
                org.json.JSONArray p = new org.json.JSONArray();
                for (float[] q : m.pts) { p.put(q[0]); p.put(q[1]); }
                a.put(new org.json.JSONObject().put("p", m.page).put("m", m.mode).put("pts", p));
            }
            return a.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    void importMeasures(String json) {
        done.clear();
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Meas m = new Meas();
                m.page = o.getInt("p");
                m.mode = o.getInt("m");
                m.pts = new ArrayList<>();
                org.json.JSONArray p = o.getJSONArray("pts");
                for (int j = 0; j + 1 < p.length(); j += 2) m.pts.add(new float[]{(float) p.getDouble(j), (float) p.getDouble(j + 1)});
                if (m.page >= 0 && m.pts.size() >= 2) done.add(m);
            }
        } catch (Throwable ignored) { }
        invalidate();
    }

    void addPoint() {
        if (n == 0 || !laidOut) return;
        int p = measurePage();
        float[] c = cursor();
        mPage = p;
        pts.add(c);
        invalidate();
    }

    void undoPoint() {
        if (!pts.isEmpty()) {
            pts.remove(pts.size() - 1);
        } else if (!done.isEmpty()) {
            // reopen the last finished measurement
            Meas m = done.remove(done.size() - 1);
            pts.addAll(m.pts);
            mPage = m.page;
            mode = m.mode;
        }
        invalidate();
    }

    void clearPoints() {
        pts.clear();
        done.clear();
        mPage = -1;
        invalidate();
    }

    /** Closes the current line or area and keeps it on the sheet. False if it has too few points. */
    boolean finishMeasure() {
        if (pts.size() < (mode == MODE_AREA ? 3 : 2)) return false;
        Meas m = new Meas();
        m.page = measurePage();
        m.mode = mode;
        m.pts = new ArrayList<>(pts);
        done.add(m);
        pts.clear();
        mPage = -1;
        invalidate();
        return true;
    }

    int finishedCount() { return done.size(); }

    private static double dist(float[] a, float[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private double toMm(double pt) { return pt * MM_PER_PT * ratio(); }

    /** Length of the placed polyline, in real millimetres. */
    double placedLengthMm() {
        double d = 0;
        for (int i = 1; i < pts.size(); i++) d += dist(pts.get(i - 1), pts.get(i));
        return toMm(d);
    }

    /** Distance from the last placed point to the cross, in real millimetres. */
    double liveLengthMm() {
        if (pts.isEmpty() || n == 0) return 0;
        return toMm(dist(pts.get(pts.size() - 1), cursor()));
    }

    /** Paper length (PDF points) of the last placed segment, for calibration. */
    double lastSegmentPt() {
        ArrayList<float[]> p = pts;
        if (p.size() < 2 && !done.isEmpty()) p = done.get(done.size() - 1).pts;
        int c = p.size();
        return c < 2 ? 0 : dist(p.get(c - 2), p.get(c - 1));
    }

    static double ratioFor(double segmentPt, double realMm) {
        return realMm / (segmentPt * MM_PER_PT);
    }

    /** Polygon through the placed points plus the cross: {area m2, perimeter mm}. */
    double[] liveArea() {
        ArrayList<float[]> p = new ArrayList<>(pts);
        if (n > 0 && laidOut) p.add(cursor());
        int c = p.size();
        if (c < 3) return new double[]{0, 0};
        double a = 0, per = 0;
        for (int i = 0; i < c; i++) {
            float[] u = p.get(i), v = p.get((i + 1) % c);
            a += (double) u[0] * v[1] - (double) v[0] * u[1];
            per += dist(u, v);
        }
        double mm = MM_PER_PT * ratio();
        return new double[]{Math.abs(a) / 2 * mm * mm / 1e6, per * mm};
    }

    static String fmtMm(double mm) {
        long v = Math.round(mm);
        return String.format(java.util.Locale.US, "%,d", v).replace(',', ' ') + " mm";
    }

    // ---------- markup ----------

    static final int TOOL_PEN = 0, TOOL_ARROW = 1, TOOL_RECT = 2, TOOL_TEXT = 3, TOOL_ERASE = 4;
    private boolean marking, multi;
    private int tool = TOOL_PEN, markColor = 0xFFE53935;
    private ArrayList<Markup.Item> items = new ArrayList<>();
    /** Line widths and text sizes on screen, in dp, for the thin / medium / thick choice. */
    static final float[] STROKE_DP = {1.5f, 3f, 6f}, TEXT_DP = {12f, 17f, 26f};
    private int thick = 1;

    void setThickness(int i) { thick = Math.max(0, Math.min(2, i)); }
    int getThickness() { return thick; }

    /** Undo history: {kind 0 added / 1 removed / 2 edited, item, index, old text, old size, old colour}. */
    private final ArrayList<Object[]> history = new ArrayList<>();
    private Markup.Item draft;
    private float lastFx = Float.NaN, lastFy, downX, downY;
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markText = new Paint(Paint.ANTI_ALIAS_FLAG);

    void setItems(ArrayList<Markup.Item> list) { items = list; history.clear(); sel = null; editing = null; invalidate(); }
    ArrayList<Markup.Item> getItems() { return items; }
    void setMarking(boolean on) { marking = on; draft = null; if (!on) select(null); if (laidOut) changed(0); }
    boolean isMarking() { return marking; }
    void setTool(int t) { tool = t; if (t != TOOL_TEXT) select(null); invalidate(); }
    int getTool() { return tool; }
    void setMarkColor(int c) { markColor = c; }
    int getMarkColor() { return markColor; }

    private void record(boolean added, Markup.Item it, int index) {
        history.add(new Object[]{added ? 0 : 1, it, index, null, 0f, 0, 0f, 0f});
        if (listener != null) listener.onMarkupChanged();
        invalidate();
    }

    void undoMarkup() {
        if (history.isEmpty()) return;
        Object[] h = history.remove(history.size() - 1);
        Markup.Item it = (Markup.Item) h[1];
        int kind = (Integer) h[0];
        if (kind == 0) items.remove(it);
        else if (kind == 1) items.add(Math.min(items.size(), (Integer) h[2]), it);
        else {
            it.text = (String) h[3]; it.width = (Float) h[4]; it.color = (Integer) h[5];
            it.pts[0] = (Float) h[6]; it.pts[1] = (Float) h[7];
        }
        if (sel != null && !items.contains(sel)) select(null);
        if (listener != null) listener.onMarkupChanged();
        invalidate();
    }

    /** Saves an edit of a text note; old values come back with Undo. */
    void commitTextEdit(Markup.Item it, String oldText, float oldSize, int oldColor, float oldX, float oldY) {
        history.add(new Object[]{2, it, 0, oldText, oldSize, oldColor, oldX, oldY});
        if (listener != null) listener.onMarkupChanged();
        invalidate();
    }

    void removeItem(Markup.Item it) {
        int i = items.indexOf(it);
        if (it == sel) select(null);
        if (i < 0) return;
        items.remove(i);
        record(false, it, i);
    }

    /** Where a note sits on screen (y of its top), to keep the edit box clear of it. */
    float screenY(Markup.Item it) {
        if (it.page < lo || it.page > hi) return getHeight() / 2f;
        return (float) (oy + (top[it.page] + it.pts[1] * k[it.page]) * scale);
    }

    void addText(int page, float x, float y, String text) {
        if (page < 0 || page >= n || text.trim().isEmpty()) return;
        Markup.Item it = new Markup.Item();
        it.type = Markup.TEXT;
        it.page = page;
        it.color = markColor;
        it.text = text.trim();
        // as large as normal text on screen at the zoom it was written at
        it.width = TEXT_DP[thick] * dp / (scale * k[page]);
        it.add(x, y);
        items.add(it);
        record(true, it, 0);
    }

    private float lx(int page, float screenX) { return (float) ((screenX - ox) / scale / k[page]); }
    private float ly(int page, float screenY) { return (float) (((screenY - oy) / scale - top[page]) / k[page]); }

    /** One finger draws, two fingers move and zoom. */
    private boolean swallow;

    private boolean markTouch(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        float x = e.getX(), y = e.getY();
        int act = e.getActionMasked();
        if (swallow && act != MotionEvent.ACTION_DOWN) {
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) swallow = false;
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopMotion();
                multi = false;
                draft = null;
                downX = x;
                downY = y;
                // a touch that finishes typing a note does only that
                swallow = listener != null && listener.onCanvasTouch();
                if (swallow) break;
                if (tool == TOOL_TEXT) textDown(x, y);
                else if (tool == TOOL_ERASE) eraseAt(x, y);
                else if (tool != TOOL_TEXT) {
                    int p = pageAt((y - oy) / scale);
                    draft = new Markup.Item();
                    draft.type = tool == TOOL_PEN ? Markup.PEN : tool == TOOL_ARROW ? Markup.ARROW : Markup.RECT;
                    draft.page = p;
                    draft.color = markColor;
                    draft.width = STROKE_DP[thick] * dp / (scale * k[p]);
                    draft.add(lx(p, x), ly(p, y));
                    if (draft.type != Markup.PEN) draft.add(lx(p, x), ly(p, y));
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                draft = null;
                textCancel();
                lastFx = Float.NaN;
                invalidate();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                lastFx = Float.NaN;
                break;
            case MotionEvent.ACTION_MOVE:
                if (multi) {
                    if (e.getPointerCount() >= 2) {
                        float fx = (e.getX(0) + e.getX(1)) / 2, fy = (e.getY(0) + e.getY(1)) / 2;
                        if (!Float.isNaN(lastFx)) {
                            ox += fx - lastFx;
                            oy += fy - lastFy;
                            atFit = false;
                            changed(120);
                        }
                        lastFx = fx;
                        lastFy = fy;
                    }
                } else if (tool == TOOL_TEXT) {
                    textMove(x, y);
                } else if (tool == TOOL_ERASE) {
                    eraseAt(x, y);
                } else if (draft != null) {
                    int p = draft.page;
                    if (draft.type == Markup.PEN) {
                        for (int i = 0; i < e.getHistorySize(); i++) draft.add(lx(p, e.getHistoricalX(i)), ly(p, e.getHistoricalY(i)));
                        draft.add(lx(p, x), ly(p, y));
                    } else {
                        draft.pts[2] = lx(p, x);
                        draft.pts[3] = ly(p, y);
                    }
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (!multi) {
                    if (tool == TOOL_TEXT) {
                        textUp(x, y);
                    } else if (draft != null && Math.hypot(x - downX, y - downY) > 6 * dp) {
                        items.add(draft);
                        record(true, draft, 0);
                    }
                }
                draft = null;
                invalidate();
                scheduleSync(0);
                break;
            case MotionEvent.ACTION_CANCEL:
                draft = null;
                invalidate();
                break;
        }
        return true;
    }

    private boolean hits(Markup.Item it, float x, float y, float tol) {
        if (it.page < lo || it.page > hi) return false;
        float f = k[it.page] * scale, bx = (float) ox, by = (float) (oy + top[it.page] * scale);
        if (it.type == Markup.TEXT) {
            String[] lines = it.text.split("\n");
            markText.setTextSize(it.width * f);
            float w = 0;
            for (String l : lines) w = Math.max(w, markText.measureText(l));
            float tx = bx + it.pts[0] * f, ty = by + it.pts[1] * f;
            return x >= tx - tol && x <= tx + w + tol && y >= ty - tol && y <= ty + lines.length * it.width * f * 1.25f + tol;
        }
        if (it.type == Markup.RECT) {
            float x0 = bx + it.pts[0] * f, y0 = by + it.pts[1] * f, x1 = bx + it.pts[2] * f, y1 = by + it.pts[3] * f;
            return seg(x, y, x0, y0, x1, y0) < tol || seg(x, y, x1, y0, x1, y1) < tol
                    || seg(x, y, x1, y1, x0, y1) < tol || seg(x, y, x0, y1, x0, y0) < tol;
        }
        for (int j = 0; j + 3 < it.n; j += 2) {
            if (seg(x, y, bx + it.pts[j] * f, by + it.pts[j + 1] * f,
                    bx + it.pts[j + 2] * f, by + it.pts[j + 3] * f) < tol) return true;
        }
        return false;
    }

    // ---------- text notes: select, move, resize (like a text layer) ----------

    private Markup.Item sel, editing;
    private int textMode;            // 0 none, 1 move, 2 resize, 3 tap on empty space, 4 delete handle
    private boolean dragged, tapEdits;
    private float selX0, selY0, selSize0, anchorX, anchorY, grabDist;
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int SEL_COLOR = 0xFF1E6FE0, DEL_COLOR = 0xFFE53935;

    Markup.Item getSelected() { return sel; }

    void select(Markup.Item it) {
        if (sel == it) return;
        sel = it;
        invalidate();
        if (listener != null) listener.onSelectionChanged(it);
    }

    /** Hides a note while it is being typed over in place (null: none). */
    void setEditing(Markup.Item it) { editing = it; invalidate(); }

    /** Screen rectangle of a note's text. */
    RectF textRect(Markup.Item it) {
        RectF r = new RectF();
        if (it.page < lo || it.page > hi) return r;
        float f = k[it.page] * scale, size = it.width * f;
        float tx = (float) (ox + it.pts[0] * f), ty = (float) (oy + top[it.page] * scale + it.pts[1] * f);
        markText.setTextSize(size);
        String[] lines = it.text.split("\n");
        float w = 0;
        for (String l : lines) w = Math.max(w, markText.measureText(l));
        r.set(tx, ty, tx + Math.max(w, size * 0.6f), ty + size * (1.25f * (lines.length - 1) + 1.2f));
        return r;
    }

    /** Text size of a note on screen, in pixels. */
    float screenSize(Markup.Item it) {
        return it.page < lo || it.page > hi ? 0 : it.width * k[it.page] * scale;
    }

    private RectF selBox() {
        RectF b = textRect(sel);
        float pad = 6 * dp;
        b.inset(-pad, -pad);
        return b;
    }

    private void editSelected(Runnable change) {
        if (sel == null) return;
        String t = sel.text;
        float w = sel.width, x = sel.pts[0], y = sel.pts[1];
        int c = sel.color;
        change.run();
        commitTextEdit(sel, t, w, c, x, y);
    }

    void setSelectedColor(int color) {
        if (sel != null && sel.color != color) editSelected(() -> sel.color = color);
    }

    void setSelectedSize(int i) {
        if (sel == null || sel.page < lo || sel.page > hi) return;
        float w = TEXT_DP[Math.max(0, Math.min(2, i))] * dp / (scale * k[sel.page]);
        editSelected(() -> sel.width = w);
    }

    private void textDown(float x, float y) {
        textMode = 0;
        dragged = false;
        tapEdits = false;
        float grab = 26 * dp;
        if (sel != null && sel.page >= lo && sel.page <= hi) {
            RectF b = selBox();
            if (Math.hypot(x - b.right, y - b.top) < grab) textMode = 4;
            else if (Math.hypot(x - b.right, y - b.bottom) < grab) textMode = 2;
            else if (b.contains(x, y)) { textMode = 1; tapEdits = true; }
        }
        if (textMode == 0) {
            Markup.Item hit = textAt(x, y);
            if (hit != null) { select(hit); textMode = 1; }
            else textMode = 3;
        }
        if (textMode == 1 || textMode == 2) {
            selX0 = sel.pts[0];
            selY0 = sel.pts[1];
            selSize0 = sel.width;
            RectF r = textRect(sel);
            anchorX = r.left;
            anchorY = r.top;
            grabDist = Math.max(8 * dp, (float) Math.hypot(x - anchorX, y - anchorY));
        }
    }

    private void textMove(float x, float y) {
        if (sel == null) return;
        if (!dragged && Math.hypot(x - downX, y - downY) > 6 * dp) dragged = true;
        if (!dragged) return;
        float f = k[sel.page] * scale;
        if (textMode == 1) {
            sel.pts[0] = selX0 + (x - downX) / f;
            sel.pts[1] = selY0 + (y - downY) / f;
        } else if (textMode == 2) {
            float size = selSize0 * (float) Math.hypot(x - anchorX, y - anchorY) / grabDist;
            sel.width = Math.max(4 * dp / f, Math.min(400 * dp / f, size));
        }
        invalidate();
    }

    private void textUp(float x, float y) {
        if (textMode == 4) {
            if (sel != null && !dragged) removeItem(sel);
        } else if ((textMode == 1 || textMode == 2) && sel != null) {
            if (dragged) {
                commitTextEdit(sel, sel.text, selSize0, sel.color, selX0, selY0);
            } else if (tapEdits && listener != null) {
                listener.onTextEdit(sel);
            }
        } else if (textMode == 3) {
            if (sel != null) {
                select(null);
            } else if (listener != null) {
                int p = pageAt((y - oy) / scale);
                listener.onTextRequested(p, lx(p, x), ly(p, y), x, y);
            }
        }
        textMode = 0;
    }

    /** Puts a note back where it was when a second finger turns the drag into a pan. */
    private void textCancel() {
        if (sel != null && dragged && (textMode == 1 || textMode == 2)) {
            sel.pts[0] = selX0;
            sel.pts[1] = selY0;
            sel.width = selSize0;
        }
        textMode = 0;
        dragged = false;
    }

    private void drawSelection(Canvas c) {
        if (sel == null || sel == editing || !marking || tool != TOOL_TEXT || sel.page < lo || sel.page > hi) return;
        RectF b = selBox();
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(1.5f * dp);
        selPaint.setColor(SEL_COLOR);
        selPaint.setPathEffect(new DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
        c.drawRect(b, selPaint);
        selPaint.setPathEffect(null);
        float r = 9 * dp;
        // resize handle, bottom-right
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFFFFFF);
        c.drawCircle(b.right, b.bottom, r, handlePaint);
        selPaint.setStrokeWidth(2 * dp);
        c.drawCircle(b.right, b.bottom, r, selPaint);
        float a = r * 0.45f;
        c.drawLine(b.right - a, b.bottom - a, b.right + a, b.bottom + a, selPaint);
        // delete handle, top-right
        handlePaint.setColor(DEL_COLOR);
        c.drawCircle(b.right, b.top, r, handlePaint);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStrokeWidth(2 * dp);
        handlePaint.setStyle(Paint.Style.STROKE);
        c.drawLine(b.right - a, b.top - a, b.right + a, b.top + a, handlePaint);
        c.drawLine(b.right - a, b.top + a, b.right + a, b.top - a, handlePaint);
    }

    /** The text note under a tap, if any (the most recent one on top). */
    private Markup.Item textAt(float x, float y) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Markup.Item it = items.get(i);
            if (it.type == Markup.TEXT && hits(it, x, y, 10 * dp)) return it;
        }
        return null;
    }

    private void eraseAt(float x, float y) {
        float tol = 14 * dp;
        for (int i = items.size() - 1; i >= 0; i--) {
            Markup.Item it = items.get(i);
            if (hits(it, x, y, tol)) {
                items.remove(i);
                record(false, it, i);
                return;
            }
        }
    }

    /** Distance from a point to a segment. */
    private static float seg(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return (float) Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    // ---------- search hits ----------

    private float[] hits;                  // page, left, top, right, bottom (sheet points)
    private int hitIndex = -1;
    private final Paint hitPaint = new Paint(), hitCurPaint = new Paint();
    {
        hitPaint.setColor(0x55FFC400);
        hitCurPaint.setColor(0x88FF8A00);
    }

    void setHits(float[] h) { hits = h; hitIndex = -1; invalidate(); }

    /** Brings search result i to the middle of the screen, zoomed in enough to read it. */
    void showHit(int i) {
        if (hits == null || i < 0 || 5 * i + 4 >= hits.length || n == 0) return;
        hitIndex = i;
        int p = (int) hits[5 * i];
        if (p < 0 || p >= n) return;
        if (!continuous && p != lo) relayout(p);
        if (!laidOut) { invalidate(); return; }
        stopMotion();
        float x0 = hits[5 * i + 1], y0 = hits[5 * i + 2], x1 = hits[5 * i + 3], y1 = hits[5 * i + 4];
        double span = Math.max(x1 - x0, (y1 - y0) * 6) * k[p];
        float want = (float) (getWidth() * 0.3 / Math.max(span, 1e-3));
        scale = Math.max(minScale(), Math.min(MAX_SCALE, Math.max(scale, want)));
        atFit = false;
        ox = getWidth() / 2f - (x0 + x1) / 2f * k[p] * scale;
        oy = centerY() - ((y0 + y1) / 2f * k[p] + top[p]) * scale;
        changed(0);
    }

    private void drawHits(Canvas c) {
        if (hits == null) return;
        for (int i = 0; 5 * i + 4 < hits.length; i++) {
            int p = (int) hits[5 * i];
            if (p < lo || p > hi || p >= n) continue;
            float f = k[p] * scale, by = (float) (oy + top[p] * scale), pad = 2 * dp;
            tmp.set((float) ox + hits[5 * i + 1] * f - pad, by + hits[5 * i + 2] * f - pad,
                    (float) ox + hits[5 * i + 3] * f + pad, by + hits[5 * i + 4] * f + pad);
            if (tmp.bottom < 0 || tmp.top > getHeight()) continue;
            c.drawRoundRect(tmp, 3 * dp, 3 * dp, i == hitIndex ? hitCurPaint : hitPaint);
        }
    }

    // ---------- export ----------

    private static void add(java.util.ArrayList<Float> o, float... v) { for (float x : v) o.add(x); }

    private static float[] rgba(int c) {
        return new float[]{(c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, (c >>> 24) / 255f};
    }

    private static void stroke(java.util.ArrayList<Float> o, int page, int color, float width, boolean closed, float... xy) {
        float[] c = rgba(color);
        add(o, 0, page, c[0], c[1], c[2], c[3], width, closed ? 1 : 0, xy.length / 2f);
        add(o, xy);
    }

    private static void fill(java.util.ArrayList<Float> o, int page, int color, float... xy) {
        float[] c = rgba(color);
        add(o, 1, page, c[0], c[1], c[2], c[3], xy.length / 2f);
        add(o, xy);
    }

    private static void text(java.util.ArrayList<Float> o, java.util.ArrayList<String> t, int page, int color,
                             float size, String s, float x, float y, boolean tagged) {
        float[] c = rgba(color);
        add(o, 2, page, c[0], c[1], c[2], c[3], size, t.size(), x, y, tagged ? 1 : 0);
        t.add(s);
    }

    private static float[] circle(float x, float y, float r) {
        float[] xy = new float[32];
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            xy[2 * i] = x + r * (float) Math.cos(a);
            xy[2 * i + 1] = y + r * (float) Math.sin(a);
        }
        return xy;
    }

    /**
     * Markup and finished measurements as drawing records for Pdfium.exportMarked, grouped by
     * sheet. Measurement lines and labels are sized to the sheet, so they print legibly.
     */
    void exportOps(java.util.ArrayList<Float> o, java.util.ArrayList<String> t) {
        for (int p = 0; p < n; p++) {
            for (Markup.Item it : items) {
                if (it.page != p || it.n < 2) continue;
                float w = Math.max(0.3f, it.width);
                if (it.type == Markup.TEXT) {
                    if (it.text != null && !it.text.isEmpty()) text(o, t, p, it.color, it.width, it.text, it.pts[0], it.pts[1], false);
                } else if (it.n >= 4) {
                    float x0 = it.pts[0], y0 = it.pts[1], x1 = it.pts[2], y1 = it.pts[3];
                    if (it.type == Markup.RECT) {
                        stroke(o, p, it.color, w, true, x0, y0, x1, y0, x1, y1, x0, y1);
                    } else if (it.type == Markup.ARROW) {
                        stroke(o, p, it.color, w, false, x0, y0, x1, y1);
                        double a = Math.atan2(y1 - y0, x1 - x0);
                        float h = Math.min(w * 5, (float) Math.hypot(x1 - x0, y1 - y0) * 0.5f);
                        stroke(o, p, it.color, w, false,
                                x1 - h * (float) Math.cos(a - 0.45), y1 - h * (float) Math.sin(a - 0.45), x1, y1,
                                x1 - h * (float) Math.cos(a + 0.45), y1 - h * (float) Math.sin(a + 0.45));
                    } else {
                        stroke(o, p, it.color, w, false, java.util.Arrays.copyOf(it.pts, it.n));
                    }
                }
            }
            float big = Math.max(pw[p], ph[p]);
            float lw = Math.max(0.5f, big / 1400f), ts = Math.max(4f, big / 180f);
            double mm = MM_PER_PT * ratio(p);
            for (Meas m : done) {
                if (m.page != p) continue;
                int cnt = m.pts.size();
                float[] xy = new float[2 * cnt];
                for (int i = 0; i < cnt; i++) { xy[2 * i] = m.pts.get(i)[0]; xy[2 * i + 1] = m.pts.get(i)[1]; }
                boolean area = m.mode == MODE_AREA;
                if (area) fill(o, p, (Ui.MARK & 0x00FFFFFF) | 0x33000000, xy);
                stroke(o, p, Ui.MARK, lw, area, xy);
                for (int i = 0; i < cnt; i++) fill(o, p, Ui.MARK, circle(xy[2 * i], xy[2 * i + 1], lw * 1.8f));
                int segs = area ? cnt : cnt - 1;
                double total = 0;
                for (int i = 0; i < segs; i++) {
                    float[] u = m.pts.get(i), v = m.pts.get((i + 1) % cnt);
                    double d = dist(u, v);
                    total += d;
                    String s = Units.len(d * mm);
                    if (d > ts * (0.6 * s.length() + 1.5)) {
                        text(o, t, p, 0xFFFFFFFF, ts, s, (u[0] + v[0]) / 2, (u[1] + v[1]) / 2, true);
                    }
                }
                if (area) {
                    double a = 0, cx = 0, cy = 0;
                    for (int i = 0; i < cnt; i++) {
                        float[] u = m.pts.get(i), v = m.pts.get((i + 1) % cnt);
                        a += (double) u[0] * v[1] - (double) v[0] * u[1];
                        cx += u[0];
                        cy += u[1];
                    }
                    text(o, t, p, 0xFFFFFFFF, ts * 1.15f, Units.area(Math.abs(a) / 2 * mm * mm / 1e6),
                            (float) (cx / cnt), (float) (cy / cnt), true);
                } else if (cnt > 2) {
                    float[] last = m.pts.get(cnt - 1);
                    text(o, t, p, 0xFFFFFFFF, ts * 1.15f, "\u03A3 " + Units.len(total * mm), last[0], last[1] - ts * 2.2f, true);
                }
            }
        }
    }

    boolean hasMarks() { return !items.isEmpty() || !done.isEmpty(); }

    private void drawMarkup(Canvas c) {
        for (Markup.Item it : items) if (it != editing) drawItem(c, it);
        if (draft != null) drawItem(c, draft);
        drawSelection(c);
    }

    private void drawItem(Canvas c, Markup.Item it) {
        int p = it.page;
        if (p < lo || p > hi || sheetBottom(p) < 0 || sheetTop(p) > getHeight()) return;
        float f = k[p] * scale, bx = (float) ox, by = (float) (oy + top[p] * scale);
        if (it.type == Markup.TEXT) {
            markText.setColor(it.color);
            float size = it.width * f;
            markText.setTextSize(size);
            float ty = by + it.pts[1] * f + size;
            for (String l : it.text.split("\n")) {
                c.drawText(l, bx + it.pts[0] * f, ty, markText);
                ty += size * 1.25f;
            }
            return;
        }
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeCap(Paint.Cap.ROUND);
        markPaint.setStrokeJoin(Paint.Join.ROUND);
        markPaint.setColor(it.color);
        float sw = Math.max(1.5f, it.width * f);
        markPaint.setStrokeWidth(sw);
        if (it.n < 4) return;
        float x0 = bx + it.pts[0] * f, y0 = by + it.pts[1] * f, x1 = bx + it.pts[2] * f, y1 = by + it.pts[3] * f;
        if (it.type == Markup.RECT) {
            c.drawRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), markPaint);
        } else if (it.type == Markup.ARROW) {
            c.drawLine(x0, y0, x1, y1, markPaint);
            double a = Math.atan2(y1 - y0, x1 - x0);
            float h = Math.min(sw * 5, (float) Math.hypot(x1 - x0, y1 - y0) * 0.5f);
            c.drawLine(x1, y1, x1 - h * (float) Math.cos(a - 0.45), y1 - h * (float) Math.sin(a - 0.45), markPaint);
            c.drawLine(x1, y1, x1 - h * (float) Math.cos(a + 0.45), y1 - h * (float) Math.sin(a + 0.45), markPaint);
        } else {
            path.rewind();
            path.moveTo(x0, y0);
            for (int j = 2; j + 1 < it.n; j += 2) path.lineTo(bx + it.pts[j] * f, by + it.pts[j + 1] * f);
            c.drawPath(path, markPaint);
        }
    }

    // ---------- drawing ----------

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Ui.BG);
        if (n == 0 || !laidOut) return;
        int vh = getHeight();
        float l = (float) ox, r = (float) (ox + W * scale);
        for (int i = firstVisible(); i <= hi && sheetTop(i) < vh; i++) {
            tmp.set(l, sheetTop(i), r, sheetBottom(i));
            c.drawRect(tmp, pagePaint);
            Bitmap b = bases.get(i);
            if (b != null) c.drawBitmap(b, null, tmp, bmpPaint);
        }
        if (patch != null) {
            double q = (double) scale / patchScale;
            tmp.set((float) (ox + patchLeft * q), (float) (oy + patchTop * q),
                    (float) (ox + (patchLeft + patch.getWidth()) * q), (float) (oy + (patchTop + patch.getHeight()) * q));
            c.drawBitmap(patch, null, tmp, q == 1.0 ? null : bmpPaint);
        }
        drawHits(c);
        drawMarkup(c);
        if (measuring) drawMeasure(c);
    }

    private float sx(float[] p) { return (float) (p[0] * k[mp] * scale + ox); }
    private float sy(float[] p) { return (float) ((p[1] * k[mp] + top[mp]) * (double) scale + oy); }
    private int mp;

    private void drawMeasure(Canvas c) {
        float cx = getWidth() / 2f, cy = centerY();
        for (Meas m : done) drawFinished(c, m);
        int cnt = pts.size();
        mp = measurePage();
        float[] aim = cursor();
        float ax = sx(aim), ay = sy(aim);
        if (cnt > 0) {
            path.rewind();
            path.moveTo(sx(pts.get(0)), sy(pts.get(0)));
            for (int i = 1; i < cnt; i++) path.lineTo(sx(pts.get(i)), sy(pts.get(i)));
            if (mode == MODE_AREA && cnt >= 2) {
                path.lineTo(ax, ay);
                path.close();
                c.drawPath(path, fillPaint);
                path.rewind();
                path.moveTo(sx(pts.get(0)), sy(pts.get(0)));
                for (int i = 1; i < cnt; i++) path.lineTo(sx(pts.get(i)), sy(pts.get(i)));
            }
            c.drawPath(path, linePaint);
            float[] last = pts.get(cnt - 1);
            c.drawLine(sx(last), sy(last), ax, ay, livePaint);
            if (mode == MODE_AREA && cnt >= 2) c.drawLine(ax, ay, sx(pts.get(0)), sy(pts.get(0)), livePaint);
            for (int i = 1; i < cnt; i++) {
                float[] u = pts.get(i - 1), v = pts.get(i);
                String s = Units.len(toMm(dist(u, v)));
                // no label on segments too short on screen to carry one
                if (Math.hypot(sx(u) - sx(v), sy(u) - sy(v)) > textPaint.measureText(s) + 28 * dp) {
                    tag(c, s, (sx(u) + sx(v)) / 2, (sy(u) + sy(v)) / 2);
                }
            }
            for (float[] p : pts) {
                c.drawCircle(sx(p), sy(p), 4.5f * dp, dotPaint);
                c.drawCircle(sx(p), sy(p), 4.5f * dp, dotRing);
            }
        }
        if (snapKind != 0) {
            // snap mark: a square on an end point, a diamond on an edge
            float h = 7 * dp;
            c.save();
            if (snapKind == 2) c.rotate(45, ax, ay);
            c.drawRect(ax - h, ay - h, ax + h, ay + h, crossHalo);
            c.drawRect(ax - h, ay - h, ax + h, ay + h, dotRing);
            c.restore();
        }
        // the aiming cross, fixed in the middle of the visible area
        float g = 5 * dp, len = 26 * dp;
        for (Paint p : new Paint[]{crossHalo, crossPaint}) {
            c.drawLine(cx - len, cy, cx - g, cy, p);
            c.drawLine(cx + g, cy, cx + len, cy, p);
            c.drawLine(cx, cy - len, cx, cy - g, p);
            c.drawLine(cx, cy + g, cx, cy + len, p);
        }
        c.drawPoint(cx, cy, crossPaint);
    }

    private void drawFinished(Canvas c, Meas m) {
        if (m.page < lo || m.page > hi || m.page >= n) return;
        mp = m.page;
        int cnt = m.pts.size();
        double mm = MM_PER_PT * ratio(m.page);
        path.rewind();
        path.moveTo(sx(m.pts.get(0)), sy(m.pts.get(0)));
        for (int i = 1; i < cnt; i++) path.lineTo(sx(m.pts.get(i)), sy(m.pts.get(i)));
        boolean area = m.mode == MODE_AREA;
        if (area) {
            path.close();
            c.drawPath(path, fillPaint);
        }
        c.drawPath(path, linePaint);
        int segs = area ? cnt : cnt - 1;
        double total = 0;
        for (int i = 0; i < segs; i++) {
            float[] u = m.pts.get(i), v = m.pts.get((i + 1) % cnt);
            total += dist(u, v);
            String s = Units.len(dist(u, v) * mm);
            if (Math.hypot(sx(u) - sx(v), sy(u) - sy(v)) > textPaint.measureText(s) + 28 * dp) {
                tag(c, s, (sx(u) + sx(v)) / 2, (sy(u) + sy(v)) / 2);
            }
        }
        for (float[] p : m.pts) {
            c.drawCircle(sx(p), sy(p), 3.5f * dp, dotPaint);
            c.drawCircle(sx(p), sy(p), 3.5f * dp, dotRing);
        }
        if (area) {
            double a = 0, cx = 0, cy = 0;
            for (int i = 0; i < cnt; i++) {
                float[] u = m.pts.get(i), v = m.pts.get((i + 1) % cnt);
                a += (double) u[0] * v[1] - (double) v[0] * u[1];
                cx += u[0];
                cy += u[1];
            }
            float[] ctr = {(float) (cx / cnt), (float) (cy / cnt)};
            tag(c, Units.area(Math.abs(a) / 2 * mm * mm / 1e6), sx(ctr), sy(ctr));
        } else if (cnt > 2) {
            float[] last = m.pts.get(cnt - 1);
            tag(c, "\u03A3 " + Units.len(total * mm), sx(last), sy(last) - 22 * dp);
        }
    }

    private void tag(Canvas c, String s, float x, float y) {
        float w = textPaint.measureText(s) / 2 + 6 * dp, h = 10 * dp;
        tmp.set(x - w, y - h, x + w, y + h);
        c.drawRoundRect(tmp, 4 * dp, 4 * dp, tagPaint);
        c.drawText(s, x, y + 4.5f * dp, textPaint);
    }
}
