#ifndef HAIR_RENDER_H
#define HAIR_RENDER_H

#include <stdint.h>

/// PDFium, used only to draw sheets with every line at hairline width.
/// All functions are safe to call from any thread (they share one lock).

/// Opens a PDF; returns NULL on failure.
void *hair_open(const char *path, const char *password);
void hair_close(void *doc);

/**
 * Draws the part of a page that covers the rectangle (x0, y0) - (x1, y1) of PDF user space
 * (points, y up) into an RGBA buffer of w x h pixels, so that pixel (0, 0) is the top-left
 * corner (x0, y1) and one point is `scale` pixels. Every stroked line is drawn `hairPx`
 * pixels wide. Returns 1 on success.
 */
int hair_render(void *doc, int pageIndex, uint8_t *rgba, int w, int h, int stride,
                double x0, double y0, double x1, double y1, double scale, float hairPx);

#include "dxf2pdf.h"

/**
 * Converts a DXF or (dwg = 1) DWG drawing to a PDF: the model, then each layout as a page.
 * Returns the number of pages or a negative DXF_ERR_ / DWG_ERR_ code; `out` gets each
 * page's scale and name. Uses the same lock as the hairline drawing.
 */
int calque_convert(const char *path, int dwg, const char *tmpDxf, const char *pdf,
                   const char *font, const char *georgianFont, DxfPages *out);

static inline double calque_page_ratio(const DxfPages *r, int i) { return r->ratio[i]; }
static inline const char *calque_page_name(const DxfPages *r, int i) { return r->name[i]; }

#endif
