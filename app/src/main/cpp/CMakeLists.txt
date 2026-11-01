cmake_minimum_required(VERSION 3.18.1)
project(playfair_jni C CXX)

add_library(playfair_jni SHARED
    playfair_jni.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/omg_hax.c
    playfair/playfair.c
    playfair/sap_hash.c)

# Old C code: turn old-style constructs that newer clang versions treat as errors into warnings.
# -O0: this old code runs only once at the start of a connection (speed does not matter) and was verified
# with -O0 in the debug build; keep it the same in release so optimization does not expose undefined behaviour.
target_compile_options(playfair_jni PRIVATE
    -O0
    -w
    -Wno-implicit-function-declaration
    -Wno-int-conversion
    -Wno-incompatible-pointer-types)

# modified_md5.c uses sin()
target_link_libraries(playfair_jni PRIVATE m)

# ALAC (Apple Lossless) decoder: for the AirPlay audio that comes from apps such as Music.
# Source: Apple ALAC (Apple Public Source License 2.0), see alac/APPLE_LICENSE.txt
add_library(alac_jni SHARED
    alac_jni.cpp
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c)

target_compile_options(alac_jni PRIVATE -w)
