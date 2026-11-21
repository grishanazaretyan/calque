// DXF → PDF for Calque.
//
// Reads the model space of an ASCII DXF (lines, polylines with bulges, circles, arcs,
// ellipses, splines, text, mtext, blocks with nested inserts and arrays, dimensions,
// solids, leaders) and writes it with PDFium as one vector page at a standard drawing
// scale, so the result opens in the normal PDF viewer and measures to real size.
// Hatches, images, 3D solids and paper-space layouts are not drawn.

#include "dxf2pdf.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fpdf_edit.h"
#include "fpdf_save.h"
#include "fpdfview.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ---------------------------------------------------------------- tokens

typedef struct { int code; char* val; } Pair;

typedef struct {
    char* name;
    uint32_t rgb;
    int hidden;          // off or frozen
} Layer;

typedef struct {
    char* name;
    double bx, by;
    int start, end;      // pair range of the block's entities
    int busy;            // recursion guard
} Block;

typedef struct { double a, b, c, d, e, f; } Mat;   // x' = a x + c y + e,  y' = b x + d y + f

static const Mat IDENT = {1, 0, 0, 1, 0, 0};

static Mat mat_mul(Mat m, Mat n) {   // m ∘ n
    Mat r;
    r.a = m.a * n.a + m.c * n.b;
    r.b = m.b * n.a + m.d * n.b;
    r.c = m.a * n.c + m.c * n.d;
    r.d = m.b * n.c + m.d * n.d;
    r.e = m.a * n.e + m.c * n.f + m.e;
    r.f = m.b * n.e + m.d * n.f + m.f;
    return r;
}
static Mat mat_tr(double x, double y) { Mat m = {1, 0, 0, 1, x, y}; return m; }
static Mat mat_sc(double x, double y) { Mat m = {x, 0, 0, y, 0, 0}; return m; }
static Mat mat_rot(double r) { double c = cos(r), s = sin(r); Mat m = {c, s, -s, c, 0, 0}; return m; }
static void mat_ap(const Mat* m, double x, double y, double* ox, double* oy) {
    *ox = m->a * x + m->c * y + m->e;
    *oy = m->b * x + m->d * y + m->f;
}

// ---------------------------------------------------------------- context

#define MAX_COLORS 64
#define MAX_SAMPLES 262144

typedef struct {
    uint32_t rgb;
    FPDF_PAGEOBJECT path;
    int segs;
} ColorPath;

typedef struct {
    Pair* p;
    int n;
    int utf8, cp1251;
    double unit;                 // drawing units → mm

    Layer* layers; int nLayers;
    Block* blocks; int nBlocks;
    int entStart, entEnd;

    // pass 1: extents
    int pass;
    double minx, miny, maxx, maxy;
    float* sx; float* sy; int ns; int stride; long counter;

    // pass 2: PDF
    double ox, oy, k, margin;    // page point = margin + (mm - o) * k
    double pw, ph;               // page size, points
    FPDF_DOCUMENT doc;
    FPDF_PAGE page;
    FPDF_FONT helv, font, geo;
    unsigned char* fontData; size_t fontLen;
    unsigned char* geoData; size_t geoLen;
    const char* fontPath; const char* geoPath;
    int fontTried, geoTried;
    ColorPath cp[MAX_COLORS]; int ncp;
    long drawn;
} Ctx;

// ---------------------------------------------------------------- helpers

static char* trim(char* s) {
    while (*s == ' ' || *s == '\t') s++;
    size_t n = strlen(s);
    while (n && (s[n - 1] == ' ' || s[n - 1] == '\t' || s[n - 1] == '\r')) s[--n] = 0;
    return s;
}

static int is(const char* a, const char* b) { return a && strcmp(a, b) == 0; }


// Value of the first `code` in pairs [i0, i1), or def.
static const char* fstr(Ctx* c, int i0, int i1, int code) {
    for (int i = i0; i < i1; i++) if (c->p[i].code == code) return c->p[i].val;
    return NULL;
}
static double fnum(Ctx* c, int i0, int i1, int code, double def) {
    const char* s = fstr(c, i0, i1, code);
    return s ? atof(s) : def;
}
static int fint(Ctx* c, int i0, int i1, int code, int def) {
    const char* s = fstr(c, i0, i1, code);
    return s ? atoi(s) : def;
}
static int ent_end(Ctx* c, int i) {   // index of the next code-0 pair after i
    int j = i + 1;
    while (j < c->n && c->p[j].code != 0) j++;
    return j;
}

// ---------------------------------------------------------------- colours

static uint32_t rgb3(double r, double g, double b) {
    int R = (int) lround(r * 255), G = (int) lround(g * 255), B = (int) lround(b * 255);
    if (R < 0) R = 0;
    if (R > 255) R = 255;
    if (G < 0) G = 0;
    if (G > 255) G = 255;
    if (B < 0) B = 0;
    if (B > 255) B = 255;
    return (uint32_t) (R << 16 | G << 8 | B);
}

// Colours meant for a black CAD screen, darkened where they would vanish on white paper.
static uint32_t paper(uint32_t c) {
    double r = (c >> 16 & 255) / 255.0, g = (c >> 8 & 255) / 255.0, b = (c & 255) / 255.0;
    double l = 0.299 * r + 0.587 * g + 0.114 * b;
    if (l > 0.93) return 0x000000;          // white lines draw black
    if (l > 0.5) { double f = 0.45 / l; return rgb3(r * f, g * f, b * f); }
    return c;
}

static uint32_t aci(int i) {
    if (i < 0) i = -i;
    switch (i) {
        case 1: return 0xFF0000; case 2: return 0xFFFF00; case 3: return 0x00FF00;
        case 4: return 0x00FFFF; case 5: return 0x0000FF; case 6: return 0xFF00FF;
        case 7: return 0xFFFFFF; case 8: return 0x808080; case 9: return 0xC0C0C0;
    }
    if (i >= 250 && i <= 255) {
        static const int g[] = {51, 91, 132, 173, 214, 255};
        int v = g[i - 250];
        return (uint32_t) (v << 16 | v << 8 | v);
    }
    if (i < 10 || i > 249) return 0xFFFFFF;
    double h = (i - 10) / 10 * 15.0;              // hue, degrees
    int k = i % 10;
    static const double val[] = {1.0, 1.0, 0.8, 0.8, 0.6, 0.6, 0.5, 0.5, 0.3, 0.3};
    double v = val[k], s = (k % 2) ? 0.5 : 1.0;
    double hh = h / 60.0, x = 1 - fabs(fmod(hh, 2) - 1);
    double r = 0, g = 0, b = 0;
    if (hh < 1) { r = 1; g = x; } else if (hh < 2) { r = x; g = 1; } else if (hh < 3) { g = 1; b = x; }
    else if (hh < 4) { g = x; b = 1; } else if (hh < 5) { r = x; b = 1; } else { r = 1; b = x; }
    r = (r * s + (1 - s)) * v; g = (g * s + (1 - s)) * v; b = (b * s + (1 - s)) * v;
    return rgb3(r, g, b);
}

// ---------------------------------------------------------------- lookups

static int cmp_layer(const void* a, const void* b) { return strcmp(((const Layer*) a)->name, ((const Layer*) b)->name); }
static int cmp_block(const void* a, const void* b) { return strcmp(((const Block*) a)->name, ((const Block*) b)->name); }

static Layer* find_layer(Ctx* c, const char* name) {
    if (!name || !c->nLayers) return NULL;
    Layer key = {(char*) name, 0, 0};
    return bsearch(&key, c->layers, c->nLayers, sizeof(Layer), cmp_layer);
}
static Block* find_block(Ctx* c, const char* name) {
    if (!name || !c->nBlocks) return NULL;
    Block key = {(char*) name, 0, 0, 0, 0, 0};
    return bsearch(&key, c->blocks, c->nBlocks, sizeof(Block), cmp_block);
}

// ---------------------------------------------------------------- output

static void sample(Ctx* c, double x, double y) {
    if (!(fabs(x) < 1e12 && fabs(y) < 1e12)) return;
    if (x < c->minx) c->minx = x;
    if (x > c->maxx) c->maxx = x;
    if (y < c->miny) c->miny = y;
    if (y > c->maxy) c->maxy = y;
    if (c->counter++ % c->stride) return;
    if (c->ns == MAX_SAMPLES) {               // keep every second sample, sample half as often
        for (int i = 0; i < MAX_SAMPLES / 2; i++) { c->sx[i] = c->sx[2 * i]; c->sy[i] = c->sy[2 * i]; }
        c->ns = MAX_SAMPLES / 2;
        c->stride *= 2;
    }
    c->sx[c->ns] = (float) x;
    c->sy[c->ns] = (float) y;
    c->ns++;
}

