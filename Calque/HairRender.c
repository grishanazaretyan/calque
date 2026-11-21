#include "HairRender.h"

#include <math.h>
#include <pthread.h>
#include "dwg2pdf.h"
#include <stdlib.h>
#include <string.h>

#include "fpdfview.h"
#include "fpdf_edit.h"

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int initialised = 0;

typedef struct { double a, b, c, d, e, f; } Mx;

#define CACHE 4

typedef struct {
    int index;
    FPDF_PAGE page;
    int npaths, cap;
    FPDF_PAGEOBJECT *paths;
    float *factor;
    double hairScale;
    float hairPx;
    unsigned long used;
} Page;

typedef struct {
    FPDF_DOCUMENT doc;
    Page pages[CACHE];
    unsigned long clock;
} Doc;

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

static Mx invert(Mx m) {
    double det = m.a * m.d - m.b * m.c;
    Mx r = {1, 0, 0, 1, 0, 0};
    if (fabs(det) < 1e-12) return r;
    r.a = m.d / det;
    r.b = -m.b / det;
    r.c = -m.c / det;
    r.d = m.a / det;
    r.e = -(m.e * r.a + m.f * r.c);
    r.f = -(m.e * r.b + m.f * r.d);
    return r;
}

static Mx objMatrix(FPDF_PAGEOBJECT o) {
    FS_MATRIX m;
    Mx r = {1, 0, 0, 1, 0, 0};
    if (FPDFPageObj_GetMatrix(o, &m)) { r.a = m.a; r.b = m.b; r.c = m.c; r.d = m.d; r.e = m.e; r.f = m.f; }
    return r;
}

static void add(Page *p, FPDF_PAGEOBJECT o, Mx m) {
    int fill = 0;
    FPDF_BOOL stroke = 0;
    if (!FPDFPath_GetDrawMode(o, &fill, &stroke) || !stroke) return;
    if (p->npaths == p->cap) {
        int cap = p->cap ? p->cap * 2 : 1024;
        FPDF_PAGEOBJECT *a = realloc(p->paths, cap * sizeof(FPDF_PAGEOBJECT));
        if (!a) return;
        p->paths = a;
        float *f = realloc(p->factor, cap * sizeof(float));
        if (!f) return;
        p->factor = f;
        p->cap = cap;
    }
    p->paths[p->npaths] = o;
    float f = (float) sqrt(fabs(m.a * m.d - m.b * m.c));
    p->factor[p->npaths] = f > 1e-9f ? f : 1e-9f;
    p->npaths++;
}

static void visit(Page *p, FPDF_PAGEOBJECT o, Mx parent, int depth) {
    int type = FPDFPageObj_GetType(o);
    if (type == FPDF_PAGEOBJ_PATH) {
        add(p, o, concat(objMatrix(o), parent));
    } else if (type == FPDF_PAGEOBJ_FORM && depth < 32) {
        Mx m = concat(objMatrix(o), parent);
        int n = FPDFFormObj_CountObjects(o);
        for (int i = 0; i < n; i++) {
            FPDF_PAGEOBJECT c = FPDFFormObj_GetObject(o, (unsigned long) i);
            if (c) visit(p, c, m, depth + 1);
        }
    }
}

static void freePage(Page *p) {
    if (p->page) FPDF_ClosePage(p->page);
    free(p->paths);
    free(p->factor);
    memset(p, 0, sizeof(Page));
    p->index = -1;
}

static Page *page(Doc *d, int index) {
    Page *free_slot = NULL;
    for (int i = 0; i < CACHE; i++) {
        if (d->pages[i].page && d->pages[i].index == index) {
            d->pages[i].used = ++d->clock;
            return &d->pages[i];
        }
        if (!free_slot || d->pages[i].used < free_slot->used) free_slot = &d->pages[i];
    }
    freePage(free_slot);
    FPDF_PAGE pg = FPDF_LoadPage(d->doc, index);
    if (!pg) return NULL;
    free_slot->page = pg;
    free_slot->index = index;
    free_slot->used = ++d->clock;
    Mx id = {1, 0, 0, 1, 0, 0};
    int n = FPDFPage_CountObjects(pg);
    for (int i = 0; i < n; i++) {
        FPDF_PAGEOBJECT o = FPDFPage_GetObject(pg, i);
        if (o) visit(free_slot, o, id, 0);
    }
    return free_slot;
}

