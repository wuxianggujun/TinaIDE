package com.wuxianggujun.tinaide.core.debug

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LldbPromptDecoderTest {
    @Test fun everyPromptSplitIsConsumedOnceWithoutLeakingText() {
        val prompt = "__unique_prompt__"
        for (split in 1 until prompt.length) {
            val decoder = LldbPromptDecoder(prompt)
            val output = StringBuilder()
            var ready = 0
            decoder.accept("before" + prompt.take(split), { output.append(it) }, { ready++ })
            assertThat(ready).isEqualTo(0)
            decoder.accept(prompt.drop(split) + "after", { output.append(it) }, { ready++ })
            assertThat(ready).isEqualTo(1)
            assertThat(output.toString()).isEqualTo("beforeafter")
        }
    }
}