static ColorPath* color_path(Ctx* c, uint32_t rgb) {
    for (int i = 0; i < c->ncp; i++) if (c->cp[i].rgb == rgb) return &c->cp[i];
    if (c->ncp == MAX_COLORS) return &c->cp[MAX_COLORS - 1];
    ColorPath* p = &c->cp[c->ncp++];
    p->rgb = rgb; p->path = NULL; p->segs = 0;
    return p;
}

static void flush_path(Ctx* c, ColorPath* p) {
    if (!p->path) return;
    FPDFPath_SetDrawMode(p->path, FPDF_FILLMODE_NONE, 1);
    FPDFPageObj_SetStrokeColor(p->path, p->rgb >> 16 & 255, p->rgb >> 8 & 255, p->rgb & 255, 255);
    FPDFPageObj_SetStrokeWidth(p->path, 0.24f);
    FPDFPageObj_SetLineJoin(p->path, FPDF_LINEJOIN_ROUND);
    FPDFPageObj_SetLineCap(p->path, FPDF_LINECAP_ROUND);
    FPDFPage_InsertObject(c->page, p->path);
    p->path = NULL;
    p->segs = 0;
}

// Clips segment a→b to the rectangle; returns 0 when nothing is left (Liang–Barsky).
static int clip_seg(double x0, double y0, double x1, double y1, double minX, double minY, double maxX, double maxY,
                    double* t0, double* t1) {
    double dx = x1 - x0, dy = y1 - y0, p[4] = {-dx, dx, -dy, dy}, q[4] = {x0 - minX, maxX - x0, y0 - minY, maxY - y0};
    *t0 = 0; *t1 = 1;
    for (int i = 0; i < 4; i++) {
        if (p[i] == 0) { if (q[i] < 0) return 0; continue; }
        double r = q[i] / p[i];
        if (p[i] < 0) { if (r > *t1) return 0; if (r > *t0) *t0 = r; }
        else { if (r < *t0) return 0; if (r < *t1) *t1 = r; }
    }
    return 1;
}

// One polyline in WCS drawing units. Parts far outside the sheet are cut off: PDFium
// draws stray lines when a path reaches tens of thousands of points off the page.
static void emit(Ctx* c, const double* xy, int n, int closed, uint32_t rgb) {
    if (n < 2) return;
    if (c->pass == 0) {
        for (int i = 0; i < n; i++) sample(c, xy[2 * i] * c->unit, xy[2 * i + 1] * c->unit);
        return;
    }
    ColorPath* p = color_path(c, paper(rgb));
    double m = (c->pw > c->ph ? c->pw : c->ph) * 0.5;
    double minX = -m, minY = -m, maxX = c->pw + m, maxY = c->ph + m;
    int segs = closed ? n : n - 1;
    double lx = 0, ly = 0;
    int open = 0;                          // a subpath is running and ends at (lx, ly)
    for (int i = 0; i < segs; i++) {
        int j = (i + 1) % n;
        double X0 = c->margin + (xy[2 * i] * c->unit - c->ox) * c->k;
        double Y0 = c->margin + (xy[2 * i + 1] * c->unit - c->oy) * c->k;
        double X1 = c->margin + (xy[2 * j] * c->unit - c->ox) * c->k;
        double Y1 = c->margin + (xy[2 * j + 1] * c->unit - c->oy) * c->k;
        double t0, t1;
        if (!(isfinite(X0) && isfinite(Y0) && isfinite(X1) && isfinite(Y1)) ||
            !clip_seg(X0, Y0, X1, Y1, minX, minY, maxX, maxY, &t0, &t1)) { open = 0; continue; }
        double ax = X0 + (X1 - X0) * t0, ay = Y0 + (Y1 - Y0) * t0;
        double bx = X0 + (X1 - X0) * t1, by = Y0 + (Y1 - Y0) * t1;
        if (!open || fabs(ax - lx) > 1e-6 || fabs(ay - ly) > 1e-6) {
            if (!p->path) p->path = FPDFPageObj_CreateNewPath((float) ax, (float) ay);
            else FPDFPath_MoveTo(p->path, (float) ax, (float) ay);
        }
        FPDFPath_LineTo(p->path, (float) bx, (float) by);
        lx = bx; ly = by; open = 1;
        p->segs++;
    }
    c->drawn += n;
    if (p->segs > 4000) flush_path(c, p);
}

// A filled area (solid hatch). Rings are consecutive point runs; starts[] marks each ring.
static void emit_fill(Ctx* c, const double* xy, const int* starts, int rings, int total, uint32_t rgb) {
    if (total < 3) return;
    if (c->pass == 0) {
        for (int i = 0; i < total; i++) sample(c, xy[2 * i] * c->unit, xy[2 * i + 1] * c->unit);
        return;
    }
    FPDF_PAGEOBJECT path = NULL;
    for (int r = 0; r < rings; r++) {
        int a = starts[r], b = r + 1 < rings ? starts[r + 1] : total;
        if (b - a < 3) continue;
        double m = (c->pw > c->ph ? c->pw : c->ph) * 0.5;
        for (int i = a; i < b; i++) {
            double fx = c->margin + (xy[2 * i] * c->unit - c->ox) * c->k;
            double fy = c->margin + (xy[2 * i + 1] * c->unit - c->oy) * c->k;
            if (!isfinite(fx) || !isfinite(fy)) continue;
            float X = (float) (fx < -m ? -m : fx > c->pw + m ? c->pw + m : fx);
            float Y = (float) (fy < -m ? -m : fy > c->ph + m ? c->ph + m : fy);
            if (i == a) {
                if (!path) path = FPDFPageObj_CreateNewPath(X, Y);
                else FPDFPath_MoveTo(path, X, Y);
            } else {
                FPDFPath_LineTo(path, X, Y);
            }
        }
        FPDFPath_Close(path);
    }
    if (!path) return;
    uint32_t col = paper(rgb);
    FPDFPath_SetDrawMode(path, FPDF_FILLMODE_ALTERNATE, 0);
    FPDFPageObj_SetFillColor(path, col >> 16 & 255, col >> 8 & 255, col & 255, 255);
    FPDFPage_InsertObject(c->page, path);
    c->drawn++;
}

// ---------------------------------------------------------------- point buffer

typedef struct { double* xy; int n, cap; } Pts;

static void pts_add(Pts* p, double x, double y) {
    if (p->n == p->cap) {
        p->cap = p->cap ? p->cap * 2 : 64;
        p->xy = realloc(p->xy, sizeof(double) * 2 * p->cap);
    }
    p->xy[2 * p->n] = x;
    p->xy[2 * p->n + 1] = y;
    p->n++;
}

static void pts_emit(Ctx* c, Pts* p, const Mat* m, int closed, uint32_t rgb) {
    for (int i = 0; i < p->n; i++) {
        double x, y;
        mat_ap(m, p->xy[2 * i], p->xy[2 * i + 1], &x, &y);
        p->xy[2 * i] = x;
        p->xy[2 * i + 1] = y;
    }
    emit(c, p->xy, p->n, closed, rgb);
    p->n = 0;
}

static int arc_steps(double sweep) {
    int n = (int) ceil(fabs(sweep) / (2 * M_PI) * 96);
    return n < 4 ? 4 : n > 400 ? 400 : n;
}

static void pts_arc(Pts* p, double cx, double cy, double r, double a0, double sweep, int first) {
    int n = arc_steps(sweep);
    for (int i = first ? 0 : 1; i <= n; i++) {
        double a = a0 + sweep * i / n;
        pts_add(p, cx + r * cos(a), cy + r * sin(a));
    }
}

// Segment from (x0,y0) to (x1,y1) with a DXF bulge (tan of a quarter of the arc angle).
static void pts_bulge(Pts* p, double x0, double y0, double x1, double y1, double bulge) {
    if (fabs(bulge) < 1e-9) { pts_add(p, x1, y1); return; }
    double dx = x1 - x0, dy = y1 - y0, chord = hypot(dx, dy);
    if (chord < 1e-12) { pts_add(p, x1, y1); return; }
    double theta = 4 * atan(bulge);
    double r = chord / (2 * sin(theta / 2));
    double mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
    double h = r * cos(theta / 2);                 // centre distance from the chord
    double nx = -dy / chord, ny = dx / chord;
    double cx = mx + nx * h, cy = my + ny * h;
    double a0 = atan2(y0 - cy, x0 - cx);
    pts_arc(p, cx, cy, fabs(r), a0, theta, 0);
}

