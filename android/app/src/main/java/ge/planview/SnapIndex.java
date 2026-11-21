package ge.planview;

/** Line segments of one sheet in a coarse grid, to find the nearest end point or edge quickly. */
final class SnapIndex {
    static final int MAX = 1_500_000;
    private static final int G = 96;
    private float[] seg = new float[4096];
    private int count;
    private float cw = 1, ch = 1;
    private int[] start, items;

    boolean full() { return count >= MAX; }

    /** Takes segments as x0,y0,x1,y1 quadruples. */
    void setSegments(float[] quads) {
        seg = quads;
        count = quads.length / 4;
    }

    void add(float x0, float y0, float x1, float y1) {
        if (count >= MAX) return;
        if (count * 4 + 4 > seg.length) seg = java.util.Arrays.copyOf(seg, seg.length * 2);
        int i = count++ * 4;
        seg[i] = x0; seg[i + 1] = y0; seg[i + 2] = x1; seg[i + 3] = y1;
    }

    private int cell(float x, float y) {
        int cx = Math.max(0, Math.min(G - 1, (int) (x / cw))), cy = Math.max(0, Math.min(G - 1, (int) (y / ch)));
        return cy * G + cx;
    }

    /** Visits the grid cells a segment passes through. fill=false counts, fill=true stores. */
    private void spread(int s, boolean fill, int[] cursor) {
        int i = s * 4;
        float x0 = seg[i], y0 = seg[i + 1], x1 = seg[i + 2], y1 = seg[i + 3];
        int steps = (int) Math.min(4 * G, Math.ceil(Math.hypot(x1 - x0, y1 - y0) / (Math.min(cw, ch) * 0.5f)));
        int last = -1;
        for (int k = 0; k <= steps; k++) {
            float t = steps == 0 ? 0 : (float) k / steps;
            int c = cell(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t);
            if (c == last) continue;
            last = c;
            if (fill) items[cursor[c]++] = s; else start[c + 1]++;
        }
    }

    void build(float w, float h) {
        cw = Math.max(1e-3f, w / G);
        ch = Math.max(1e-3f, h / G);
        start = new int[G * G + 1];
        for (int s = 0; s < count; s++) spread(s, false, null);
        for (int c = 0; c < G * G; c++) start[c + 1] += start[c];
        items = new int[start[G * G]];
        int[] cursor = java.util.Arrays.copyOf(start, G * G);
        for (int s = 0; s < count; s++) spread(s, true, cursor);
    }

    /** Nearest end point within r, else nearest point on an edge: {x, y, kind 1=end 2=edge}, or null. */
    float[] query(float x, float y, float r) {
        if (items == null || count == 0) return null;
        r = Math.min(r, 1.5f * Math.min(cw, ch));
        int cx0 = Math.max(0, (int) ((x - r) / cw)), cx1 = Math.min(G - 1, (int) ((x + r) / cw));
        int cy0 = Math.max(0, (int) ((y - r) / ch)), cy1 = Math.min(G - 1, (int) ((y + r) / ch));
        float bestEnd = r * r, bestEdge = r * r * 0.45f;
        float ex = 0, ey = 0, gx = 0, gy = 0;
        boolean end = false, edge = false;
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = cy * G + cx;
                for (int k = start[c]; k < start[c + 1]; k++) {
                    int i = items[k] * 4;
                    float x0 = seg[i], y0 = seg[i + 1], x1 = seg[i + 2], y1 = seg[i + 3];
                    float d0 = (x - x0) * (x - x0) + (y - y0) * (y - y0);
                    if (d0 < bestEnd) { bestEnd = d0; ex = x0; ey = y0; end = true; }
                    float d1 = (x - x1) * (x - x1) + (y - y1) * (y - y1);
                    if (d1 < bestEnd) { bestEnd = d1; ex = x1; ey = y1; end = true; }
                    float dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy;
                    if (l2 > 0) {
                        float t = Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / l2));
                        float qx = x0 + t * dx, qy = y0 + t * dy;
                        float dq = (x - qx) * (x - qx) + (y - qy) * (y - qy);
                        if (dq < bestEdge) { bestEdge = dq; gx = qx; gy = qy; edge = true; }
                    }
                }
            }
        }
        if (end) return new float[]{ex, ey, 1};
        if (edge) return new float[]{gx, gy, 2};
        return null;
    }
}
