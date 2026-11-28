#include <jni.h>

#include <stdint.h>

#include "chacha20poly1305.h"

static int valid_key_nonce(JNIEnv *env, jbyteArray key, jbyteArray nonce) {
    return (*env)->GetArrayLength(env, key) == 32 && (*env)->GetArrayLength(env, nonce) == 12;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChaCha20Poly1305_open(
    JNIEnv *env,
    jobject thiz,
    jbyteArray key,
    jbyteArray nonce,
    jbyteArray aad,
    jbyteArray input,
    jint input_offset,
    jint input_length,
    jbyteArray output,
    jint output_offset) {
    jbyte *key_bytes;
    jbyte *nonce_bytes;
    jbyte *aad_bytes;
    jbyte *input_bytes;
    jbyte *output_bytes;
    jint aad_length;
    jint result;

    (void)thiz;
    if (!valid_key_nonce(env, key, nonce)) return -2;
    if (input_offset < 0 || input_length < 16) return -2;
    if (input_offset > (*env)->GetArrayLength(env, input) - input_length) return -2;
    if (output_offset < 0 || output_offset > (*env)->GetArrayLength(env, output) - (input_length - 16)) {
        return -2;
    }
    aad_length = (*env)->GetArrayLength(env, aad);

    key_bytes = (*env)->GetByteArrayElements(env, key, NULL);
    nonce_bytes = (*env)->GetByteArrayElements(env, nonce, NULL);
    aad_bytes = (*env)->GetByteArrayElements(env, aad, NULL);
    input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
    output_bytes = (*env)->GetByteArrayElements(env, output, NULL);
    if (key_bytes == NULL || nonce_bytes == NULL || aad_bytes == NULL ||
        input_bytes == NULL || output_bytes == NULL) {
        if (key_bytes != NULL) (*env)->ReleaseByteArrayElements(env, key, key_bytes, JNI_ABORT);
        if (nonce_bytes != NULL) (*env)->ReleaseByteArrayElements(env, nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes != NULL) (*env)->ReleaseByteArrayElements(env, aad, aad_bytes, JNI_ABORT);
        if (input_bytes != NULL) (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
        if (output_bytes != NULL) (*env)->ReleaseByteArrayElements(env, output, output_bytes, JNI_ABORT);
        return -2;
    }

    result = chacha20poly1305_open(
        (const uint8_t *)key_bytes,
        (const uint8_t *)nonce_bytes,
        (const uint8_t *)aad_bytes,
        (size_t)aad_length,
        (const uint8_t *)input_bytes + input_offset,
        (size_t)input_length,
        (uint8_t *)output_bytes + output_offset);

    (*env)->ReleaseByteArrayElements(env, key, key_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, aad, aad_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, output, output_bytes, 0);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChaCha20Poly1305_seal(
    JNIEnv *env,
    jobject thiz,
    jbyteArray key,
    jbyteArray nonce,
    jbyteArray aad,
    jbyteArray plaintext,
    jint plaintext_offset,
    jint plaintext_length,
    jbyteArray output,
    jint output_offset) {
    jbyte *key_bytes;
    jbyte *nonce_bytes;
    jbyte *aad_bytes;
    jbyte *plaintext_bytes;
    jbyte *output_bytes;
    jint aad_length;
    jint result;

    (void)thiz;
    if (!valid_key_nonce(env, key, nonce)) return -2;
    if (plaintext_offset < 0 || plaintext_length < 0) return -2;
    if (plaintext_offset > (*env)->GetArrayLength(env, plaintext) - plaintext_length) return -2;
    if (output_offset < 0 || output_offset > (*env)->GetArrayLength(env, output) - (plaintext_length + 16)) {
        return -2;
    }
    aad_length = (*env)->GetArrayLength(env, aad);

    key_bytes = (*env)->GetByteArrayElements(env, key, NULL);
    nonce_bytes = (*env)->GetByteArrayElements(env, nonce, NULL);
    aad_bytes = (*env)->GetByteArrayElements(env, aad, NULL);
    plaintext_bytes = (*env)->GetByteArrayElements(env, plaintext, NULL);
    output_bytes = (*env)->GetByteArrayElements(env, output, NULL);
    if (key_bytes == NULL || nonce_bytes == NULL || aad_bytes == NULL ||
        plaintext_bytes == NULL || output_bytes == NULL) {
        if (key_bytes != NULL) (*env)->ReleaseByteArrayElements(env, key, key_bytes, JNI_ABORT);
        if (nonce_bytes != NULL) (*env)->ReleaseByteArrayElements(env, nonce, nonce_bytes, JNI_ABORT);
        if (aad_bytes != NULL) (*env)->ReleaseByteArrayElements(env, aad, aad_bytes, JNI_ABORT);
        if (plaintext_bytes != NULL) {
            (*env)->ReleaseByteArrayElements(env, plaintext, plaintext_bytes, JNI_ABORT);
        }
        if (output_bytes != NULL) (*env)->ReleaseByteArrayElements(env, output, output_bytes, JNI_ABORT);
        return -2;
    }

    result = chacha20poly1305_seal(
        (const uint8_t *)key_bytes,
        (const uint8_t *)nonce_bytes,
        (const uint8_t *)aad_bytes,
        (size_t)aad_length,
        (const uint8_t *)plaintext_bytes + plaintext_offset,
        (size_t)plaintext_length,
        (uint8_t *)output_bytes + output_offset);

    (*env)->ReleaseByteArrayElements(env, key, key_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, nonce, nonce_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, aad, aad_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, plaintext, plaintext_bytes, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, output, output_bytes, 0);
    return result;
}
