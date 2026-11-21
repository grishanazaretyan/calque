#ifndef CALQUE_DWG2PDF_H
#define CALQUE_DWG2PDF_H

#define DWG_ERR_READ_DWG 7   /* LibreDWG could not read the DWG */

/* Converts a DWG to a one-page PDF via a temporary DXF; same results as dxf_to_pdf. */
double dwg_to_pdf(const char* dwgPath, const char* tmpDxfPath, const char* pdfPath,
                  const char* fontPath, const char* georgianFontPath);

#endif