// ---------------------------------------------------------------- text

static const uint16_t CP1251[128] = {
    0x0402,0x0403,0x201A,0x0453,0x201E,0x2026,0x2020,0x2021,0x20AC,0x2030,0x0409,0x2039,0x040A,0x040C,0x040B,0x040F,
    0x0452,0x2018,0x2019,0x201C,0x201D,0x2022,0x2013,0x2014,0x0020,0x2122,0x0459,0x203A,0x045A,0x045C,0x045B,0x045F,
    0x00A0,0x040E,0x045E,0x0408,0x00A4,0x0490,0x00A6,0x00A7,0x0401,0x00A9,0x0404,0x00AB,0x00AC,0x00AD,0x00AE,0x0407,
    0x00B0,0x00B1,0x0406,0x0456,0x0491,0x00B5,0x00B6,0x00B7,0x0451,0x2116,0x0454,0x00BB,0x0458,0x0405,0x0455,0x0457,
    0x0410,0x0411,0x0412,0x0413,0x0414,0x0415,0x0416,0x0417,0x0418,0x0419,0x041A,0x041B,0x041C,0x041D,0x041E,0x041F,
    0x0420,0x0421,0x0422,0x0423,0x0424,0x0425,0x0426,0x0427,0x0428,0x0429,0x042A,0x042B,0x042C,0x042D,0x042E,0x042F,
    0x0430,0x0431,0x0432,0x0433,0x0434,0x0435,0x0436,0x0437,0x0438,0x0439,0x043A,0x043B,0x043C,0x043D,0x043E,0x043F,
    0x0440,0x0441,0x0442,0x0443,0x0444,0x0445,0x0446,0x0447,0x0448,0x0449,0x044A,0x044B,0x044C,0x044D,0x044E,0x044F,
};

// Next code point of a DXF string.
static uint32_t next_cp(Ctx* c, const unsigned char** s) {
    const unsigned char* p = *s;
    uint32_t ch = *p++;
    if (ch >= 0x80) {
        if (c->utf8 && ch >= 0xC0) {
            int extra = ch >= 0xF0 ? 3 : ch >= 0xE0 ? 2 : 1;
            uint32_t v = ch & (0x3F >> extra);
            int ok = 1;
            for (int i = 0; i < extra; i++) {
                if ((p[i] & 0xC0) != 0x80) { ok = 0; break; }
                v = v << 6 | (p[i] & 0x3F);
            }
            if (ok) { p += extra; ch = v; }
        } else if (c->cp1251) {
            ch = CP1251[ch - 0x80];
        }
    }
    *s = p;
    return ch;
}

typedef struct { uint32_t* cp; int n, cap; } Str;

static void str_add(Str* s, uint32_t ch) {
    if (s->n + 2 >= s->cap) {
        s->cap = s->cap ? s->cap * 2 : 64;
        s->cp = realloc(s->cp, sizeof(uint32_t) * s->cap);
    }
    s->cp[s->n++] = ch;
}

static int hexval(const unsigned char* p, int n) {
    int v = 0;
    for (int i = 0; i < n; i++) {
        int ch = p[i], d;
        if (ch >= '0' && ch <= '9') d = ch - '0';
        else if (ch >= 'a' && ch <= 'f') d = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'F') d = ch - 'A' + 10;
        else return -1;
        v = v * 16 + d;
    }
    return v;
}

// Decodes DXF text: %%c %%d %%p, \U+XXXX, and (for MTEXT) formatting codes; '\n' marks a new line.
static void decode(Ctx* c, const char* in, int mtext, Str* out) {
    const unsigned char* s = (const unsigned char*) in;
    while (*s) {
        if (s[0] == '%' && s[1] == '%' && s[2]) {
            int t = s[2] | 0x20;
            if (t == 'c') { str_add(out, 0x2300); s += 3; continue; }
            if (t == 'd') { str_add(out, 0x00B0); s += 3; continue; }
            if (t == 'p') { str_add(out, 0x00B1); s += 3; continue; }
            if (t == 'u' || t == 'o' || t == 'k') { s += 3; continue; }
            if (s[2] >= '0' && s[2] <= '9') {
                int v = 0; s += 2;
                while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0');
                str_add(out, (uint32_t) v);
                continue;
            }
        }
        if (s[0] == '\\' && (s[1] == 'U' || s[1] == 'u') && s[2] == '+') {
            int v = hexval(s + 3, 4);
            if (v >= 0) { str_add(out, (uint32_t) v); s += 7; continue; }
        }
        if (mtext) {
            if (*s == '{' || *s == '}') { s++; continue; }
            if (*s == '\\' && s[1]) {
                unsigned char t = s[1];
                if (t == 'P' || t == 'X') { str_add(out, '\n'); s += 2; continue; }
                if (t == '~') { str_add(out, ' '); s += 2; continue; }
                if (t == '\\' || t == '{' || t == '}') { str_add(out, t); s += 2; continue; }
                if (t == 'S') {                         // stacked a^b, a/b, a#b → a/b; "2^" alone → 2
                    s += 2;
                    int sep = -1;
                    while (*s && *s != ';') {
                        if ((*s == '^' || *s == '#' || *s == '/') && sep < 0) { sep = out->n; s++; continue; }
                        str_add(out, next_cp(c, &s));
                    }
                    if (sep >= 0 && out->n > sep) {     // there is a bottom part: insert the slash
                        str_add(out, 0);
                        memmove(out->cp + sep + 1, out->cp + sep, sizeof(uint32_t) * (out->n - 1 - sep));
                        out->cp[sep] = '/';
                    }
                    if (*s == ';') s++;
                    continue;
                }
                if (strchr("ACcFfHhQqTtWwpa", t)) {     // codes with an argument up to ';'
                    s += 2;
                    while (*s && *s != ';') s++;
                    if (*s == ';') s++;
                    continue;
                }
                if (strchr("LlOoKkNn", t)) { s += 2; continue; }
                if (t == 'M' && s[2] == '+') { s += 3; while (*s && *s != '\\' && *s != ' ' && *s != '{' && *s != '}') s++; continue; }
            }
            if (*s == '^' && s[1]) { s += 2; str_add(out, ' '); continue; }   // ^I tab, ^J ...
        }
        str_add(out, next_cp(c, &s));
    }
}

static unsigned char* read_all(const char* path, size_t* len) {
    if (!path) return NULL;
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char* b = n > 0 ? malloc((size_t) n) : NULL;
    if (b && fread(b, 1, (size_t) n, f) != (size_t) n) { free(b); b = NULL; }
    fclose(f);
    if (b) *len = (size_t) n;
    return b;
}

static int is_geo(uint32_t ch) {
    return (ch >= 0x10A0 && ch <= 0x10FF) || (ch >= 0x2D00 && ch <= 0x2D2F) || (ch >= 0x1C90 && ch <= 0x1CBF);
}

static FPDF_FONT pick_font(Ctx* c, const uint32_t* cp, int n) {
    int geo = 0, wide = 0;
    for (int i = 0; i < n; i++) {
        uint32_t ch = cp[i];
        if ((ch >= 0x10A0 && ch <= 0x10FF) || (ch >= 0x2D00 && ch <= 0x2D2F) || (ch >= 0x1C90 && ch <= 0x1CBF)) geo = 1;
        else if (ch > 0xFF && ch != 0x2300) wide = 1;
    }
    if (geo) {
        if (!c->geoTried) {
            c->geoTried = 1;
            c->geoData = read_all(c->geoPath, &c->geoLen);
            if (c->geoData) c->geo = FPDFText_LoadFont(c->doc, c->geoData, (uint32_t) c->geoLen, FPDF_FONT_TRUETYPE, 1);
        }
        if (c->geo) return c->geo;
    }
    if (geo || wide) {
        if (!c->fontTried) {
            c->fontTried = 1;
            c->fontData = read_all(c->fontPath, &c->fontLen);
            if (c->fontData) c->font = FPDFText_LoadFont(c->doc, c->fontData, (uint32_t) c->fontLen, FPDF_FONT_TRUETYPE, 1);
        }
        if (c->font) return c->font;
    }
    if (!c->helv) c->helv = FPDFText_LoadStandardFont(c->doc, "Helvetica");
    return c->helv;
}

