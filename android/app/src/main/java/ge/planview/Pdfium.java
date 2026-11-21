package ge.planview;

import android.graphics.Bitmap;

/** Thin Java face of cpp/calque.c. Everything but abort must be called from one thread. */
final class Pdfium {
    static {
        System.loadLibrary("pdfium");
        System.loadLibrary("calque");
    }

    static final long ERR_PASSWORD = -4, ERR_UNREADABLE = -100;

    /**
     * Errors are small negative numbers. A real handle can also look negative: on 64-bit ARM
     * phones Android tags the top byte of memory addresses (e.g. 0xB400...), so never test > 0.
     */
    static boolean isError(long h) {
        return h <= 0 && h >= -1000;
    }

    /** Handle (> 0), or minus a PDFium error code. The fd must stay open until close. */
    static native long open(int fd, long size, String password);
    /** DXF → PDF; returns the drawing scale (100 for 1:100) or a negative error code. */
    static native double dxfToPdf(String dxf, String pdf, String font, String georgianFont);
    /** DWG → PDF through LibreDWG; same results as dxfToPdf (-7: the DWG could not be read). */
    static native double dwgToPdf(String dwg, String tmpDxf, String pdf, String font, String georgianFont);
    /** Text search: groups of page, left, top, right, bottom (sheet points from the top-left). */
    static native float[] search(long doc, String query, int max);
    /** A copy of the file with markup drawn in (see calque.c for the record format). */
    static native boolean exportMarked(int fd, long size, String password, String out,
                                       float[] ops, String[] texts, String font, String georgianFont);
    static native void close(long doc);
    static native int pageCount(long doc);
    static native float[] pageSize(long doc, int index);
    static native String pageLabel(long doc, int index);
    /** {int[] depths, int[] pages, String[] titles} */
    static native Object[] outline(long doc);
    static native long loadPage(long doc, int index);
    static native void closePage(long page);
    static native void abort(long doc, boolean on);
    static native boolean render(long page, Bitmap bm, int x0, int y0, int x1, int y1,
                                 int startX, int startY, int sizeX, int sizeY,
                                 float scale, float hairPx, boolean cancellable);
    static native float[] geometry(long page, int maxSegments);

    private Pdfium() { }
}
