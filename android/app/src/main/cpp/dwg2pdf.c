// DWG → PDF for Calque, with LibreDWG (GPL-3.0-or-later): the DWG is read, written out
// as an ASCII DXF, and that DXF is drawn by dxf2pdf.c.

#include "dwg2pdf.h"

#include <stdio.h>
#include <string.h>

#include "dwg.h"
#include "dxf2pdf.h"

double dwg_to_pdf(const char* dwgPath, const char* tmpDxfPath, const char* pdfPath,
                  const char* fontPath, const char* georgianFontPath) {
    Dwg_Data dwg;
    memset(&dwg, 0, sizeof dwg);
    dwg.opts = 0;                                   // no log output
    int err = dwg_read_file(dwgPath, &dwg);
    if (err >= DWG_ERR_CRITICAL) {
        dwg_free(&dwg);
        return -DWG_ERR_READ_DWG;
    }
    err = dxf_write_file(tmpDxfPath, &dwg, 0);
    dwg_free(&dwg);
    if (err >= DWG_ERR_CRITICAL) {
        remove(tmpDxfPath);
        return -DWG_ERR_READ_DWG;
    }
    double r = dxf_to_pdf(tmpDxfPath, pdfPath, fontPath, georgianFontPath);
    remove(tmpDxfPath);
    return r;
}