// One line of text. (x, y) is the anchor on the baseline in drawing units; ux,uy the
// baseline direction (its length is the width factor), vx,vy the "up" direction scaled to
// the text height. hfrac 0 / 0.5 / 1 puts the anchor at the left / middle / right end;
// fitLen > 0 stretches (fit) or scales (aligned, when keepAspect) the line to that length.
static void put_line(Ctx* c, const uint32_t* cp, int n, double x, double y,
                     double ux, double uy, double vx, double vy, double hfrac,
                     double fitLen, int keepAspect, uint32_t rgb) {
    while (n > 0 && cp[n - 1] == ' ') n--;
    if (n <= 0) return;
    double wf = hypot(ux, uy);
    if (wf < 1e-12) { ux = 1; uy = 0; wf = 1; }
    double bx = ux / wf, by = uy / wf;                 // unit baseline direction
    double hh = hypot(vx, vy);                         // text height, drawing units
    if (hh <= 0) return;
    double ax = vx / hh, ay = vy / hh;
    if (bx * ay - by * ax < 0) { ax = -ax; ay = -ay; } // never draw text mirrored
    if (c->pass == 0) {
        double w = fitLen > 0 ? fitLen : 0.55 * n * hh * wf;
        double x0 = x - bx * w * hfrac, y0 = y - by * w * hfrac;
        sample(c, x0 * c->unit, y0 * c->unit);
        sample(c, (x0 + bx * w + ax * hh) * c->unit, (y0 + by * w + ay * hh) * c->unit);
        return;
    }
    double h = hh * c->unit * c->k;                    // height in page points
    if (h < 0.02 || h > 5000) return;
    // runs of characters that need the same font (Georgian vs everything else)
    enum { MAXRUN = 32 };
    FPDF_PAGEOBJECT obj[MAXRUN];
    double wid[MAXRUN];
    int nr = 0;
    double w1 = 0;
    unsigned short* w = malloc(sizeof(unsigned short) * (2 * n + 1));
    int start = 0;
    while (start < n && nr < MAXRUN) {
        int geo = is_geo(cp[start]);
        int end = start + 1;
        while (end < n && (is_geo(cp[end]) == geo || cp[end] == ' ')) end++;
        if (nr == MAXRUN - 1) end = n;
        FPDF_FONT font = pick_font(c, cp + start, end - start);
        FPDF_PAGEOBJECT t = font ? FPDFPageObj_CreateTextObj(c->doc, font, 1.0f) : NULL;
        if (t) {
            int k = 0;
            for (int i = start; i < end; i++) {
                uint32_t ch = cp[i];
                if (ch < 0x20) ch = ' ';
                if (ch >= 0x10000) {
                    ch -= 0x10000;
                    w[k++] = (unsigned short) (0xD800 + (ch >> 10));
                    w[k++] = (unsigned short) (0xDC00 + (ch & 0x3FF));
                } else {
                    w[k++] = (unsigned short) ch;
                }
            }
            w[k] = 0;
            FPDFText_SetText(t, (FPDF_WIDESTRING) w);
            // advance width at size 1; bounds miss trailing spaces, so add them back
            float l = 0, b = 0, r = 0, tp = 0;
            double rw = FPDFPageObj_GetBounds(t, &l, &b, &r, &tp) && r > l ? r : 0.55 * (end - start);
            int sp = 0;
            for (int i = end - 1; i >= start && cp[i] == ' '; i--) sp++;
            rw += sp * 0.28;
            obj[nr] = t;
            wid[nr] = rw;
            nr++;
            w1 += rw;
        }
        start = end;
    }
    free(w);
    if (!nr) return;
    double sx = wf;                                     // horizontal stretch
    if (fitLen > 0) {
        double want = fitLen * c->unit * c->k / h;      // wanted width at size 1
        if (keepAspect) { h *= want / (w1 * sx); }      // aligned: scale the whole text
        else sx = want / w1;                            // fit: stretch only
    }
    double width = w1 * sx * h;                         // page points
    double X = c->margin + (x * c->unit - c->ox) * c->k - bx * width * hfrac;
    double Y = c->margin + (y * c->unit - c->oy) * c->k - by * width * hfrac;
    uint32_t col = paper(rgb);
    double adv = 0;
    for (int i = 0; i < nr; i++) {
        double ox = X + bx * adv * sx * h, oy = Y + by * adv * sx * h;
        FPDFPageObj_Transform(obj[i], bx * h * sx, by * h * sx, ax * h, ay * h, ox, oy);
        FPDFPageObj_SetFillColor(obj[i], col >> 16 & 255, col >> 8 & 255, col & 255, 255);
        FPDFPage_InsertObject(c->page, obj[i]);
        adv += wid[i];
    }
    c->drawn++;
}

// hAlign 0 left, 1 centre, 2 right; vAlign 0 baseline, 1 bottom, 2 middle, 3 top.
// With fit (x2,y2 given, fitMode 1 fit / 2 aligned) the line runs from (x,y) to (x2,y2).
static void put_text(Ctx* c, const Mat* m, const char* raw, int mtext, double x, double y, double h,
                     double rot, double widthFactor, int hAlign, int vAlign, uint32_t rgb,
                     int fitMode, double x2, double y2, double wrapWidth) {
    if (!raw || !*raw || h <= 0) return;
    Str s = {0};
    decode(c, raw, mtext, &s);
    if (!s.n) { free(s.cp); return; }
    if (wrapWidth > 0) {                         // MTEXT: break lines at spaces to fit the box
        int maxChars = (int) (wrapWidth / (0.52 * h));
        if (maxChars < 1) maxChars = 1;
        int lineStart = 0, lastSpace = -1;
        for (int i = 0; i < s.n; i++) {
            if (s.cp[i] == '\n') { lineStart = i + 1; lastSpace = -1; continue; }
            if (s.cp[i] == ' ') lastSpace = i;
            if (i - lineStart >= maxChars && lastSpace > lineStart) {
                s.cp[lastSpace] = '\n';
                lineStart = lastSpace + 1;
                lastSpace = -1;
            }
        }
    }
    double fitLen = 0;
    if (fitMode) {
        double dx = x2 - x, dy = y2 - y;
        if (hypot(dx, dy) > 1e-9) { rot = atan2(dy, dx); fitLen = 1; }
    }
    double cr = cos(rot), sr = sin(rot);
    int lines = 1;
    for (int i = 0; i < s.n; i++) if (s.cp[i] == '\n') lines++;
    double lineGap = 1.6 * h;
    double first;   // first baseline relative to the anchor, along "up"
    if (vAlign == 3) first = -h;
    else if (vAlign == 2) first = -h / 2 + (lines - 1) * lineGap / 2;
    else if (vAlign == 1) first = 0.15 * h + (lines - 1) * lineGap;
    else first = (lines - 1) * lineGap;
    double hfrac = hAlign == 1 ? 0.5 : hAlign == 2 ? 1 : 0;
    int start = 0, line = 0;
    for (int i = 0; i <= s.n; i++) {
        if (i < s.n && s.cp[i] != '\n') continue;
        int n = i - start;
        double dy = first - line * lineGap;
        double lx = x - sr * dy, ly = y + cr * dy;
        double X, Y, X1, Y1, X2, Y2, L = 0;
        mat_ap(m, lx, ly, &X, &Y);
        mat_ap(m, lx + cr * widthFactor, ly + sr * widthFactor, &X1, &Y1);
        mat_ap(m, lx - sr * h, ly + cr * h, &X2, &Y2);
        if (fitLen > 0) {
            double FX, FY;
            mat_ap(m, x2, y2, &FX, &FY);
            L = hypot(FX - X, FY - Y);
            hfrac = 0;
        }
        put_line(c, s.cp + start, n, X, Y, X1 - X, Y1 - Y, X2 - X, Y2 - Y, hfrac, L, fitMode == 2, rgb);
        start = i + 1;
        line++;
    }
    free(s.cp);
}

// ---------------------------------------------------------------- splines

