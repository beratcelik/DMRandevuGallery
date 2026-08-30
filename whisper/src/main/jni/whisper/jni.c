#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "DMRandevuWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// Set from Kotlin when the coroutine running the export is cancelled. whisper_full blocks for
// tens of seconds, so without this a cancelled export would keep a core busy long after the
// operator swiped the page away.
static bool g_abort = false;

static bool abort_callback(void *user_data) {
    UNUSED(user_data);
    return g_abort;
}

// Without this whisper's own warnings go to stderr and are never seen on Android. That cost real
// time: it disables alignment when flash attention is on and says so, and the message went
// nowhere, so alignment looked like it was running when it had been switched off.
static void log_callback(enum ggml_log_level level, const char *text, void *user_data) {
    UNUSED(user_data);
    int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                 : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                                 : ANDROID_LOG_INFO;
    __android_log_print(priority, TAG, "%s", text);
}

JNIEXPORT jlong JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path, jint aheads_preset, jboolean flash_attn) {
    UNUSED(thiz);
    whisper_log_set(log_callback, NULL);

    struct whisper_context_params params = whisper_context_default_params();
    // Cross-attention alignment, which has to be asked for when the model is loaded rather than
    // per call. Without it whisper's token timestamps fall back on the decoder's own timestamp
    // tokens, and the first token of each 30-second window inherits the window's start — a word
    // 680 ms in was reported at 0, which put the beep before the word instead of over it.
    if (aheads_preset > 0) {
        params.dtw_token_timestamps = true;
        params.dtw_aheads_preset = (enum whisper_alignment_heads_preset) aheads_preset;
        // Flash attention defaults on and is mutually exclusive with alignment — whisper quietly
        // turns the alignment off rather than the other way round, so asking for both gets
        // neither, and the timestamps silently stay wrong.
        params.flash_attn = false;
    } else {
        params.flash_attn = flash_attn == JNI_TRUE;
    }
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context *context = whisper_init_from_file_with_params(path, params);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (context == NULL) {
        LOGW("Failed to load model");
        return 0;
    }
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    if (context_ptr == 0) return;
    whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT void JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_setAbort(
        JNIEnv *env, jobject thiz, jboolean abort) {
    UNUSED(env);
    UNUSED(thiz);
    g_abort = abort == JNI_TRUE;
}

/**
 * Runs recognition over 16 kHz mono float samples.
 *
 * `no_timestamps` picks between the two modes the app needs, and it is not a printing option —
 * it changes what the decoder produces. With timestamps suppressed whisper transcribes swearing
 * that it otherwise replaces with an innocent near-homophone, but it answers with one long
 * segment, so it cannot say when. With `max_len = 1` it emits a segment per token, which is
 * where word timings come from, at the cost of that substitution. The app runs both and lines
 * the results up afterwards.
 *
 * Returns 0 on success, -1 if the model failed, -2 if it was aborted.
 */
JNIEXPORT jint JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jboolean no_timestamps, jint beam_size, jboolean no_context, jint max_len) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize count = (*env)->GetArrayLength(env, audio_data);

    // Beam search where the command line tool uses it. Greedy decoding produced token
    // timestamps that collapsed onto the 30-second window boundaries — a word 680 ms into the
    // window was reported at the window's start, which put the beep before the word.
    struct whisper_full_params params = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (beam_size > 1) {
        params.beam_search.beam_size = beam_size;
    }
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "tr";
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = no_context == JNI_TRUE;
    params.single_segment = false;
    params.no_timestamps = no_timestamps == JNI_TRUE;
    // max_len 1 splits a segment per token, which is where word timings come from. Zero leaves
    // whisper's own phrase segments, whose timestamps hold up where the per-token ones collapse.
    params.max_len = max_len;
    params.token_timestamps = no_timestamps != JNI_TRUE;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = NULL;

    g_abort = false;
    whisper_reset_timings(context);

    const int result = whisper_full(context, params, samples, count);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);

    if (g_abort) return -2;
    if (result != 0) {
        LOGW("whisper_full failed with %d", result);
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_segmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_segmentText(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text(
            (struct whisper_context *) context_ptr, index);
    return (*env)->NewStringUTF(env, text);
}

/** Segment start, in whisper's own hundredths of a second. */
JNIEXPORT jlong JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_segmentStart(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_get_segment_t0((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_segmentEnd(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_get_segment_t1((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jint JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_tokenCount(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_tokens((struct whisper_context *) context_ptr, segment);
}

JNIEXPORT jstring JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_tokenText(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return (*env)->NewStringUTF(env, whisper_full_get_token_text(context, segment, index));
}

/**
 * Where alignment places this token, in hundredths of a second from the start of the audio.
 *
 * Returns -1 for a token alignment did not place, and for the special tokens that carry no audio
 * of their own — the caller must not treat those as a moment in the recording.
 */
JNIEXPORT jlong JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_tokenAligned(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const whisper_token_data token = whisper_full_get_token_data(context, segment, index);
    if (token.id >= whisper_token_eot(context)) {
        return -1;
    }
    return token.t_dtw;
}

JNIEXPORT jstring JNICALL
Java_com_dmrandevu_whisper_WhisperLib_00024Companion_systemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
