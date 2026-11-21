# Calque for Android

Fast PDF, DXF and DWG viewer for architectural drawings: real-scale measuring, markup, hairlines.

Licensed under the GNU General Public License v3 or later (see LICENSE), because DWG files are
read with [LibreDWG](https://github.com/LibreDWG/libredwg). PDF rendering uses PDFium (BSD-style).

## Building
1. Run `tools/build-libredwg.sh <ndk> <cmake/bin>` once (LibreDWG static libraries).
2. Put your signing details in `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword).
3. `gradle assembleRelease`