static void spline_pts(Ctx* c, int i0, int i1, Pts* out) {
    int deg = fint(c, i0, i1, 71, 3);
    double knots[4096]; int nk = 0;
    double* cx = NULL; double* cy = NULL; double* cw = NULL; int nc = 0, capc = 0;
    double* fx = NULL; double* fy = NULL; int nf = 0, capf = 0;
    for (int i = i0; i < i1; i++) {
        int code = c->p[i].code;
        double v = atof(c->p[i].val);
        if (code == 40 && nk < 4096) knots[nk++] = v;
        else if (code == 10) {
            if (nc == capc) { capc = capc ? capc * 2 : 32; cx = realloc(cx, sizeof(double) * capc); cy = realloc(cy, sizeof(double) * capc); cw = realloc(cw, sizeof(double) * capc); }
            cx[nc] = v; cy[nc] = 0; cw[nc] = 1; nc++;
        } else if (code == 20 && nc) cy[nc - 1] = v;
        else if (code == 41 && nc) cw[nc - 1] = v > 0 ? v : 1;
        else if (code == 11) {
            if (nf == capf) { capf = capf ? capf * 2 : 32; fx = realloc(fx, sizeof(double) * capf); fy = realloc(fy, sizeof(double) * capf); }
            fx[nf] = v; fy[nf] = 0; nf++;
        } else if (code == 21 && nf) fy[nf - 1] = v;
    }
    if (nc >= 2 && deg >= 1 && nk == nc + deg + 1) {
        double t0 = knots[deg], t1 = knots[nc];
        int steps = nc * 12; if (steps > 2000) steps = 2000;
        double dx[64], dy[64], dw[64];
        if (deg < 63) {
            for (int s = 0; s <= steps; s++) {
                double t = t0 + (t1 - t0) * s / steps;
                int k = deg;
                while (k < nc - 1 && t >= knots[k + 1]) k++;
                for (int j = 0; j <= deg; j++) {
                    int ci = k - deg + j;
                    dw[j] = cw[ci]; dx[j] = cx[ci] * cw[ci]; dy[j] = cy[ci] * cw[ci];
                }
                for (int r = 1; r <= deg; r++) {
                    for (int j = deg; j >= r; j--) {
                        int ki = k - deg + j;
                        double den = knots[ki + deg - r + 1] - knots[ki];
                        double a = den > 1e-12 ? (t - knots[ki]) / den : 0;
                        dx[j] = (1 - a) * dx[j - 1] + a * dx[j];
                        dy[j] = (1 - a) * dy[j - 1] + a * dy[j];
                        dw[j] = (1 - a) * dw[j - 1] + a * dw[j];
                    }
                }
                double w = dw[deg] != 0 ? dw[deg] : 1;
                pts_add(out, dx[deg] / w, dy[deg] / w);
            }
        }
    } else if (nf >= 2) {                     // a smooth curve through the fit points
        pts_add(out, fx[0], fy[0]);
        for (int i = 0; i + 1 < nf; i++) {
            double x0 = fx[i > 0 ? i - 1 : 0], y0 = fy[i > 0 ? i - 1 : 0];
            double x3 = fx[i + 2 < nf ? i + 2 : nf - 1], y3 = fy[i + 2 < nf ? i + 2 : nf - 1];
            for (int k = 1; k <= 12; k++) {
                double t = k / 12.0, t2 = t * t, t3 = t2 * t;
                double x = 0.5 * (2 * fx[i] + (-x0 + fx[i + 1]) * t + (2 * x0 - 5 * fx[i] + 4 * fx[i + 1] - x3) * t2 + (-x0 + 3 * fx[i] - 3 * fx[i + 1] + x3) * t3);
                double y = 0.5 * (2 * fy[i] + (-y0 + fy[i + 1]) * t + (2 * y0 - 5 * fy[i] + 4 * fy[i + 1] - y3) * t2 + (-y0 + 3 * fy[i] - 3 * fy[i + 1] + y3) * t3);
                pts_add(out, x, y);
            }
        }
    } else {
        for (int i = 0; i < nc; i++) pts_add(out, cx[i], cy[i]);
    }
    free(cx); free(cy); free(cw); free(fx); free(fy);
}

// ---------------------------------------------------------------- entities

static void draw_range(Ctx* c, int from, int to, const Mat* m, uint32_t parentRgb, int depth);

static int resolve(Ctx* c, int i0, int i1, uint32_t parentRgb, int depth, uint32_t* out) {
    if (fint(c, i0, i1, 60, 0) == 1) return 0;            // invisible
    const char* ln = fstr(c, i0, i1, 8);
    Layer* L = find_layer(c, ln);
    if (L && L->hidden) return 0;
    const char* tc = fstr(c, i0, i1, 420);
    if (tc) { *out = (uint32_t) atol(tc) & 0xFFFFFF; return 1; }
    int col = fint(c, i0, i1, 62, 256);
    if (col < 0) return 0;                                  // a negative colour means "off"
    if (col == 0) { *out = parentRgb; return 1; }
    if (col == 256) {
        if (depth > 0 && (!ln || is(ln, "0"))) *out = parentRgb;
        else *out = L ? L->rgb : 0xFFFFFF;
        return 1;
    }
    *out = aci(col);
    return 1;
}

// Object coordinate system: with extrusion (0,0,-1) the x axis is mirrored.
static Mat ocs(Ctx* c, int i0, int i1, const Mat* m) {
    if (fnum(c, i0, i1, 230, 1) < 0) return mat_mul(*m, mat_sc(-1, 1));
    return *m;
}

// Solid-filled HATCH: boundary paths made of polylines (with bulges), lines, arcs and ellipses.
static void draw_hatch(Ctx* c, int i, int e, const Mat* m, uint32_t rgb) {
    int k = i;
    while (k < e && c->p[k].code != 91) k++;
    if (k >= e) return;
    if (fint(c, i, k, 70, 0) != 1) return;          // pattern hatches are not drawn
    Mat mm = ocs(c, i, e, m);
    int paths = atoi(c->p[k].val);
    k++;
    Pts pts = {0};
    int* starts = NULL; int rings = 0;
#define NEXT(gc) (k < e && c->p[k].code == (gc) ? atof(c->p[k++].val) : 0)
    for (int pth = 0; pth < paths && k < e; pth++) {
        while (k < e && c->p[k].code != 92) k++;
        if (k >= e) break;
        int flags = (int) NEXT(92);
        starts = realloc(starts, sizeof(int) * (rings + 1));
        starts[rings++] = pts.n;
        if (flags & 2) {                                   // polyline
            int hasBulge = (int) NEXT(72);
            NEXT(73);
            int nv = (int) NEXT(93);
            double px = 0, py = 0, pb = 0, fx = 0, fy = 0;
            for (int v = 0; v < nv && k < e; v++) {
                double x = NEXT(10), y = NEXT(20), b = hasBulge && k < e && c->p[k].code == 42 ? NEXT(42) : 0;
                if (v == 0) { pts_add(&pts, x, y); fx = x; fy = y; }
                else pts_bulge(&pts, px, py, x, y, pb);
                px = x; py = y; pb = b;
            }
            if (nv > 1) pts_bulge(&pts, px, py, fx, fy, pb);
        } else {                                           // edges
            int ne = (int) NEXT(93);
            for (int ed = 0; ed < ne && k < e; ed++) {
                int type = (int) NEXT(72);
                if (type == 1) {
                    double x0 = NEXT(10), y0 = NEXT(20), x1 = NEXT(11), y1 = NEXT(21);
                    if (!pts.n || pts.n == starts[rings - 1]) pts_add(&pts, x0, y0);
                    pts_add(&pts, x1, y1);
                } else if (type == 2) {
                    double cx = NEXT(10), cy = NEXT(20), r = NEXT(40), a0 = NEXT(50), a1 = NEXT(51);
                    int ccw = (int) NEXT(73);
                    double s0 = a0 * M_PI / 180, sw = (a1 - a0) * M_PI / 180;
                    while (sw <= 0) sw += 2 * M_PI;
                    if (!ccw) { s0 = -s0; sw = -sw; }           // clockwise: angles are mirrored
                    int n = arc_steps(sw);
                    for (int q = 0; q <= n; q++) { double a = s0 + sw * q / n; pts_add(&pts, cx + r * cos(a), cy + r * sin(a)); }
                } else if (type == 3) {
                    double cx = NEXT(10), cy = NEXT(20), mx = NEXT(11), my = NEXT(21), ratio = NEXT(40);
                    double a0 = NEXT(50) * M_PI / 180, a1 = NEXT(51) * M_PI / 180;
                    int ccw = (int) NEXT(73);
                    double sw = a1 - a0;
                    while (sw <= 0) sw += 2 * M_PI;
                    double nx = -my * ratio, ny = mx * ratio;
                    if (!ccw) { a0 = -a0; sw = -sw; }
                    int n = arc_steps(sw);
                    for (int q = 0; q <= n; q++) { double t = a0 + sw * q / n; pts_add(&pts, cx + mx * cos(t) + nx * sin(t), cy + my * cos(t) + ny * sin(t)); }
                } else if (type == 4) {                    // spline edge: use its control points
                    while (k < e && c->p[k].code != 10 && c->p[k].code != 72 && c->p[k].code != 97) k++;
                    while (k < e && (c->p[k].code == 10 || c->p[k].code == 20 || c->p[k].code == 42)) {
                        if (c->p[k].code == 10) { double x = NEXT(10), y = NEXT(20); pts_add(&pts, x, y); }
                        else k++;
                    }
                    while (k < e && c->p[k].code != 72 && c->p[k].code != 97) k++;
                }
            }
        }
        while (k < e && c->p[k].code != 97) k++;          // source objects of this path
        int ns = (int) NEXT(97);
        for (int q = 0; q < ns && k < e && c->p[k].code == 330; q++) k++;
    }
#undef NEXT
    for (int q = 0; q < pts.n; q++) {
        double x, y;
        mat_ap(&mm, pts.xy[2 * q], pts.xy[2 * q + 1], &x, &y);
        pts.xy[2 * q] = x;
        pts.xy[2 * q + 1] = y;
    }
    emit_fill(c, pts.xy, starts, rings, pts.n, rgb);
    free(pts.xy);
    free(starts);
}

