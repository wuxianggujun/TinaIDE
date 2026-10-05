package com.wuxianggujun.tinaide.editor.symbol

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSParser
import com.wuxianggujun.tinaide.core.treesitter.TreeSitterRuntime
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ProjectSymbolIndexInitializationTest {
    @Before
    fun setUp() {
        mockkObject(TreeSitterRuntime)
        mockkStatic(TSParser::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(TSParser::class)
        unmockkObject(TreeSitterRuntime)
    }

    @Test
    fun constructor_shouldInitializeCoreBeforeCreatingAnyParser() {
        val calls = mutableListOf<String>()
        val parser = mockk<TSParser>(relaxed = true)
        val language = mockk<TSLanguage>()
        val provider = mockk<LanguageSymbolProvider>()
        every { provider.supportedExtensions } returns setOf("java")
        every { provider.createLanguage() } answers {
            calls.add("grammar")
            language
        }
        every { TreeSitterRuntime.ensureInitialized() } answers {
            calls.add("core")
            Unit
        }
        every { TSParser.create() } answers {
            calls.add("parser")
            parser
        }

        val index = ProjectSymbolIndexService(providers = listOf(provider))
        try {
            assertThat(calls).containsExactly("core", "parser", "grammar").inOrder()
        } finally {
            index.close()
        }
    }

    @Test
    fun coreLoadFailure_shouldPropagateBeforeParserOrGrammarInitialization() {
        val failure = UnsatisfiedLinkError("core library unavailable")
        val provider = mockk<LanguageSymbolProvider>()
        every { TreeSitterRuntime.ensureInitialized() } throws failure

        val thrown = assertThrows(UnsatisfiedLinkError::class.java) {
            ProjectSymbolIndexService(providers = listOf(provider))
        }

        assertThat(thrown).isSameInstanceAs(failure)
        verify(exactly = 0) { TSParser.create() }
        verify(exactly = 0) { provider.createLanguage() }
    }
}
