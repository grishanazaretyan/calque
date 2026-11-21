#ifndef CALQUE_DXF2PDF_H
#define CALQUE_DXF2PDF_H

#ifdef __cplusplus
extern "C" {
#endif

/* Error codes returned (as negative numbers) by dxf_to_pdf. */
#define DXF_ERR_READ    1   /* the file could not be read */
#define DXF_ERR_BINARY  2   /* binary DXF, not supported */
#define DXF_ERR_NOTDXF  3   /* not a DXF file */
#define DXF_ERR_EMPTY   4   /* no visible drawing in model space */
#define DXF_ERR_WRITE   5   /* the PDF could not be written */
#define DXF_ERR_MEMORY  6

/*
 * Converts the model space of an ASCII DXF file into a one-page vector PDF drawn to a
 * standard scale (1:1, 1:2, 1:5 … 1:10000), so it can be measured like any scaled drawing.
 * PDFium must already be initialised. Font paths are TrueType files used for text that
 * is not plain Latin (either may be NULL).
 * Returns the scale denominator (for example 100 for 1:100), or -DXF_ERR_x.
 */
double dxf_to_pdf(const char* dxfPath, const char* pdfPath,
                  const char* fontPath, const char* georgianFontPath);

/*
 * The same, with every layout (paper space) as a further page after the model: drawn 1:1,
 * with its viewports showing model space at their own scale and cut to their frames.
 * Returns the number of pages, or -DXF_ERR_x. out gets each page's scale and name.
 */
#define DXF_MAX_PAGES 64
typedef struct {
    int pages;
    double ratio[DXF_MAX_PAGES];        /* scale denominator; for layouts, of the largest viewport */
    char name[DXF_MAX_PAGES][64];       /* "Model", then the layout names */
} DxfPages;

int dxf_to_pdf_pages(const char* dxfPath, const char* pdfPath, const char* fontPath,
                     const char* georgianFontPath, DxfPages* out);

#ifdef __cplusplus
}
#endif
#endif