// Draws the entity that starts at pair i; returns the index of the next entity.
static int draw_entity(Ctx* c, int i, const Mat* m, uint32_t parentRgb, int depth) {
    int e = ent_end(c, i);
    const char* type = c->p[i].val;
    if (depth == 0 && fint(c, i, e, 67, 0) == 1) {           // paper space
        if (is(type, "POLYLINE")) {
            int j = e;
            while (j < c->n && c->p[j].code == 0 && is(c->p[j].val, "VERTEX")) j = ent_end(c, j);
            if (j < c->n && c->p[j].code == 0 && is(c->p[j].val, "SEQEND")) j = ent_end(c, j);
            return j;
        }
        return e;
    }
    uint32_t rgb;
    Pts pts = {0};

    if (is(type, "LINE")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        pts_add(&pts, fnum(c, i, e, 10, 0), fnum(c, i, e, 20, 0));
        pts_add(&pts, fnum(c, i, e, 11, 0), fnum(c, i, e, 21, 0));
        pts_emit(c, &pts, m, 0, rgb);
    } else if (is(type, "CIRCLE") || is(type, "ARC")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        Mat mm = ocs(c, i, e, m);
        double r = fnum(c, i, e, 40, 0);
        if (r > 0) {
            double a0 = 0, sw = 2 * M_PI;
            if (is(type, "ARC")) {
                a0 = fnum(c, i, e, 50, 0) * M_PI / 180;
                double a1 = fnum(c, i, e, 51, 360) * M_PI / 180;
                sw = a1 - a0;
                while (sw <= 0) sw += 2 * M_PI;
            }
            pts_arc(&pts, fnum(c, i, e, 10, 0), fnum(c, i, e, 20, 0), r, a0, sw, 1);
            pts_emit(c, &pts, &mm, 0, rgb);
        }
    } else if (is(type, "ELLIPSE")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        double cx = fnum(c, i, e, 10, 0), cy = fnum(c, i, e, 20, 0);
        double mx = fnum(c, i, e, 11, 1), my = fnum(c, i, e, 21, 0), ratio = fnum(c, i, e, 40, 1);
        double nx = -my * ratio, ny = mx * ratio;
        if (fnum(c, i, e, 230, 1) < 0) { nx = -nx; ny = -ny; }
        double t0 = fnum(c, i, e, 41, 0), t1 = fnum(c, i, e, 42, 2 * M_PI), sw = t1 - t0;
        while (sw <= 0) sw += 2 * M_PI;
        int n = arc_steps(sw);
        for (int k = 0; k <= n; k++) {
            double t = t0 + sw * k / n;
            pts_add(&pts, cx + mx * cos(t) + nx * sin(t), cy + my * cos(t) + ny * sin(t));
        }
        pts_emit(c, &pts, m, 0, rgb);
    } else if (is(type, "LWPOLYLINE")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        Mat mm = ocs(c, i, e, m);
        int closed = fint(c, i, e, 70, 0) & 1;
        // vertices: 10 starts one, 20 its y, 42 its bulge
        double* vx = NULL; double* vy = NULL; double* vb = NULL; int nv = 0, cap = 0;
        for (int k = i; k < e; k++) {
            int code = c->p[k].code;
            if (code == 10) {
                if (nv == cap) { cap = cap ? cap * 2 : 16; vx = realloc(vx, sizeof(double) * cap); vy = realloc(vy, sizeof(double) * cap); vb = realloc(vb, sizeof(double) * cap); }
                vx[nv] = atof(c->p[k].val); vy[nv] = 0; vb[nv] = 0; nv++;
            } else if (code == 20 && nv) vy[nv - 1] = atof(c->p[k].val);
            else if (code == 42 && nv) vb[nv - 1] = atof(c->p[k].val);
        }
        if (nv >= 2) {
            pts_add(&pts, vx[0], vy[0]);
            for (int k = 0; k + 1 < nv; k++) pts_bulge(&pts, vx[k], vy[k], vx[k + 1], vy[k + 1], vb[k]);
            if (closed) pts_bulge(&pts, vx[nv - 1], vy[nv - 1], vx[0], vy[0], vb[nv - 1]);
            pts_emit(c, &pts, &mm, 0, rgb);
        }
        free(vx); free(vy); free(vb);
    } else if (is(type, "POLYLINE")) {
        int flags = fint(c, i, e, 70, 0);
        int ok = resolve(c, i, e, parentRgb, depth, &rgb);
        int is3d = flags & 8;
        Mat mm = is3d ? *m : ocs(c, i, e, m);
        double px = 0, py = 0, pb = 0, fx = 0, fy = 0; int nv = 0;
        int j = e;
        while (j < c->n && c->p[j].code == 0 && is(c->p[j].val, "VERTEX")) {
            int je = ent_end(c, j);
            int vf = fint(c, j, je, 70, 0);
            if (ok && !(flags & (16 | 64)) && !(vf & 16)) {
                double x = fnum(c, j, je, 10, 0), y = fnum(c, j, je, 20, 0);
                if (nv == 0) { pts_add(&pts, x, y); fx = x; fy = y; }
                else pts_bulge(&pts, px, py, x, y, pb);
                px = x; py = y; pb = fnum(c, j, je, 42, 0); nv++;
            }
            j = je;
        }
        if (ok && nv >= 2) {
            if (flags & 1) pts_bulge(&pts, px, py, fx, fy, pb);
            pts_emit(c, &pts, &mm, 0, rgb);
        }
        if (j < c->n && c->p[j].code == 0 && is(c->p[j].val, "SEQEND")) j = ent_end(c, j);
        free(pts.xy);
        return j;
    } else if (is(type, "SPLINE")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        spline_pts(c, i, e, &pts);
        pts_emit(c, &pts, m, fint(c, i, e, 70, 0) & 1, rgb);
    } else if (is(type, "SOLID") || is(type, "TRACE") || is(type, "3DFACE")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        Mat mm = is(type, "3DFACE") ? *m : ocs(c, i, e, m);
        double x0 = fnum(c, i, e, 10, 0), y0 = fnum(c, i, e, 20, 0), x1 = fnum(c, i, e, 11, 0), y1 = fnum(c, i, e, 21, 0);
        double x2 = fnum(c, i, e, 12, 0), y2 = fnum(c, i, e, 22, 0), x3 = fnum(c, i, e, 13, x2), y3 = fnum(c, i, e, 23, y2);
        pts_add(&pts, x0, y0);
        if (is(type, "3DFACE")) { pts_add(&pts, x1, y1); pts_add(&pts, x2, y2); pts_add(&pts, x3, y3); }
        else { pts_add(&pts, x1, y1); pts_add(&pts, x3, y3); pts_add(&pts, x2, y2); }
        pts_emit(c, &pts, &mm, 1, rgb);
    } else if (is(type, "LEADER")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        double x = 0;
        for (int k = i; k < e; k++) {
            if (c->p[k].code == 10) x = atof(c->p[k].val);
            else if (c->p[k].code == 20) pts_add(&pts, x, atof(c->p[k].val));
        }
        pts_emit(c, &pts, m, 0, rgb);
    } else if (is(type, "TEXT") || is(type, "ATTRIB")) {
        if (is(type, "ATTRIB") && (fint(c, i, e, 70, 0) & 1)) return e;   // hidden attribute
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        Mat mm = ocs(c, i, e, m);
        int ha = fint(c, i, e, 72, 0);
        int va = fint(c, i, e, is(type, "ATTRIB") ? 74 : 73, 0);
        double x = fnum(c, i, e, 10, 0), y = fnum(c, i, e, 20, 0);
        double x2 = fnum(c, i, e, 11, x), y2 = fnum(c, i, e, 21, y);
        int fit = ha == 5 ? 1 : ha == 3 ? 2 : 0;
        if (!fit && (ha || va)) { x = x2; y = y2; }
        int h = ha == 1 || ha == 4 ? 1 : ha == 2 ? 2 : 0;
        int v = ha == 4 ? 2 : fit ? 0 : va;
        put_text(c, &mm, fstr(c, i, e, 1), 0, x, y, fnum(c, i, e, 40, 1), fnum(c, i, e, 50, 0) * M_PI / 180,
                 fnum(c, i, e, 41, 1), h, v, rgb, fit, x2, y2, 0);
    } else if (is(type, "MTEXT")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        size_t len = 0;
        for (int k = i; k < e; k++) if (c->p[k].code == 3 || c->p[k].code == 1) len += strlen(c->p[k].val);
        char* txt = malloc(len + 1);
        txt[0] = 0;
        for (int k = i; k < e; k++) if (c->p[k].code == 3) strcat(txt, c->p[k].val);
        for (int k = i; k < e; k++) if (c->p[k].code == 1) strcat(txt, c->p[k].val);
        double rot = fnum(c, i, e, 50, 0) * M_PI / 180;
        if (fstr(c, i, e, 11)) rot = atan2(fnum(c, i, e, 21, 0), fnum(c, i, e, 11, 1));
        int a = fint(c, i, e, 71, 1);
        if (a < 1 || a > 9) a = 1;
        int col = (a - 1) % 3, row = (a - 1) / 3;
        put_text(c, m, txt, 1, fnum(c, i, e, 10, 0), fnum(c, i, e, 20, 0), fnum(c, i, e, 40, 1), rot, 1,
                 col, row == 0 ? 3 : row == 1 ? 2 : 1, rgb, 0, 0, 0, fnum(c, i, e, 41, 0));
        free(txt);
    } else if (is(type, "HATCH")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        draw_hatch(c, i, e, m, rgb);
    } else if (is(type, "INSERT") || is(type, "DIMENSION") || is(type, "ACAD_TABLE")) {
        if (!resolve(c, i, e, parentRgb, depth, &rgb)) return e;
        Block* b = find_block(c, fstr(c, i, e, 2));
        if (b && !b->busy && depth < 16) {
            b->busy = 1;
            if (is(type, "DIMENSION") || is(type, "ACAD_TABLE")) {   // already in place
                draw_range(c, b->start, b->end, m, rgb, depth + 1);
            } else {
                Mat base = ocs(c, i, e, m);
                double sx = fnum(c, i, e, 41, 1), sy = fnum(c, i, e, 42, 1);
                double rot = fnum(c, i, e, 50, 0) * M_PI / 180;
                int cols = fint(c, i, e, 70, 1), rows = fint(c, i, e, 71, 1);
                double cs = fnum(c, i, e, 44, 0), rs = fnum(c, i, e, 45, 0);
                if (cols < 1) cols = 1;
                if (rows < 1) rows = 1;
                if ((long) cols * rows > 10000) { cols = rows = 1; }
                Mat ins = mat_mul(base, mat_mul(mat_tr(fnum(c, i, e, 10, 0), fnum(c, i, e, 20, 0)), mat_rot(rot)));
                for (int r = 0; r < rows; r++) {
                    for (int k = 0; k < cols; k++) {
                        Mat t = mat_mul(ins, mat_mul(mat_tr(k * cs, r * rs), mat_mul(mat_sc(sx, sy), mat_tr(-b->bx, -b->by))));
                        draw_range(c, b->start, b->end, &t, rgb, depth + 1);
                    }
                }
            }
            b->busy = 0;
        }
    }
    free(pts.xy);
    return e;
}