static void ensure_init(void) {
    if (!initialised) {
        FPDF_LIBRARY_CONFIG cfg;
        memset(&cfg, 0, sizeof cfg);
        cfg.version = 2;
        FPDF_InitLibraryWithConfig(&cfg);
        initialised = 1;
    }
}

int calque_convert(const char *path, int dwg, const char *tmpDxf, const char *pdf,
                   const char *font, const char *georgianFont, DxfPages *out) {
    pthread_mutex_lock(&lock);
    ensure_init();
    int r = dwg ? dwg_to_pdf_pages(path, tmpDxf, pdf, font, georgianFont, out)
                : dxf_to_pdf_pages(path, pdf, font, georgianFont, out);
    pthread_mutex_unlock(&lock);
    return r;
}

void *hair_open(const char *path, const char *password) {
    pthread_mutex_lock(&lock);
    ensure_init();
    Doc *d = NULL;
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, password);
    if (doc) {
        d = calloc(1, sizeof(Doc));
        if (d) {
            d->doc = doc;
            for (int i = 0; i < CACHE; i++) d->pages[i].index = -1;
        } else {
            FPDF_CloseDocument(doc);
        }
    }
    pthread_mutex_unlock(&lock);
    return d;
}

void hair_close(void *h) {
    Doc *d = (Doc *) h;
    if (!d) return;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < CACHE; i++) freePage(&d->pages[i]);
    FPDF_CloseDocument(d->doc);
    free(d);
    pthread_mutex_unlock(&lock);
}

int hair_render(void *h, int pageIndex, uint8_t *rgba, int w, int h2, int stride,
                double x0, double y0, double x1, double y1, double scale, float hairPx) {
    Doc *d = (Doc *) h;
    if (!d || w <= 0 || h2 <= 0) return 0;
    int ok = 0;
    pthread_mutex_lock(&lock);
    Page *p = page(d, pageIndex);
    if (p) {
        // every line hairPx pixels wide at this scale
        if (p->hairScale != scale || p->hairPx != hairPx) {
            for (int i = 0; i < p->npaths; i++) {
                FPDFPageObj_SetStrokeWidth(p->paths[i], (float) (hairPx / (scale * p->factor[i])));
            }
            p->hairScale = scale;
            p->hairPx = hairPx;
        }
        // PDFium draws in "display space" (points, y down, page rotation applied). Measure the
        // map from PDF user space to display space at 1000x, then build display -> bitmap.
        double pw = FPDF_GetPageWidthF(p->page), ph = FPDF_GetPageHeightF(p->page);
        int sw = (int) lround(pw * 1000), sh = (int) lround(ph * 1000);
        int ax, ay, bx, by, cx, cy;
        FPDF_PageToDevice(p->page, 0, 0, sw, sh, 0, 0, 0, &ax, &ay);
        FPDF_PageToDevice(p->page, 0, 0, sw, sh, 0, 1000, 0, &bx, &by);
        FPDF_PageToDevice(p->page, 0, 0, sw, sh, 0, 0, 1000, &cx, &cy);
        Mx userToDisplay = {(bx - ax) / 1e6, (by - ay) / 1e6, (cx - ax) / 1e6, (cy - ay) / 1e6, ax / 1000.0, ay / 1000.0};
        Mx userToBitmap = {scale, 0, 0, -scale, -x0 * scale, y1 * scale};
        Mx m = concat(invert(userToDisplay), userToBitmap);
        FPDF_BITMAP b = FPDFBitmap_CreateEx(w, h2, FPDFBitmap_BGRA, rgba, stride);
        if (b) {
            FPDFBitmap_FillRect(b, 0, 0, w, h2, 0xFFFFFFFF);
            FS_MATRIX fm = {(float) m.a, (float) m.b, (float) m.c, (float) m.d, (float) m.e, (float) m.f};
            FS_RECTF clip = {0, 0, (float) w, (float) h2};
            FPDF_RenderPageBitmapWithMatrix(b, p->page, &fm, &clip, FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER);
            FPDFBitmap_Destroy(b);
            ok = 1;
        }
    }
    pthread_mutex_unlock(&lock);
    return ok;
}
