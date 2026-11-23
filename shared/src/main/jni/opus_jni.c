/*
 * JNI binding for the bundled libopus.
 *
 * The GEELY head unit has no audio/opus MediaCodec, so CarPlay voice is decoded by libopus
 * instead of the pure-Java Concentus fallback. libopus also decodes hybrid packets at any output
 * sample rate, which Concentus 1.0.2 does not.
 *
 * Each handle is owned by one audio thread; no locking is needed.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "opus.h"

#define DECODER_MAX_FRAME_SAMPLES 5760 // 120 ms at 48 kHz, the Opus maximum

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_nativeDecoderCreate(
        JNIEnv *env, jclass clazz, jint sample_rate, jint channels) {
    (void) env;
    (void) clazz;
    if (sample_rate <= 0 || channels <= 0) return 0;

    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sample_rate, channels, &error);
    if (error != OPUS_OK || decoder == NULL) return 0;
    return (jlong) ((intptr_t) decoder);
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_nativeDecoderDecode(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray packet, jint packet_offset, jint packet_bytes,
        jshortArray pcm, jint pcm_offset, jint max_frames) {
    (void) clazz;
    OpusDecoder *decoder = (OpusDecoder *) ((intptr_t) handle);
    if (decoder == NULL) return OPUS_BAD_ARG;
    if (max_frames <= 0 || max_frames > DECODER_MAX_FRAME_SAMPLES) return OPUS_BAD_ARG;

    jbyte *input = (*env)->GetByteArrayElements(env, packet, NULL);
    if (input == NULL) return OPUS_ALLOC_FAIL;
    jshort *output = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (output == NULL) {
        (*env)->ReleaseByteArrayElements(env, packet, input, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }

    int frames = opus_decode(
            decoder,
            (const unsigned char *) input + packet_offset,
            packet_bytes,
            (opus_int16 *) output + pcm_offset,
            max_frames,
            0);

    (*env)->ReleaseShortArrayElements(env, pcm, output, 0);
    (*env)->ReleaseByteArrayElements(env, packet, input, JNI_ABORT);
    return (jint) frames;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_nativeDecoderReset(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusDecoder *decoder = (OpusDecoder *) ((intptr_t) handle);
    if (decoder == NULL) return;
    opus_decoder_ctl(decoder, OPUS_RESET_STATE);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_nativeDecoderDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusDecoder *decoder = (OpusDecoder *) ((intptr_t) handle);
    if (decoder == NULL) return;
    opus_decoder_destroy(decoder);
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_nativeEncoderCreate(
        JNIEnv *env, jclass clazz, jint sample_rate, jint channels, jint bitrate, jint complexity) {
    (void) env;
    (void) clazz;
    if (sample_rate <= 0 || channels <= 0) return 0;

    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(sample_rate, channels, OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK || encoder == NULL) return 0;

    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(encoder, OPUS_SET_VBR(1));
    opus_encoder_ctl(encoder, OPUS_SET_DTX(0));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(0));
    return (jlong) ((intptr_t) encoder);
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle,
        jshortArray pcm, jint pcm_offset, jint frame_samples,
        jbyteArray out, jint out_offset, jint max_out_bytes) {
    (void) clazz;
    OpusEncoder *encoder = (OpusEncoder *) ((intptr_t) handle);
    if (encoder == NULL) return OPUS_BAD_ARG;
    if (frame_samples <= 0 || max_out_bytes <= 0) return OPUS_BAD_ARG;

    jshort *input = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (input == NULL) return OPUS_ALLOC_FAIL;
    jbyte *output = (*env)->GetByteArrayElements(env, out, NULL);
    if (output == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, input, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }

    int bytes = opus_encode(
            encoder,
            (const opus_int16 *) input + pcm_offset,
            frame_samples,
            (unsigned char *) output + out_offset,
            max_out_bytes);

    (*env)->ReleaseByteArrayElements(env, out, output, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, input, JNI_ABORT);
    return (jint) bytes;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_nativeEncoderDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusEncoder *encoder = (OpusEncoder *) ((intptr_t) handle);
    if (encoder == NULL) return;
    opus_encoder_destroy(encoder);
}
