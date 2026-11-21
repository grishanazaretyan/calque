#ifndef CALQUE_DWG2PDF_H
#define CALQUE_DWG2PDF_H

#define DWG_ERR_READ_DWG 7   /* LibreDWG could not read the DWG */

#include "dxf2pdf.h"

/* Converts a DWG to a PDF via a temporary DXF; same results as dxf_to_pdf_pages. */
int dwg_to_pdf_pages(const char* dwgPath, const char* tmpDxfPath, const char* pdfPath,
                     const char* fontPath, const char* georgianFontPath, DxfPages* out);

#endif
