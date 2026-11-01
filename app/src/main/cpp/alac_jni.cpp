/*
 * JNI koprusu: ALAC (Apple Lossless) cozucu. alac/ klasoru Apple'in acik kaynak ALAC
 * kodudur (Apache License 2.0, bkz. alac/APPLE_LICENSE.txt). AirPlay'de Muzik gibi
 * uygulamalardan gelen ses ALAC ile sikistirilir; Android'in MediaCodec'i bunu guvenilir
 * sekilde cozmedigi icin kendi cozucumuz kullanilir.
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

/* AirPlay'in sabit ALAC ayari: 352 ornek/kare, 16 bit, 2 kanal, 44100 Hz. */
static const uint8_t kAirPlayCookie[24] = {
    0x00, 0x00, 0x01, 0x60, /* frameLength = 352 */
    0x00,                   /* compatibleVersion */
    0x10,                   /* bitDepth = 16 */
    0x28, 0x0A, 0x0E,       /* pb=40, mb=10, kb=14 */
    0x02,                   /* numChannels */
    0x00, 0xFF,             /* maxRun = 255 */
    0x00, 0x00, 0x00, 0x00, /* maxFrameBytes */
    0x00, 0x00, 0x00, 0x00, /* avgBitRate */
    0x00, 0x00, 0xAC, 0x44  /* sampleRate = 44100 */
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_tvalici_AlacNative_nativeCreate(JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    ALACDecoder *d = new ALACDecoder();
    uint8_t cookie[24];
    memcpy(cookie, kAirPlayCookie, sizeof(cookie));
    if (d->Init(cookie, sizeof(cookie)) != 0) {
        delete d;
        return 0;
    }
    return (jlong)(intptr_t) d;
}

/* Bir kareyi cozer; out 16 bit little-endian araliksiz stereo. Donus: yazilan bayt, hata -1. */
JNIEXPORT jint JNICALL
Java_com_example_tvalici_AlacNative_nativeDecode(JNIEnv *env, jclass clazz, jlong handle,
                                                  jbyteArray in, jint inLen, jbyteArray out)
{
    (void) clazz;
    ALACDecoder *d = (ALACDecoder *)(intptr_t) handle;
    if (d == NULL || in == NULL || out == NULL || inLen <= 0 || inLen > 4096) return -1;
    if (env->GetArrayLength(out) < 352 * 2 * 2) return -1;

    uint8_t inBuf[4096];
    env->GetByteArrayRegion(in, 0, inLen, (jbyte *) inBuf);

    uint8_t outBuf[352 * 2 * 2 + 64];
    memset(outBuf, 0, sizeof(outBuf));
    BitBuffer bits;
    BitBufferInit(&bits, inBuf, (uint32_t) inLen);
    uint32_t samples = 0;
    int32_t status = d->Decode(&bits, outBuf, 352, 2, &samples);
    if (status != 0 || samples == 0 || samples > 352) return -1;

    int bytes = (int) samples * 2 * 2;
    env->SetByteArrayRegion(out, 0, bytes, (const jbyte *) outBuf);
    return bytes;
}

JNIEXPORT void JNICALL
Java_com_example_tvalici_AlacNative_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    delete (ALACDecoder *)(intptr_t) handle;
}

} /* extern "C" */
