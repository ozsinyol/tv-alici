/*
 * JNI bridge: decrypts the FairPlay stream key (playfair).
 * The playfair/ folder was taken from the UxPlay project (GNU GPL version 3, see playfair/LICENSE.md).
 */
#include <jni.h>
#include <string.h>
#include "playfair/playfair.h"

JNIEXPORT jbyteArray JNICALL
Java_com_example_tvalici_Playfair_decrypt(JNIEnv *env, jclass clazz, jbyteArray keymsg, jbyteArray ekey)
{
    (void) clazz;
    if (keymsg == NULL || ekey == NULL) return NULL;
    if ((*env)->GetArrayLength(env, keymsg) != 164) return NULL;
    if ((*env)->GetArrayLength(env, ekey) != 72) return NULL;

    unsigned char msg[164];
    unsigned char key[72];
    unsigned char out[16];
    (*env)->GetByteArrayRegion(env, keymsg, 0, 164, (jbyte *) msg);
    (*env)->GetByteArrayRegion(env, ekey, 0, 72, (jbyte *) key);

    /* playfair expects msg[12] (0..3) as the mode; if it is invalid, uninitialized memory is read. */
    if (msg[4] != 3 || msg[12] > 3) return NULL;

    playfair_decrypt(msg, key, out);

    jbyteArray result = (*env)->NewByteArray(env, 16);
    if (result == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, 16, (const jbyte *) out);
    return result;
}
