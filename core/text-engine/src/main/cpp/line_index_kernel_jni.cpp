#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

namespace {

class LineIndexKernel {
public:
    LineIndexKernel() : line_starts_{0} {}

    void Clear() {
        line_starts_.clear();
        line_starts_.push_back(0);
        append_base_ = 0;
    }

    void Rebuild(const std::u16string& text) {
        Clear();
        AppendChunk(text);
    }

    /**
     * 分片重建：调用方先 Clear()，再按文档顺序逐片 AppendChunk()。
     * 用于大文件流式加载——避免为了建索引而把整份文档拼成一个连续字符串。
     * 依赖 append_base_ 累计已消费长度，所以分片必须严格按顺序、不重不漏。
     */
    void AppendChunk(const std::u16string& chunk) {
        for (size_t i = 0; i < chunk.size(); ++i) {
            if (chunk[i] == u'\n') {
                line_starts_.push_back(static_cast<int>(append_base_ + i) + 1);
            }
        }
        append_base_ += chunk.size();
    }

    int GetLineCount() const {
        return static_cast<int>(line_starts_.size());
    }

    int GetLineStart(int line) const {
        RequireLine(line);
        return line_starts_[line];
    }

    int GetLineEnd(int line, int text_length) const {
        RequireLine(line);
        if (line + 1 < GetLineCount()) {
            return std::max(line_starts_[line + 1] - 1, line_starts_[line]);
        }
        return std::max(text_length, line_starts_[line]);
    }

    int OffsetToLine(int offset) const {
        const int safe_offset = std::max(offset, 0);
        auto it = std::upper_bound(line_starts_.begin(), line_starts_.end(), safe_offset);
        if (it == line_starts_.begin()) {
            return 0;
        }
        const int line = static_cast<int>((it - line_starts_.begin()) - 1);
        return std::clamp(line, 0, GetLineCount() - 1);
    }

    int PositionToOffset(int line, int column, int text_length) const {
        const int start = GetLineStart(line);
        const int end = GetLineEnd(line, text_length);
        return std::clamp(start + column, start, end);
    }

    void ApplyChange(int start_offset, const std::u16string& old_text, const std::u16string& new_text) {
        const int start_line = OffsetToLine(start_offset);
        const int old_newline_count = CountNewlines(old_text);
        const int delta = static_cast<int>(new_text.size()) - static_cast<int>(old_text.size());

        const int remove_from = std::min(start_line + 1, GetLineCount());
        const int remove_to = std::min(start_line + 1 + old_newline_count, GetLineCount());
        if (remove_from < remove_to) {
            line_starts_.erase(
                line_starts_.begin() + remove_from,
                line_starts_.begin() + remove_to
            );
        }

        std::vector<int> new_line_starts;
        new_line_starts.reserve(CountNewlines(new_text));
        for (size_t i = 0; i < new_text.size(); ++i) {
            if (new_text[i] == u'\n') {
                new_line_starts.push_back(start_offset + static_cast<int>(i) + 1);
            }
        }
        if (!new_line_starts.empty()) {
            line_starts_.insert(
                line_starts_.begin() + remove_from,
                new_line_starts.begin(),
                new_line_starts.end()
            );
        }

        const int shift_from = remove_from + static_cast<int>(new_line_starts.size());
        for (size_t i = static_cast<size_t>(shift_from); i < line_starts_.size(); ++i) {
            line_starts_[i] += delta;
        }

        if (line_starts_.empty()) {
            line_starts_.push_back(0);
        }
    }

private:
    void RequireLine(int line) const {
        if (line < 0 || line >= GetLineCount()) {
            throw std::out_of_range("Invalid line");
        }
    }

    static int CountNewlines(const std::u16string& text) {
        return static_cast<int>(std::count(text.begin(), text.end(), u'\n'));
    }

    std::vector<int> line_starts_;
    // AppendChunk 的累计基准偏移；Clear() 归零。
    size_t append_base_ = 0;
};

LineIndexKernel* FromHandle(jlong handle) {
    return reinterpret_cast<LineIndexKernel*>(handle);
}

std::u16string JStringToUtf16(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    std::u16string text(reinterpret_cast<const char16_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return text;
}

void ThrowIllegalArgument(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

void ThrowIllegalState(JNIEnv* env, const char* message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
}

template <typename Fn, typename ReturnT>
ReturnT WithKernel(JNIEnv* env, jlong handle, ReturnT fallback, Fn&& fn) {
    auto* kernel = FromHandle(handle);
    if (kernel == nullptr) {
        ThrowIllegalState(env, "Native line index handle is null");
        return fallback;
    }
    try {
        return fn(*kernel);
    } catch (const std::out_of_range& ex) {
        ThrowIllegalArgument(env, ex.what());
        return fallback;
    }
}

template <typename Fn>
void WithKernelVoid(JNIEnv* env, jlong handle, Fn&& fn) {
    auto* kernel = FromHandle(handle);
    if (kernel == nullptr) {
        ThrowIllegalState(env, "Native line index handle is null");
        return;
    }
    try {
        fn(*kernel);
    } catch (const std::out_of_range& ex) {
        ThrowIllegalArgument(env, ex.what());
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeCreate(
    JNIEnv*,
    jobject
) {
    return reinterpret_cast<jlong>(new LineIndexKernel());
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeClear(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    WithKernelVoid(env, handle, [](LineIndexKernel& kernel) { kernel.Clear(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeRebuild(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring text
) {
    WithKernelVoid(env, handle, [&](LineIndexKernel& kernel) {
        kernel.Rebuild(JStringToUtf16(env, text));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeAppendChunk(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring chunk
) {
    WithKernelVoid(env, handle, [&](LineIndexKernel& kernel) {
        kernel.AppendChunk(JStringToUtf16(env, chunk));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeGetLineCount(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    return WithKernel(env, handle, 1, [](LineIndexKernel& kernel) {
        return kernel.GetLineCount();
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeGetLineStart(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint line
) {
    return WithKernel(env, handle, 0, [&](LineIndexKernel& kernel) {
        return kernel.GetLineStart(line);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeGetLineEnd(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint line,
    jint textLength
) {
    return WithKernel(env, handle, 0, [&](LineIndexKernel& kernel) {
        return kernel.GetLineEnd(line, textLength);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeOffsetToLine(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint offset
) {
    return WithKernel(env, handle, 0, [&](LineIndexKernel& kernel) {
        return kernel.OffsetToLine(offset);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativePositionToOffset(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint line,
    jint column,
    jint textLength
) {
    return WithKernel(env, handle, 0, [&](LineIndexKernel& kernel) {
        return kernel.PositionToOffset(line, column, textLength);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeLineIndexKernel_nativeApplyChange(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint startOffset,
    jstring oldText,
    jstring newText
) {
    WithKernelVoid(env, handle, [&](LineIndexKernel& kernel) {
        kernel.ApplyChange(
            startOffset,
            JStringToUtf16(env, oldText),
            JStringToUtf16(env, newText)
        );
    });
}
