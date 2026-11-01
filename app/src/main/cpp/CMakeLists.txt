cmake_minimum_required(VERSION 3.18.1)
project(playfair_jni C CXX)

add_library(playfair_jni SHARED
    playfair_jni.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/omg_hax.c
    playfair/playfair.c
    playfair/sap_hash.c)

# Eski C kodu: yeni clang surumlerinde hata sayilan eski tarz yapilari uyari yap.
target_compile_options(playfair_jni PRIVATE
    -w
    -Wno-implicit-function-declaration
    -Wno-int-conversion
    -Wno-incompatible-pointer-types)

# modified_md5.c sin() kullanir
target_link_libraries(playfair_jni PRIVATE m)

# ALAC (Apple Lossless) cozucu: Muzik gibi uygulamalardan gelen AirPlay sesi icin.
# Kaynak: Apple ALAC (Apache License 2.0), bkz. alac/APPLE_LICENSE.txt
add_library(alac_jni SHARED
    alac_jni.cpp
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c)

target_compile_options(alac_jni PRIVATE -w)
