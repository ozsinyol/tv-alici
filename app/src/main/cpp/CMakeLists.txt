cmake_minimum_required(VERSION 3.18.1)
project(playfair_jni C)

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
