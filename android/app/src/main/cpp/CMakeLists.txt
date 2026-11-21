cmake_minimum_required(VERSION 3.22.1)
project(calque C)

add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpdfium.so)

# LibreDWG (GPL-3.0-or-later), prebuilt per ABI from https://github.com/LibreDWG/libredwg
add_library(redwg STATIC IMPORTED)
set_target_properties(redwg PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libredwg/${ANDROID_ABI}/libredwg.a)

add_library(calque SHARED calque.c dxf2pdf.c dwg2pdf.c)
target_include_directories(calque PRIVATE ${CMAKE_SOURCE_DIR}/pdfium/include ${CMAKE_SOURCE_DIR}/libredwg/include)
target_compile_options(calque PRIVATE -O2 -Wall)
# 16 KB page alignment, needed by newer Android devices and Google Play
target_link_options(calque PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(calque pdfium redwg jnigraphics log m)
target_link_options(calque PRIVATE "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
