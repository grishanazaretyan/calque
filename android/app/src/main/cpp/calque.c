// Bridge between Calque (Java) and PDFium. Every call except Pdfium.abort runs on one
// worker thread, as PDFium itself is not thread-safe.
#include <jni.h>
#include <android/bitmap.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <stdatomic.h>

#include "fpdfview.h"
#include "dxf2pdf.h"
#include "dwg2pdf.h"
#include "fpdf_doc.h"
#include "fpdf_edit.h"
#include "fpdf_progressive.h"

#define JFN(name) Java_ge_planview_Pdfium_##name

typedef struct {
    FPDF_FILEACCESS access;   // must outlive the document
    FPDF_DOCUMENT doc;
    int fd;
    atomic_int abort;
} Doc;

typedef struct {
    FPDF_PAGE page;
    Doc* doc;
    // hairline state: every stroked path, with how much its own transform scales widths
    int npaths;
    FPDF_PAGEOBJECT* paths;
    float* factor;
    float hairScale, hairPx;
    int cap;
} Pg;

typedef struct { double a, b, c, d, e, f; } Mx;

static int initialised = 0;

static int read_block(void* param, unsigned long pos, unsigned char* buf, unsigned long size) {
    Doc* d = (Doc*) param;
    unsigned long done = 0;
    while (done < size) {
        ssize_t n = pread(d->fd, buf + done, size - done, (off_t) (pos + done));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        done += (unsigned long) n;
    }
    return 1;
}

static jstring utf16(JNIEnv* env, const unsigned short* buf, unsigned long bytes) {
    if (bytes < 2) return NULL;
    jsize len = (jsize) (bytes / 2 - 1);   // drop the terminating zero
    if (len <= 0) return NULL;
    return (*env)->NewString(env, (const jchar*) buf, len);
}

// ---------- document ----------

// Returns a handle, or minus the PDFium error code (-100: the file cannot be read by offset).
static void ensure_init(void) {
    if (!initialised) {
        FPDF_LIBRARY_CONFIG cfg;
        memset(&cfg, 0, sizeof cfg);
        cfg.version = 2;
        FPDF_InitLibraryWithConfig(&cfg);
        initialised = 1;
    }
}

// "pages\nratio\tname\n…" for the converted pages, or "-error".
static jstring pages_result(JNIEnv* env, int n, const DxfPages* r) {
    size_t cap = 64 + (size_t) (n > 0 ? n : 0) * 96;
    char* out = malloc(cap);
    if (!out) return NULL;
    int k = snprintf(out, cap, "%d\n", n);
    for (int i = 0; n > 0 && i < r->pages && (size_t) k < cap; i++) {
        k += snprintf(out + k, cap - (size_t) k, "%.6g\t%s\n", r->ratio[i], r->name[i]);
    }
    jstring js = (*env)->NewStringUTF(env, out);
    free(out);
    return js;
}

// Converts a DXF file to a PDF: the model, then every layout as a page.
JNIEXPORT jstring JNICALL JFN(dxfToPdf)(JNIEnv* env, jclass cls, jstring dxf, jstring pdf,
                                        jstring font, jstring geoFont) {
    ensure_init();
    const char* a = (*env)->GetStringUTFChars(env, dxf, NULL);
    const char* b = (*env)->GetStringUTFChars(env, pdf, NULL);
    const char* f = font ? (*env)->GetStringUTFChars(env, font, NULL) : NULL;
    const char* g = geoFont ? (*env)->GetStringUTFChars(env, geoFont, NULL) : NULL;
    DxfPages* r = malloc(sizeof(DxfPages));
    int n = r ? dxf_to_pdf_pages(a, b, f, g, r) : -DXF_ERR_MEMORY;
    (*env)->ReleaseStringUTFChars(env, dxf, a);
    (*env)->ReleaseStringUTFChars(env, pdf, b);
    if (f) (*env)->ReleaseStringUTFChars(env, font, f);
    if (g) (*env)->ReleaseStringUTFChars(env, geoFont, g);
    jstring out = pages_result(env, n, r);
    free(r);
    return out;
}

// Converts a DWG (via LibreDWG and a temporary DXF) to a one-page PDF.
JNIEXPORT jstring JNICALL JFN(dwgToPdf)(JNIEnv* env, jclass cls, jstring dwg, jstring tmpDxf, jstring pdf,
                                        jstring font, jstring geoFont) {
    ensure_init();
    const char* a = (*env)->GetStringUTFChars(env, dwg, NULL);
    const char* t = (*env)->GetStringUTFChars(env, tmpDxf, NULL);
    const char* b = (*env)->GetStringUTFChars(env, pdf, NULL);
    const char* f = font ? (*env)->GetStringUTFChars(env, font, NULL) : NULL;
    const char* g = geoFont ? (*env)->GetStringUTFChars(env, geoFont, NULL) : NULL;
    DxfPages* r = malloc(sizeof(DxfPages));
    int n = r ? dwg_to_pdf_pages(a, t, b, f, g, r) : -DXF_ERR_MEMORY;
    (*env)->ReleaseStringUTFChars(env, dwg, a);
    (*env)->ReleaseStringUTFChars(env, tmpDxf, t);
    (*env)->ReleaseStringUTFChars(env, pdf, b);
    if (f) (*env)->ReleaseStringUTFChars(env, font, f);
    if (g) (*env)->ReleaseStringUTFChars(env, geoFont, g);
    jstring out = pages_result(env, n, r);
    free(r);
    return out;
}

