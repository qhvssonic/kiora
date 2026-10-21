#include <jni.h>
#include <atomic>
#include <mutex>
#include <sstream>
#include <streambuf>
#include <string>
#include <vector>
#include "llm/llm.hpp"

namespace {
using MNN::Transformer::Llm;
using MNN::Transformer::ChatMessages;

struct State {
    explicit State(std::string path) : configPath(std::move(path)) {}
    const std::string configPath;
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    Llm* llm = nullptr;
};

void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

std::string utf8(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool reload(State& state) {
    if (state.llm) Llm::destroy(state.llm);
    state.llm = Llm::createLLM(state.configPath);
    if (!state.llm || !state.llm->load()) {
        if (state.llm) Llm::destroy(state.llm);
        state.llm = nullptr;
        return false;
    }
    // Every call receives the full Kiora session history; a fresh context avoids stale KV state.
    state.llm->set_config(R"({"jinja":{"context":{"enable_thinking":false}}})");
    return true;
}

// MNN may split a UTF-8 scalar over several stream writes. Pass only complete bytes to Java.
size_t completeUtf8Prefix(const std::string& value) {
    size_t i = 0;
    while (i < value.size()) {
        const auto c = static_cast<unsigned char>(value[i]);
        const size_t width = c < 0x80 ? 1 : (c & 0xe0) == 0xc0 ? 2 :
            (c & 0xf0) == 0xe0 ? 3 : (c & 0xf8) == 0xf0 ? 4 : 1;
        if (i + width > value.size()) break;
        i += width;
    }
    return i;
}

class TokenBuffer final : public std::streambuf {
public:
    TokenBuffer(JNIEnv* env, jobject sink, State& state) : env_(env), sink_(sink), state_(state) {
        jclass type = env_->GetObjectClass(sink_);
        onBytes_ = env_->GetMethodID(type, "onBytes", "([B)Z");
        env_->DeleteLocalRef(type);
    }

    void flush() { emit(true); }

protected:
    std::streamsize xsputn(const char* data, std::streamsize length) override {
        pending_.append(data, static_cast<size_t>(length));
        emit(false);
        return length;
    }

    int overflow(int value) override {
        if (value != traits_type::eof()) {
            pending_.push_back(static_cast<char>(value));
            emit(false);
        }
        return value;
    }

private:
    void emit(bool last) {
        if (state_.cancelled.load() || env_->ExceptionCheck()) return;
        const size_t count = last ? pending_.size() : completeUtf8Prefix(pending_);
        if (count == 0) return;
        jbyteArray bytes = env_->NewByteArray(static_cast<jsize>(count));
        if (!bytes) { state_.cancelled.store(true); return; }
        env_->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count),
            reinterpret_cast<const jbyte*>(pending_.data()));
        const jboolean stop = env_->CallBooleanMethod(sink_, onBytes_, bytes);
        env_->DeleteLocalRef(bytes);
        if (stop || env_->ExceptionCheck()) state_.cancelled.store(true);
        pending_.erase(0, count);
    }

    JNIEnv* env_;
    jobject sink_;
    State& state_;
    jmethodID onBytes_ = nullptr;
    std::string pending_;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_kiora_model_mnn_MnnNativeBridge_load(JNIEnv* env, jobject, jstring path) {
    if (!path) { fail(env, "MNN config path is missing"); return 0; }
    auto* state = new State(utf8(env, path));
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (!reload(*state)) {
            delete state;
            fail(env, "MNN could not load the model; check config.json and its companion files");
            return 0;
        }
    }
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_kiora_model_mnn_MnnNativeBridge_generate(JNIEnv* env, jobject, jlong handle,
        jobjectArray roles, jobjectArray contents, jint maxTokens, jobject sink) {
    auto* state = reinterpret_cast<State*>(handle);
    if (!state || !roles || !contents || !sink || maxTokens <= 0) {
        fail(env, "Invalid MNN generation request"); return nullptr;
    }
    const jsize count = env->GetArrayLength(roles);
    if (count != env->GetArrayLength(contents)) {
        fail(env, "MNN history roles and contents differ"); return nullptr;
    }
    ChatMessages messages;
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        if (!role || !content) {
            fail(env, "MNN history contains null text"); return nullptr;
        }
        const auto length = env->GetArrayLength(content);
        std::string text(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(content, 0, length, reinterpret_cast<jbyte*>(text.data()));
        messages.emplace_back(utf8(env, role), std::move(text));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->llm) {
        fail(env, "MNN model is not loaded"); return nullptr;
    }
    if (state->cancelled.load()) return nullptr;
    // Kiora supplies full history each turn. Clear conversation state without reloading weights.
    state->llm->reset();
    TokenBuffer buffer(env, sink, *state);
    std::ostream stream(&buffer);
    state->llm->response(messages, &stream, nullptr, 0);
    int generated = 0;
    while (!state->cancelled.load() && !state->llm->stoped() && generated < maxTokens) {
        state->llm->generate(1);
        ++generated;
    }
    buffer.flush();
    if (env->ExceptionCheck()) return nullptr;
    const auto* context = state->llm->getContext();
    const jlong metrics[] = {context->prompt_len, context->gen_seq_len};
    jlongArray result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, metrics);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kiora_model_mnn_MnnNativeBridge_cancel(JNIEnv*, jobject, jlong handle) {
    auto* state = reinterpret_cast<State*>(handle);
    if (state) state->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kiora_model_mnn_MnnNativeBridge_clearCancel(JNIEnv*, jobject, jlong handle) {
    auto* state = reinterpret_cast<State*>(handle);
    if (state) state->cancelled.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kiora_model_mnn_MnnNativeBridge_release(JNIEnv*, jobject, jlong handle) {
    auto* state = reinterpret_cast<State*>(handle);
    if (!state) return;
    state->cancelled.store(true);
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->llm) Llm::destroy(state->llm);
    }
    delete state;
}
