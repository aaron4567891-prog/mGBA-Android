#include <mgba/flags.h>
#include <jni.h>
#include <algorithm>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <mgba/core/cheats.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/serialize.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/image.h>
#include <mgba-util/vfs.h>
}

namespace {
constexpr size_t kVideoStride = 256;
constexpr size_t kVideoHeightMax = 224;
constexpr size_t kAudioFrames = 4096;

std::string systemDir;
std::string saveDir;
std::vector<uint8_t> rom;
std::vector<mColor> nativeFrame(kVideoStride * kVideoHeightMax);
std::vector<uint32_t> argbFrame;
std::vector<int16_t> audio;
struct mCore* core = nullptr;
unsigned frameWidth = 240;
unsigned frameHeight = 160;
unsigned sampleRate = 32768;
uint32_t keyMask = 0;

std::string jstringValue(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void closeCore() {
    if (!core) return;
    core->unloadROM(core);
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    core = nullptr;
    rom.clear();
    argbFrame.clear();
    audio.clear();
    keyMask = 0;
}

void updateVideo() {
    if (!core) return;
    core->currentVideoSize(core, &frameWidth, &frameHeight);
    frameWidth = std::min<unsigned>(frameWidth, kVideoStride);
    frameHeight = std::min<unsigned>(frameHeight, kVideoHeightMax);
    argbFrame.resize(static_cast<size_t>(frameWidth) * frameHeight);
    for (unsigned y = 0; y < frameHeight; ++y) {
        for (unsigned x = 0; x < frameWidth; ++x) {
            const uint32_t color = nativeFrame[y * kVideoStride + x];
#ifdef COLOR_16_BIT
            const uint32_t r = M_R5(color) * 255 / 31;
            const uint32_t g = M_G5(color) * 255 / 31;
            const uint32_t b = M_B5(color) * 255 / 31;
#else
            const uint32_t r = color & 0xFF;
            const uint32_t g = (color >> 8) & 0xFF;
            const uint32_t b = (color >> 16) & 0xFF;
#endif
            argbFrame[y * frameWidth + x] = 0xFF000000u | (r << 16) | (g << 8) | b;
        }
    }
}

void drainAudio() {
    audio.clear();
    if (!core) return;
    struct mAudioBuffer* buffer = core->getAudioBuffer(core);
    if (!buffer) return;
    const size_t frames = std::min(mAudioBufferAvailable(buffer), kAudioFrames);
    audio.resize(frames * 2);
    const size_t read = mAudioBufferRead(buffer, audio.data(), frames);
    audio.resize(read * 2);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_initialize(JNIEnv* env, jobject, jstring system, jstring saves) {
    systemDir = jstringValue(env, system);
    saveDir = jstringValue(env, saves);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_loadRom(JNIEnv* env, jobject, jbyteArray bytes, jstring) {
    closeCore();
    const jsize size = env->GetArrayLength(bytes);
    rom.resize(size);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(rom.data()));

    struct VFile* vf = VFileFromMemory(rom.data(), rom.size());
    if (!vf) return JNI_FALSE;
    core = mCoreFindVF(vf);
    if (!core) {
        vf->close(vf);
        return JNI_FALSE;
    }

    mCoreInitConfig(core, "android");
    mCoreConfigSetDefaultValue(&core->config, "idleOptimization", "remove");
    if (!core->init(core)) {
        mCoreConfigDeinit(&core->config);
        core = nullptr;
        vf->close(vf);
        return JNI_FALSE;
    }

    std::fill(nativeFrame.begin(), nativeFrame.end(), 0);
    core->setVideoBuffer(core, nativeFrame.data(), kVideoStride);
    core->setAudioBufferSize(core, kAudioFrames);
    if (!core->loadROM(core, vf)) {
        mCoreConfigDeinit(&core->config);
        core->deinit(core);
        core = nullptr;
        vf->close(vf);
        return JNI_FALSE;
    }

    core->baseVideoSize(core, &frameWidth, &frameHeight);
    sampleRate = core->audioSampleRate(core);
    core->reset(core);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_unloadRom(JNIEnv*, jobject) { closeCore(); }

extern "C" JNIEXPORT jintArray JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_runFrame(JNIEnv* env, jobject, jint count) {
    if (!core) return nullptr;
    for (int i = 0; i < std::max(1, count); ++i) {
        core->setKeys(core, keyMask);
        core->runFrame(core);
    }
    updateVideo();
    drainAudio();
    jintArray output = env->NewIntArray(static_cast<jsize>(argbFrame.size()));
    if (!argbFrame.empty()) env->SetIntArrayRegion(output, 0, static_cast<jsize>(argbFrame.size()), reinterpret_cast<const jint*>(argbFrame.data()));
    return output;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_takeAudio(JNIEnv* env, jobject) {
    jshortArray output = env->NewShortArray(static_cast<jsize>(audio.size()));
    if (!audio.empty()) env->SetShortArrayRegion(output, 0, static_cast<jsize>(audio.size()), audio.data());
    audio.clear();
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_setButton(JNIEnv*, jobject, jint id, jboolean pressed) {
    if (id < 0 || id >= 32) return;
    const uint32_t bit = 1u << id;
    if (pressed) keyMask |= bit; else keyMask &= ~bit;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_saveState(JNIEnv* env, jobject) {
    if (!core) return nullptr;
    struct VFile* memory = VFileMemChunk(nullptr, 0);
    if (!memory || !mCoreSaveStateNamed(core, memory, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_CHEATS)) {
        if (memory) memory->close(memory);
        return nullptr;
    }
    const ssize_t size = memory->size(memory);
    std::vector<uint8_t> state(size);
    memory->seek(memory, 0, SEEK_SET);
    memory->read(memory, state.data(), state.size());
    memory->close(memory);
    jbyteArray output = env->NewByteArray(static_cast<jsize>(state.size()));
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(state.size()), reinterpret_cast<const jbyte*>(state.data()));
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_loadState(JNIEnv* env, jobject, jbyteArray bytes) {
    if (!core) return JNI_FALSE;
    const jsize size = env->GetArrayLength(bytes);
    std::vector<uint8_t> state(size);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(state.data()));
    struct VFile* memory = VFileFromConstMemory(state.data(), state.size());
    const bool ok = memory && mCoreLoadStateNamed(core, memory, SAVESTATE_RTC | SAVESTATE_CHEATS);
    if (memory) memory->close(memory);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_readSaveRam(JNIEnv* env, jobject) {
    void* data = nullptr;
    const size_t size = core ? core->savedataClone(core, &data) : 0;
    jbyteArray output = env->NewByteArray(static_cast<jsize>(size));
    if (data && size) env->SetByteArrayRegion(output, 0, static_cast<jsize>(size), static_cast<const jbyte*>(data));
    free(data);
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_writeSaveRam(JNIEnv* env, jobject, jbyteArray bytes) {
    if (!core) return JNI_FALSE;
    const jsize size = env->GetArrayLength(bytes);
    std::vector<uint8_t> data(size);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(data.data()));
    return core->savedataRestore(core, data.data(), data.size(), true) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_reset(JNIEnv*, jobject) { if (core) core->reset(core); }

extern "C" JNIEXPORT void JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_setCheat(JNIEnv* env, jobject, jint, jboolean enabled, jstring code) {
    if (!core) return;
    struct mCheatDevice* device = core->cheatDevice(core);
    if (!device) return;
    struct mCheatSet* set = device->createSet(device, "Android");
    const std::string text = jstringValue(env, code);
    if (mCheatAddLine(set, text.c_str(), 0)) {
        set->enabled = enabled;
        mCheatAddSet(device, set);
        if (set->refresh) set->refresh(set, device);
    } else {
        set->deinit(set);
        free(set);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aaron_mgbaandroid_NativeBridge_clearCheats(JNIEnv*, jobject) {
    if (core && core->cheatDevice(core)) mCheatDeviceClear(core->cheatDevice(core));
}

extern "C" JNIEXPORT jint JNICALL Java_com_aaron_mgbaandroid_NativeBridge_videoWidth(JNIEnv*, jobject) { return frameWidth; }
extern "C" JNIEXPORT jint JNICALL Java_com_aaron_mgbaandroid_NativeBridge_videoHeight(JNIEnv*, jobject) { return frameHeight; }
extern "C" JNIEXPORT jint JNICALL Java_com_aaron_mgbaandroid_NativeBridge_audioRate(JNIEnv*, jobject) { return sampleRate; }