JNIEXPORT jlong JNICALL JFN(open)(JNIEnv* env, jclass cls, jint fd, jlong size, jstring password) {
    ensure_init();
    unsigned char probe;
    if (size <= 0 || pread(fd, &probe, 1, 0) != 1) return -100;
    Doc* d = calloc(1, sizeof(Doc));
    if (!d) return -1;
    d->fd = fd;
    d->access.m_FileLen = (unsigned long) size;
    d->access.m_GetBlock = read_block;
    d->access.m_Param = d;
    const char* pw = password ? (*env)->GetStringUTFChars(env, password, NULL) : NULL;
    d->doc = FPDF_LoadCustomDocument(&d->access, pw);
    if (pw) (*env)->ReleaseStringUTFChars(env, password, pw);
    if (!d->doc) {
        long err = (long) FPDF_GetLastError();
        free(d);
        return -(err > 0 ? err : 1);
    }
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL JFN(close)(JNIEnv* env, jclass cls, jlong h) {
    Doc* d = (Doc*) (intptr_t) h;
    if (!d) return;
    FPDF_CloseDocument(d->doc);
    free(d);
}

JNIEXPORT jint JNICALL JFN(pageCount)(JNIEnv* env, jclass cls, jlong h) {
    return FPDF_GetPageCount(((Doc*) (intptr_t) h)->doc);
}

/** Page size in points as displayed (rotation applied); {0,0} if unknown. */
JNIEXPORT jfloatArray JNICALL JFN(pageSize)(JNIEnv* env, jclass cls, jlong h, jint i) {
    FS_SIZEF s = {0, 0};
    FPDF_GetPageSizeByIndexF(((Doc*) (intptr_t) h)->doc, i, &s);
    jfloat v[2] = {s.width, s.height};
    jfloatArray out = (*env)->NewFloatArray(env, 2);
    (*env)->SetFloatArrayRegion(env, out, 0, 2, v);
    return out;
}

JNIEXPORT jstring JNICALL JFN(pageLabel)(JNIEnv* env, jclass cls, jlong h, jint i) {
    FPDF_DOCUMENT doc = ((Doc*) (intptr_t) h)->doc;
    unsigned long n = FPDF_GetPageLabel(doc, i, NULL, 0);
    if (n < 4 || n > 4096) return NULL;
    unsigned short* buf = malloc(n);
    if (!buf) return NULL;
    FPDF_GetPageLabel(doc, i, buf, n);
    jstring s = utf16(env, buf, n);
    free(buf);
    return s;
}

// Bookmarks, flattened: titles[k] points at pages[k] and sits at depths[k] (1 = top level).
typedef struct { int n, cap; int* depth; int* page; jstring* title; } Outline;

static void walk_outline(JNIEnv* env, FPDF_DOCUMENT doc, FPDF_BOOKMARK bm, int depth, Outline* o) {
    int guard = 0;
    while (bm && guard++ < 5000 && o->n < 20000 && depth <= 12) {
        int page = -1;
        FPDF_DEST dest = FPDFBookmark_GetDest(doc, bm);
        if (!dest) {
            FPDF_ACTION act = FPDFBookmark_GetAction(bm);
            if (act && FPDFAction_GetType(act) == PDFACTION_GOTO) dest = FPDFAction_GetDest(doc, act);
        }
        if (dest) page = FPDFDest_GetDestPageIndex(doc, dest);
        unsigned long n = FPDFBookmark_GetTitle(bm, NULL, 0);
        if (page >= 0 && n >= 4 && n < 8192) {
            unsigned short* buf = malloc(n);
            if (buf) {
                FPDFBookmark_GetTitle(bm, buf, n);
                if (o->n == o->cap) {
                    o->cap = o->cap ? o->cap * 2 : 64;
                    o->depth = realloc(o->depth, o->cap * sizeof(int));
                    o->page = realloc(o->page, o->cap * sizeof(int));
                    o->title = realloc(o->title, o->cap * sizeof(jstring));
                }
                jstring t = utf16(env, buf, n);
                free(buf);
                if (t) {
                    o->depth[o->n] = depth;
                    o->page[o->n] = page;
                    o->title[o->n] = (*env)->NewGlobalRef(env, t);
                    (*env)->DeleteLocalRef(env, t);
                    o->n++;
                }
            }
        }
        walk_outline(env, doc, FPDFBookmark_GetFirstChild(doc, bm), depth + 1, o);
        bm = FPDFBookmark_GetNextSibling(doc, bm);
    }
}

/** {int[] depths, int[] pages, String[] titles} */
JNIEXPORT jobjectArray JNICALL JFN(outline)(JNIEnv* env, jclass cls, jlong h) {
    FPDF_DOCUMENT doc = ((Doc*) (intptr_t) h)->doc;
    Outline o = {0};
    walk_outline(env, doc, FPDFBookmark_GetFirstChild(doc, NULL), 1, &o);
    jintArray depths = (*env)->NewIntArray(env, o.n), pages = (*env)->NewIntArray(env, o.n);
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray titles = (*env)->NewObjectArray(env, o.n, strCls, NULL);
    if (o.n > 0) {
        (*env)->SetIntArrayRegion(env, depths, 0, o.n, o.depth);
        (*env)->SetIntArrayRegion(env, pages, 0, o.n, o.page);
        for (int i = 0; i < o.n; i++) {
            (*env)->SetObjectArrayElement(env, titles, i, o.title[i]);
            (*env)->DeleteGlobalRef(env, o.title[i]);
        }
    }
    free(o.depth); free(o.page); free(o.title);
    jobjectArray out = (*env)->NewObjectArray(env, 3, (*env)->FindClass(env, "java/lang/Object"), NULL);
    (*env)->SetObjectArrayElement(env, out, 0, depths);
    (*env)->SetObjectArrayElement(env, out, 1, pages);
    (*env)->SetObjectArrayElement(env, out, 2, titles);
    return out;
}

// ---------- pages ----------

JNIEXPORT jlong JNICALL JFN(loadPage)(JNIEnv* env, jclass cls, jlong h, jint i) {
    Doc* d = (Doc*) (intptr_t) h;
    FPDF_PAGE p = FPDF_LoadPage(d->doc, i);
    if (!p) return 0;
    Pg* g = calloc(1, sizeof(Pg));
    if (!g) { FPDF_ClosePage(p); return 0; }
    g->page = p;
    g->doc = d;
    g->npaths = -1;
    return (jlong) (intptr_t) g;
}

JNIEXPORT void JNICALL JFN(closePage)(JNIEnv* env, jclass cls, jlong ph) {
    Pg* g = (Pg*) (intptr_t) ph;
    if (!g) return;
    FPDF_ClosePage(g->page);
    free(g->paths);
    free(g->factor);
    free(g);
}

static Mx concat(Mx m1, Mx m2) {   // apply m1, then m2
    Mx r;
    r.a = m1.a * m2.a + m1.b * m2.c;
    r.b = m1.a * m2.b + m1.b * m2.d;
    r.c = m1.c * m2.a + m1.d * m2.c;
    r.d = m1.c * m2.b + m1.d * m2.d;
    r.e = m1.e * m2.a + m1.f * m2.c + m2.e;
    r.f = m1.e * m2.b + m1.f * m2.d + m2.f;
    return r;
}

static Mx objMatrix(FPDF_PAGEOBJECT o) {
    FS_MATRIX m;
    Mx r = {1, 0, 0, 1, 0, 0};
    if (FPDFPageObj_GetMatrix(o, &m)) { r.a = m.a; r.b = m.b; r.c = m.c; r.d = m.d; r.e = m.e; r.f = m.f; }
    return r;
}

// Visits every path object of a page, inside forms too, with its full transform to page space.
typedef void (*Visit)(void* ctx, FPDF_PAGEOBJECT path, Mx m);

static void visit(FPDF_PAGEOBJECT o, Mx parent, int depth, Visit fn, void* ctx) {
    int type = FPDFPageObj_GetType(o);
    if (type == FPDF_PAGEOBJ_PATH) {
        fn(ctx, o, concat(objMatrix(o), parent));
    } else if (type == FPDF_PAGEOBJ_FORM && depth < 32) {
        Mx m = concat(objMatrix(o), parent);
        int n = FPDFFormObj_CountObjects(o);
        for (int i = 0; i < n; i++) {
            FPDF_PAGEOBJECT c = FPDFFormObj_GetObject(o, (unsigned long) i);
            if (c) visit(c, m, depth + 1, fn, ctx);
        }
    }
}

static void visitPage(FPDF_PAGE p, Visit fn, void* ctx) {
    Mx id = {1, 0, 0, 1, 0, 0};
    int n = FPDFPage_CountObjects(p);
    for (int i = 0; i < n; i++) {
        FPDF_PAGEOBJECT o = FPDFPage_GetObject(p, i);
        if (o) visit(o, id, 0, fn, ctx);
    }
}

static void collectStroked(void* ctx, FPDF_PAGEOBJECT path, Mx m) {
    Pg* g = (Pg*) ctx;
    int fill = 0;
    FPDF_BOOL stroke = 0;
    if (!FPDFPath_GetDrawMode(path, &fill, &stroke) || !stroke) return;
    if (g->npaths == g->cap) {
        int cap = g->cap ? g->cap * 2 : 1024;
        FPDF_PAGEOBJECT* p = realloc(g->paths, cap * sizeof(FPDF_PAGEOBJECT));
        if (!p) return;
        g->paths = p;
        float* f = realloc(g->factor, cap * sizeof(float));
        if (!f) return;
        g->factor = f;
        g->cap = cap;
    }
    g->paths[g->npaths] = path;
    float f = (float) sqrt(fabs(m.a * m.d - m.b * m.c));
    g->factor[g->npaths] = f > 1e-9f ? f : 1e-9f;
    g->npaths++;
}

/** Sets every line of the page to hairPx device pixels wide at this zoom (scale = px per point). */
static void applyHair(Pg* g, float scale, float hairPx) {
    if (g->npaths < 0) {
        g->npaths = 0;
        visitPage(g->page, collectStroked, g);
    }
    if (g->hairScale == scale && g->hairPx == hairPx) return;
    for (int i = 0; i < g->npaths; i++) {
        FPDFPageObj_SetStrokeWidth(g->paths[i], hairPx / (scale * g->factor[i]));
    }
    g->hairScale = scale;
    g->hairPx = hairPx;
}

static FPDF_BOOL needPause(IFSDK_PAUSE* p) {
    return p->user != NULL && atomic_load((atomic_int*) p->user) != 0;
}

JNIEXPORT void JNICALL JFN(abort)(JNIEnv* env, jclass cls, jlong h, jboolean on) {
    Doc* d = (Doc*) (intptr_t) h;
    if (d) atomic_store(&d->abort, on ? 1 : 0);
}

/**
 * Draws a page into the rectangle x0,y0 - x1,y1 of an ARGB_8888 bitmap, leaving the rest of
 * the bitmap alone. The page's top-left corner sits at startX,startY of the whole bitmap and
 * the page is sizeX x sizeY pixels (scale = pixels per point). hairPx > 0 draws every line at
 * that width. Returns false if it was aborted.
 */
JNIEXPORT jboolean JNICALL JFN(render)(JNIEnv* env, jclass cls, jlong ph, jobject bitmap,
                                       jint x0, jint y0, jint x1, jint y1,
                                       jint startX, jint startY, jint sizeX, jint sizeY,
                                       jfloat scale, jfloat hairPx, jboolean cancellable) {
    Pg* g = (Pg*) (intptr_t) ph;
    if (!g || x1 <= x0 || y1 <= y0) return JNI_TRUE;
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (x0 < 0 || y0 < 0 || x1 > (jint) info.width || y1 > (jint) info.height) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;

    unsigned char* first = (unsigned char*) pixels + (size_t) y0 * info.stride + (size_t) x0 * 4;
    FPDF_BITMAP b = FPDFBitmap_CreateEx(x1 - x0, y1 - y0, FPDFBitmap_BGRA, first, (int) info.stride);
    jboolean ok = JNI_FALSE;
    if (b) {
        FPDFBitmap_FillRect(b, 0, 0, x1 - x0, y1 - y0, 0xFFFFFFFF);
        if (hairPx > 0) applyHair(g, scale, hairPx);
        IFSDK_PAUSE pause;
        memset(&pause, 0, sizeof pause);
        pause.version = 1;
        pause.NeedToPauseNow = needPause;
        pause.user = cancellable ? (void*) &g->doc->abort : NULL;
        int st = FPDF_RenderPageBitmap_Start(b, g->page, startX - x0, startY - y0, sizeX, sizeY, 0,
                                             FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER, &pause);
        while (st == FPDF_RENDER_TOBECONTINUED && !needPause(&pause)) {
            st = FPDF_RenderPage_Continue(g->page, &pause);
        }
        ok = st != FPDF_RENDER_TOBECONTINUED;
        FPDF_RenderPage_Close(g->page);
        FPDFBitmap_Destroy(b);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

// ---------- geometry for snapping ----------

typedef struct { float* seg; int n, cap, max; Mx toTop; } Geo;

static void addSeg(Geo* g, double x0, double y0, double x1, double y1) {
    if (g->n >= g->max) return;
    if (g->n == g->cap) {
        int cap = g->cap ? g->cap * 2 : 4096;
        float* s = realloc(g->seg, (size_t) cap * 4 * sizeof(float));
        if (!s) { g->max = g->n; return; }
        g->seg = s;
        g->cap = cap;
    }
    float* p = g->seg + (size_t) g->n * 4;
    p[0] = (float) x0; p[1] = (float) y0; p[2] = (float) x1; p[3] = (float) y1;
    g->n++;
}

static void collectGeo(void* ctx, FPDF_PAGEOBJECT path, Mx m) {
    Geo* g = (Geo*) ctx;
    if (g->n >= g->max) return;
    Mx t = concat(m, g->toTop);
    int n = FPDFPath_CountSegments(path);
    double sx = 0, sy = 0, px = 0, py = 0;
    int curve = 0;
    for (int i = 0; i < n; i++) {
        FPDF_PATHSEGMENT s = FPDFPath_GetPathSegment(path, i);
        float x, y;
        if (!s || !FPDFPathSegment_GetPoint(s, &x, &y)) continue;
        double X = t.a * x + t.c * y + t.e, Y = t.b * x + t.d * y + t.f;
        int type = FPDFPathSegment_GetType(s);
        if (type == FPDF_SEGMENT_MOVETO) {
            sx = px = X; sy = py = Y;
            curve = 0;
        } else if (type == FPDF_SEGMENT_LINETO) {
            addSeg(g, px, py, X, Y);
            px = X; py = Y;
        } else if (type == FPDF_SEGMENT_BEZIERTO) {
            // three points per curve; only the end of a curve is a snap point
            if (++curve == 3) {
                addSeg(g, X, Y, X, Y);
                px = X; py = Y;
                curve = 0;
            }
        }
        if (FPDFPathSegment_GetClose(s) && (px != sx || py != sy)) {
            addSeg(g, px, py, sx, sy);
            px = sx; py = sy;
        }
    }
}

/** Line segments of the page as x0,y0,x1,y1 in points from the top-left corner, as displayed. */
JNIEXPORT jfloatArray JNICALL JFN(geometry)(JNIEnv* env, jclass cls, jlong ph, jint max) {
    Pg* pg = (Pg*) (intptr_t) ph;
    if (!pg) return NULL;
    FPDF_PAGE p = pg->page;
    // PDF space (y up) -> displayed points (y down, rotation applied), measured at 1000x
    double w = FPDF_GetPageWidthF(p), h = FPDF_GetPageHeightF(p);
    int sw = (int) lround(w * 1000), sh = (int) lround(h * 1000);
    int x0, y0, xa, ya, xb, yb;
    FPDF_PageToDevice(p, 0, 0, sw, sh, 0, 0, 0, &x0, &y0);
    FPDF_PageToDevice(p, 0, 0, sw, sh, 0, 1000, 0, &xa, &ya);
    FPDF_PageToDevice(p, 0, 0, sw, sh, 0, 0, 1000, &xb, &yb);
    Geo g;
    memset(&g, 0, sizeof g);
    g.max = max;
    g.toTop.a = (xa - x0) / 1e6; g.toTop.b = (ya - y0) / 1e6;
    g.toTop.c = (xb - x0) / 1e6; g.toTop.d = (yb - y0) / 1e6;
    g.toTop.e = x0 / 1000.0;     g.toTop.f = y0 / 1000.0;
    visitPage(p, collectGeo, &g);
    jfloatArray out = (*env)->NewFloatArray(env, g.n * 4);
    if (out && g.n > 0) (*env)->SetFloatArrayRegion(env, out, 0, g.n * 4, g.seg);
    free(g.seg);
    return out;
}

// ---------- text search ----------

#include "fpdf_text.h"

// Finds `query` (case-insensitive) on every page. Returns groups of 5 floats:
// page, left, top, right, bottom, in sheet points measured from the top-left corner.
JNIEXPORT jfloatArray JNICALL JFN(search)(JNIEnv* env, jclass cls, jlong h, jstring query, jint max) {
    Doc* d = (Doc*) (intptr_t) h;
    if (!d || !query) return NULL;
    const jchar* q = (*env)->GetStringChars(env, query, NULL);
    jsize ql = (*env)->GetStringLength(env, query);
    unsigned short* wq = malloc(sizeof(unsigned short) * (ql + 1));
    for (jsize i = 0; i < ql; i++) wq[i] = q[i];
    wq[ql] = 0;
    (*env)->ReleaseStringChars(env, query, q);
    int cap = 64, n = 0;
    float* out = malloc(sizeof(float) * 5 * cap);
    int pages = FPDF_GetPageCount(d->doc);
    for (int i = 0; i < pages && n < max; i++) {
        if (atomic_load(&d->abort)) break;
        FPDF_PAGE page = FPDF_LoadPage(d->doc, i);
        if (!page) continue;
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(page);
        if (tp) {
            int W = (int) (FPDF_GetPageWidthF(page) * 100), H = (int) (FPDF_GetPageHeightF(page) * 100);
            FPDF_SCHHANDLE sh = FPDFText_FindStart(tp, (FPDF_WIDESTRING) wq, 0, 0);
            while (sh && n < max && FPDFText_FindNext(sh)) {
                int at = FPDFText_GetSchResultIndex(sh), cnt = FPDFText_GetSchCount(sh);
                int rc = FPDFText_CountRects(tp, at, cnt);
                double x0 = 1e30, y0 = 1e30, x1 = -1e30, y1 = -1e30;
                for (int r = 0; r < rc; r++) {
                    double l, t, rr, b;
                    if (!FPDFText_GetRect(tp, r, &l, &t, &rr, &b)) continue;
                    double px[2] = {l, rr}, py[2] = {t, b};
                    for (int a = 0; a < 2; a++) for (int c = 0; c < 2; c++) {
                        int dx, dy;
                        FPDF_PageToDevice(page, 0, 0, W, H, 0, px[a], py[c], &dx, &dy);
                        if (dx / 100.0 < x0) x0 = dx / 100.0;
                        if (dx / 100.0 > x1) x1 = dx / 100.0;
                        if (dy / 100.0 < y0) y0 = dy / 100.0;
                        if (dy / 100.0 > y1) y1 = dy / 100.0;
                    }
                }
                if (x1 < x0) continue;
                if (n == cap) { cap *= 2; out = realloc(out, sizeof(float) * 5 * cap); }
                float* o = out + 5 * n++;
                o[0] = (float) i; o[1] = (float) x0; o[2] = (float) y0; o[3] = (float) x1; o[4] = (float) y1;
            }
            if (sh) FPDFText_FindClose(sh);
            FPDFText_ClosePage(tp);
        }
        FPDF_ClosePage(page);
    }
    free(wq);
    jfloatArray arr = (*env)->NewFloatArray(env, 5 * n);
    if (arr && n) (*env)->SetFloatArrayRegion(env, arr, 0, 5 * n, out);
    free(out);
    return arr;
}

// ---------- export with markup ----------

#include "fpdf_save.h"

typedef struct { FPDF_FILEWRITE w; FILE* f; } OutFile;

static int out_block(FPDF_FILEWRITE* w, const void* data, unsigned long size) {
    return fwrite(data, 1, size, ((OutFile*) w)->f) == size;
}

static unsigned char* slurp(const char* path, unsigned long* len) {
    if (!path) return NULL;
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char* b = n > 0 ? malloc((size_t) n) : NULL;
    if (b && fread(b, 1, (size_t) n, f) != (size_t) n) { free(b); b = NULL; }
    fclose(f);
    if (b) *len = (unsigned long) n;
    return b;
}

typedef struct {
    FPDF_DOCUMENT doc;
    FPDF_PAGE page;
    int W, H;                       // device size, 1/100 point
    FPDF_FONT helv, font, geo;
    unsigned char *fontData, *geoData;
    const char *fontPath, *geoPath;
    int fontTried, geoTried;
} Ex;

// sheet point (from the top-left corner) → PDF user space
static void d2p(Ex* x, double dx, double dy, double* px, double* py) {
    FPDF_DeviceToPage(x->page, 0, 0, x->W, x->H, 0, (int) lround(dx * 100), (int) lround(dy * 100), px, py);
}

static FPDF_FONT ex_font(Ex* x, const jchar* s, jsize n) {
    int geo = 0, wide = 0;
    for (jsize i = 0; i < n; i++) {
        jchar c = s[i];
        if ((c >= 0x10A0 && c <= 0x10FF) || (c >= 0x2D00 && c <= 0x2D2F) || (c >= 0x1C90 && c <= 0x1CBF)) geo = 1;
        else if (c > 0xFF) wide = 1;
    }
    if (geo) {
        if (!x->geoTried) {
            unsigned long len = 0;
            x->geoTried = 1;
            x->geoData = slurp(x->geoPath, &len);
            if (x->geoData) x->geo = FPDFText_LoadFont(x->doc, x->geoData, (uint32_t) len, FPDF_FONT_TRUETYPE, 1);
        }
        if (x->geo) return x->geo;
    }
    if (geo || wide) {
        if (!x->fontTried) {
            unsigned long len = 0;
            x->fontTried = 1;
            x->fontData = slurp(x->fontPath, &len);
            if (x->fontData) x->font = FPDFText_LoadFont(x->doc, x->fontData, (uint32_t) len, FPDF_FONT_TRUETYPE, 1);
        }
        if (x->font) return x->font;
    }
    if (!x->helv) x->helv = FPDFText_LoadStandardFont(x->doc, "Helvetica");
    return x->helv;
}

static void ex_poly(Ex* x, const float* xy, int n, int closed, int fill, const float* rgba, float width) {
    if (n < 2) return;
    FPDF_PAGEOBJECT path = NULL;
    for (int i = 0; i < n; i++) {
        double px, py;
        d2p(x, xy[2 * i], xy[2 * i + 1], &px, &py);
        if (!path) path = FPDFPageObj_CreateNewPath((float) px, (float) py);
        else FPDFPath_LineTo(path, (float) px, (float) py);
    }
    if (!path) return;
    if (closed || fill) FPDFPath_Close(path);
    unsigned R = (unsigned) (rgba[0] * 255), G = (unsigned) (rgba[1] * 255), B = (unsigned) (rgba[2] * 255), A = (unsigned) (rgba[3] * 255);
    if (fill) {
        FPDFPath_SetDrawMode(path, FPDF_FILLMODE_WINDING, 0);
        FPDFPageObj_SetFillColor(path, R, G, B, A);
    } else {
        FPDFPath_SetDrawMode(path, FPDF_FILLMODE_NONE, 1);
        FPDFPageObj_SetStrokeColor(path, R, G, B, A);
        FPDFPageObj_SetStrokeWidth(path, width);
        FPDFPageObj_SetLineJoin(path, FPDF_LINEJOIN_ROUND);
        FPDFPageObj_SetLineCap(path, FPDF_LINECAP_ROUND);
    }
    FPDFPage_InsertObject(x->page, path);
}

// One run of text in one font with its left baseline end at (bx, by) in sheet points;
// returns its width.
static double ex_run(Ex* x, const jchar* s, jsize n, double bx, double by, double size, const float* rgba, int measureOnly) {
    if (n <= 0) return 0;
    FPDF_FONT font = ex_font(x, s, n);
    if (!font) return 0;
    FPDF_PAGEOBJECT t = FPDFPageObj_CreateTextObj(x->doc, font, 1.0f);
    if (!t) return 0;
    unsigned short* w = malloc(sizeof(unsigned short) * (n + 1));
    for (jsize i = 0; i < n; i++) w[i] = s[i];
    w[n] = 0;
    FPDFText_SetText(t, (FPDF_WIDESTRING) w);
    free(w);
    float l = 0, b = 0, r = 0, tp = 0;
    double width = (FPDFPageObj_GetBounds(t, &l, &b, &r, &tp) && r > 0 ? r : 0.55 * n) * size;
    if (measureOnly) { FPDFPageObj_Destroy(t); return width; }
    double ox, oy, ax, ay, ux, uy;
    d2p(x, bx, by, &ox, &oy);
    d2p(x, bx + 10, by, &ax, &ay);                 // baseline direction on the sheet
    d2p(x, bx, by - 10, &ux, &uy);                 // "up" direction
    ax = (ax - ox) / 10; ay = (ay - oy) / 10; ux = (ux - ox) / 10; uy = (uy - oy) / 10;
    FPDFPageObj_Transform(t, ax * size, ay * size, ux * size, uy * size, ox, oy);
    FPDFPageObj_SetFillColor(t, (unsigned) (rgba[0] * 255), (unsigned) (rgba[1] * 255), (unsigned) (rgba[2] * 255), (unsigned) (rgba[3] * 255));
    FPDFPage_InsertObject(x->page, t);
    return width;
}

static int ex_geo(jchar c) { return (c >= 0x10A0 && c <= 0x10FF) || (c >= 0x2D00 && c <= 0x2D2F) || (c >= 0x1C90 && c <= 0x1CBF); }

// One line, split into runs so Georgian and other scripts each get a font that has them.
static double ex_text(Ex* x, const jchar* s, jsize n, double bx, double by, double size, const float* rgba, int measureOnly) {
    double total = 0;
    jsize start = 0;
    while (start < n) {
        int geo = ex_geo(s[start]);
        jsize end = start + 1;
        while (end < n && (ex_geo(s[end]) == geo || s[end] == ' ')) end++;
        double w = ex_run(x, s + start, end - start, bx + total, by, size, rgba, measureOnly);
        int sp = 0;
        for (jsize i = end - 1; i >= start && s[i] == ' '; i--) sp++;
        total += w + sp * 0.28 * size;
        start = end;
    }
    return total;
}

// Writes a copy of the open file with markup and measurements drawn into the sheets.
// ops: records of floats —
//   0 stroke: page, r, g, b, a, width, closed, n, x0, y0 … (n points)
//   1 fill:   page, r, g, b, a, n, x0, y0 …
//   2 text:   page, r, g, b, a, size, textIndex, x, y, tagged
//             (tagged 0: (x, y) is the top-left of a block of lines;
//              tagged 1: (x, y) is the centre of a label on a dark rounded tag)
// Records must be grouped by page.
JNIEXPORT jboolean JNICALL JFN(exportMarked)(JNIEnv* env, jclass cls, jint fd, jlong size, jstring password,
                                             jstring outPath, jfloatArray ops, jobjectArray texts,
                                             jstring font, jstring geoFont) {
    ensure_init();
    Doc d;
    memset(&d, 0, sizeof d);
    d.fd = fd;
    d.access.m_FileLen = (unsigned long) size;
    d.access.m_GetBlock = read_block;
    d.access.m_Param = &d;
    const char* pw = password ? (*env)->GetStringUTFChars(env, password, NULL) : NULL;
    FPDF_DOCUMENT doc = FPDF_LoadCustomDocument(&d.access, pw);
    if (pw) (*env)->ReleaseStringUTFChars(env, password, pw);
    if (!doc) return JNI_FALSE;

    Ex x;
    memset(&x, 0, sizeof x);
    x.doc = doc;
    x.fontPath = font ? (*env)->GetStringUTFChars(env, font, NULL) : NULL;
    x.geoPath = geoFont ? (*env)->GetStringUTFChars(env, geoFont, NULL) : NULL;

    jsize n = (*env)->GetArrayLength(env, ops);
    float* o = (*env)->GetFloatArrayElements(env, ops, NULL);
    int cur = -1, ok = 1;
    jsize i = 0;
    while (i < n && ok) {
        int kind = (int) o[i], page = (int) o[i + 1];
        if (page != cur) {
            if (x.page) { FPDFPage_GenerateContent(x.page); FPDF_ClosePage(x.page); x.page = NULL; }
            cur = page;
            x.page = FPDF_LoadPage(doc, page);
            if (!x.page) { ok = 0; break; }
            x.W = (int) lround(FPDF_GetPageWidthF(x.page) * 100);
            x.H = (int) lround(FPDF_GetPageHeightF(x.page) * 100);
        }
        const float* rgba = o + i + 2;
        if (kind == 0) {
            float width = o[i + 6];
            int closed = (int) o[i + 7], cnt = (int) o[i + 8];
            ex_poly(&x, o + i + 9, cnt, closed, 0, rgba, width);
            i += 9 + 2 * cnt;
        } else if (kind == 1) {
            int cnt = (int) o[i + 6];
            ex_poly(&x, o + i + 7, cnt, 1, 1, rgba, 0);
            i += 7 + 2 * cnt;
        } else if (kind == 2) {
            double sz = o[i + 6];
            int idx = (int) o[i + 7];
            double tx = o[i + 8], ty = o[i + 9];
            int tagged = (int) o[i + 10];
            i += 11;
            jstring js = (jstring) (*env)->GetObjectArrayElement(env, texts, idx);
            if (!js) continue;
            const jchar* s = (*env)->GetStringChars(env, js, NULL);
            jsize len = (*env)->GetStringLength(env, js);
            if (tagged) {
                double w = ex_text(&x, s, len, 0, 0, sz, rgba, 1);
                double pad = sz * 0.45, hw = w / 2 + pad, hh = sz * 0.85;
                float box[] = {(float) (tx - hw), (float) (ty - hh), (float) (tx + hw), (float) (ty - hh),
                               (float) (tx + hw), (float) (ty + hh), (float) (tx - hw), (float) (ty + hh)};
                float dark[] = {0.10f, 0.11f, 0.12f, 0.9f};
                ex_poly(&x, box, 4, 1, 1, dark, 0);
                ex_text(&x, s, len, tx - w / 2, ty + sz * 0.36, sz, rgba, 0);
            } else {
                double line = ty + sz;
                jsize start = 0;
                for (jsize k = 0; k <= len; k++) {
                    if (k < len && s[k] != '\n') continue;
                    ex_text(&x, s + start, k - start, tx, line, sz, rgba, 0);
                    line += sz * 1.25;
                    start = k + 1;
                }
            }
            (*env)->ReleaseStringChars(env, js, s);
            (*env)->DeleteLocalRef(env, js);
        } else {
            ok = 0;
        }
    }
    (*env)->ReleaseFloatArrayElements(env, ops, o, JNI_ABORT);
    if (x.page) { FPDFPage_GenerateContent(x.page); FPDF_ClosePage(x.page); }

    if (ok) {
        const char* op = (*env)->GetStringUTFChars(env, outPath, NULL);
        OutFile w;
        memset(&w, 0, sizeof w);
        w.w.version = 1;
        w.w.WriteBlock = out_block;
        w.f = fopen(op, "wb");
        if (!w.f) ok = 0;
        else {
            ok = FPDF_SaveAsCopy(doc, &w.w, FPDF_NO_INCREMENTAL);
            if (fclose(w.f) != 0) ok = 0;
            if (!ok) remove(op);
        }
        (*env)->ReleaseStringUTFChars(env, outPath, op);
    }
    if (x.helv) FPDFFont_Close(x.helv);
    if (x.font) FPDFFont_Close(x.font);
    if (x.geo) FPDFFont_Close(x.geo);
    FPDF_CloseDocument(doc);
    free(x.fontData);
    free(x.geoData);
    if (x.fontPath) (*env)->ReleaseStringUTFChars(env, font, x.fontPath);
    if (x.geoPath) (*env)->ReleaseStringUTFChars(env, geoFont, x.geoPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}