static void draw_range(Ctx* c, int from, int to, const Mat* m, uint32_t parentRgb, int depth) {
    int i = from;
    while (i < to) {
        if (c->p[i].code != 0) { i++; continue; }
        int next = draw_entity(c, i, m, parentRgb, depth);
        i = next > i ? next : i + 1;
    }
}

// ---------------------------------------------------------------- reading

static int tokenize(Ctx* c, char* buf, size_t len) {
    int cap = (int) (len / 8) + 64;
    c->p = malloc(sizeof(Pair) * cap);
    if (!c->p) return 0;
    c->n = 0;
    char* s = buf;
    char* end = buf + len;
    while (s < end) {
        char* l1 = s;
        char* nl = memchr(s, '\n', (size_t) (end - s));
        if (!nl) break;
        *nl = 0;
        s = nl + 1;
        char* l2 = s;
        nl = memchr(s, '\n', (size_t) (end - s));
        if (nl) { *nl = 0; s = nl + 1; } else { s = end; }
        size_t n2 = strlen(l2);
        if (n2 && l2[n2 - 1] == '\r') l2[n2 - 1] = 0;
        char* t = trim(l1);
        if (!*t) continue;
        if (c->n == cap) {
            cap *= 2;
            Pair* np = realloc(c->p, sizeof(Pair) * cap);
            if (!np) return 0;
            c->p = np;
        }
        c->p[c->n].code = atoi(t);
        c->p[c->n].val = l2;
        // most values are compared or parsed; trim all but text strings (1, 3)
        if (c->p[c->n].code != 1 && c->p[c->n].code != 3) c->p[c->n].val = trim(l2);
        c->n++;
    }
    return c->n > 0;
}

static double units_to_mm(int u) {
    switch (u) {
        case 1: return 25.4;           // inch
        case 2: return 304.8;          // foot
        case 3: return 1609344;        // mile
        case 5: return 10;             // cm
        case 6: return 1000;           // m
        case 7: return 1e6;            // km
        case 8: return 25.4e-6;        // microinch
        case 9: return 0.0254;         // mil
        case 10: return 914.4;         // yard
        case 14: return 100;           // dm
        case 4: default: return 1;     // mm, or unitless
    }
}

static void read_structure(Ctx* c) {
    int section = 0;                   // 1 header, 2 tables, 3 blocks, 4 entities
    int capL = 0, capB = 0;
    c->entStart = c->entEnd = -1;
    for (int i = 0; i < c->n; i++) {
        Pair* p = &c->p[i];
        if (p->code == 0 && is(p->val, "SECTION") && i + 1 < c->n && c->p[i + 1].code == 2) {
            const char* name = c->p[i + 1].val;
            section = is(name, "HEADER") ? 1 : is(name, "TABLES") ? 2 : is(name, "BLOCKS") ? 3 : is(name, "ENTITIES") ? 4 : 5;
            if (section == 4) c->entStart = i + 2;
            continue;
        }
        if (p->code == 0 && is(p->val, "ENDSEC")) {
            if (section == 4) c->entEnd = i;
            section = 0;
            continue;
        }
        if (section == 1 && p->code == 9) {
            const char* v = p->val;
            if (is(v, "$INSUNITS") && i + 1 < c->n) c->unit = units_to_mm(atoi(c->p[i + 1].val));
            else if (is(v, "$ACADVER") && i + 1 < c->n) c->utf8 = strcmp(c->p[i + 1].val, "AC1021") >= 0;
            else if (is(v, "$DWGCODEPAGE") && i + 1 < c->n) c->cp1251 = strstr(c->p[i + 1].val, "1251") != NULL;
        } else if (section == 2 && p->code == 0 && is(p->val, "LAYER")) {
            int e = ent_end(c, i);
            const char* name = fstr(c, i, e, 2);
            if (!name) continue;
            if (c->nLayers == capL) { capL = capL ? capL * 2 : 64; c->layers = realloc(c->layers, sizeof(Layer) * capL); }
            Layer* L = &c->layers[c->nLayers++];
            L->name = (char*) name;
            int col = fint(c, i, e, 62, 7);
            const char* tc = fstr(c, i, e, 420);
            L->rgb = tc ? (uint32_t) atol(tc) & 0xFFFFFF : aci(col);
            L->hidden = col < 0 || (fint(c, i, e, 70, 0) & 1);   // off, or frozen
        } else if (section == 3 && p->code == 0 && is(p->val, "BLOCK")) {
            int e = ent_end(c, i);
            const char* name = fstr(c, i, e, 2);
            int j = e;
            while (j < c->n && !(c->p[j].code == 0 && is(c->p[j].val, "ENDBLK"))) j++;
            if (name) {
                if (c->nBlocks == capB) { capB = capB ? capB * 2 : 64; c->blocks = realloc(c->blocks, sizeof(Block) * capB); }
                Block* b = &c->blocks[c->nBlocks++];
                b->name = (char*) name;
                b->bx = fnum(c, i, e, 10, 0);
                b->by = fnum(c, i, e, 20, 0);
                b->start = e;
                b->end = j;
                b->busy = 0;
            }
            i = j;
        }
    }
    if (c->entStart >= 0 && c->entEnd < 0) c->entEnd = c->n;
    if (c->nLayers) qsort(c->layers, c->nLayers, sizeof(Layer), cmp_layer);
    if (c->nBlocks) qsort(c->blocks, c->nBlocks, sizeof(Block), cmp_block);
}

static int cmp_float(const void* a, const void* b) {
    float x = *(const float*) a, y = *(const float*) b;
    return x < y ? -1 : x > y;
}

// Extents without a few stray far-away objects, which would otherwise shrink the drawing.
static void robust_extents(Ctx* c, double* x0, double* y0, double* x1, double* y1) {
    *x0 = c->minx; *y0 = c->miny; *x1 = c->maxx; *y1 = c->maxy;
    if (c->ns < 50) return;
    qsort(c->sx, c->ns, sizeof(float), cmp_float);
    qsort(c->sy, c->ns, sizeof(float), cmp_float);
    int cut = c->ns / 500 > 2 ? c->ns / 500 : 2;
    int lo = cut, hi = c->ns - 1 - cut;
    double ax = c->sx[lo], bx = c->sx[hi], ay = c->sy[lo], by = c->sy[hi];
    double sx = bx - ax, sy = by - ay, s = sx > sy ? sx : sy;
    if (s <= 0) return;
    // keep everything within 30 % of the main body, then fit tightly around what is kept
    double wx0 = ax - s * 0.3, wx1 = bx + s * 0.3, wy0 = ay - s * 0.3, wy1 = by + s * 0.3;
    if (*x0 >= wx0 && *x1 <= wx1 && *y0 >= wy0 && *y1 <= wy1) return;   // nothing stray
    double kx0 = 1e300, kx1 = -1e300, ky0 = 1e300, ky1 = -1e300;
    for (int i = 0; i < c->ns; i++) {
        if (c->sx[i] >= wx0 && c->sx[i] <= wx1) { if (c->sx[i] < kx0) kx0 = c->sx[i]; if (c->sx[i] > kx1) kx1 = c->sx[i]; }
        if (c->sy[i] >= wy0 && c->sy[i] <= wy1) { if (c->sy[i] < ky0) ky0 = c->sy[i]; if (c->sy[i] > ky1) ky1 = c->sy[i]; }
    }
    if (kx1 < kx0 || ky1 < ky0) return;
    double pad = s * 0.01;
    *x0 = kx0 - pad > *x0 ? kx0 - pad : *x0;
    *x1 = kx1 + pad < *x1 ? kx1 + pad : *x1;
    *y0 = ky0 - pad > *y0 ? ky0 - pad : *y0;
    *y1 = ky1 + pad < *y1 ? ky1 + pad : *y1;
}

typedef struct { FPDF_FILEWRITE w; FILE* f; } Writer;

static int write_block(FPDF_FILEWRITE* w, const void* data, unsigned long size) {
    Writer* wr = (Writer*) w;
    return fwrite(data, 1, size, wr->f) == size;
}

double dxf_to_pdf(const char* dxfPath, const char* pdfPath, const char* fontPath, const char* georgianFontPath) {
    size_t len = 0;
    unsigned char* raw = read_all(dxfPath, &len);
    if (!raw) return -DXF_ERR_READ;
    if (len >= 18 && memcmp(raw, "AutoCAD Binary DXF", 18) == 0) { free(raw); return -DXF_ERR_BINARY; }
    char* buf = realloc(raw, len + 2);
    if (!buf) { free(raw); return -DXF_ERR_MEMORY; }
    buf[len] = '\n';
    buf[len + 1] = 0;

    Ctx* c = calloc(1, sizeof(Ctx));
    if (!c) { free(buf); return -DXF_ERR_MEMORY; }
    c->unit = 1;
    c->fontPath = fontPath;
    c->geoPath = georgianFontPath;
    double result = -DXF_ERR_NOTDXF;

    if (!tokenize(c, buf, len + 1)) goto done;
    read_structure(c);
    if (c->entStart < 0) goto done;

    // pass 1: extents
    c->sx = malloc(sizeof(float) * MAX_SAMPLES);
    c->sy = malloc(sizeof(float) * MAX_SAMPLES);
    if (!c->sx || !c->sy) { result = -DXF_ERR_MEMORY; goto done; }
    c->stride = 1;
    c->minx = c->miny = 1e300;
    c->maxx = c->maxy = -1e300;
    c->pass = 0;
    draw_range(c, c->entStart, c->entEnd, &IDENT, 0xFFFFFF, 0);
    if (c->maxx < c->minx) { result = -DXF_ERR_EMPTY; goto done; }

    double x0, y0, x1, y1;
    robust_extents(c, &x0, &y0, &x1, &y1);
    double w = x1 - x0, h = y1 - y0, span = w > h ? w : h;
    if (span <= 0) span = w = h = 1;

    // the smallest standard scale that fits the drawing on an A0 sheet
    static const double SCALES[] = {1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000,
                                    10000, 20000, 25000, 50000, 100000, 200000, 500000, 1000000};
    double ratio = SCALES[sizeof SCALES / sizeof SCALES[0] - 1];
    for (size_t i = 0; i < sizeof SCALES / sizeof SCALES[0]; i++) {
        if (span / SCALES[i] <= 1169) { ratio = SCALES[i]; break; }
    }
    c->k = 72.0 / 25.4 / ratio;
    c->margin = 10 * 72.0 / 25.4;
    c->ox = x0;
    c->oy = y0;
    double pw = w * c->k + 2 * c->margin, ph = h * c->k + 2 * c->margin;
    if (pw < 72) pw = 72;
    if (ph < 72) ph = 72;
    c->pw = pw;
    c->ph = ph;

    // pass 2: the PDF
    c->doc = FPDF_CreateNewDocument();
    if (!c->doc) { result = -DXF_ERR_MEMORY; goto done; }
    c->page = FPDFPage_New(c->doc, 0, pw, ph);
    if (!c->page) { result = -DXF_ERR_MEMORY; goto done; }
    c->pass = 1;
    draw_range(c, c->entStart, c->entEnd, &IDENT, 0xFFFFFF, 0);
    for (int i = 0; i < c->ncp; i++) flush_path(c, &c->cp[i]);
    if (!c->drawn) { result = -DXF_ERR_EMPTY; goto done; }
    if (!FPDFPage_GenerateContent(c->page)) { result = -DXF_ERR_WRITE; goto done; }

    {
        Writer wr;
        memset(&wr, 0, sizeof wr);
        wr.w.version = 1;
        wr.w.WriteBlock = write_block;
        wr.f = fopen(pdfPath, "wb");
        if (!wr.f) { result = -DXF_ERR_WRITE; goto done; }
        int ok = FPDF_SaveAsCopy(c->doc, &wr.w, 0);
        if (fclose(wr.f) != 0) ok = 0;
        if (!ok) { remove(pdfPath); result = -DXF_ERR_WRITE; goto done; }
    }
    result = ratio;

done:
    if (c->page) FPDF_ClosePage(c->page);
    if (c->helv) FPDFFont_Close(c->helv);
    if (c->font) FPDFFont_Close(c->font);
    if (c->geo) FPDFFont_Close(c->geo);
    if (c->doc) FPDF_CloseDocument(c->doc);
    free(c->fontData);
    free(c->geoData);
    free(c->sx);
    free(c->sy);
    free(c->layers);
    free(c->blocks);
    free(c->p);
    free(c);
    free(buf);
    return result;
}
