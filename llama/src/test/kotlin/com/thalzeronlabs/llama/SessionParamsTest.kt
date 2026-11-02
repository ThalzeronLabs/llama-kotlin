package com.thalzeronlabs.llama

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionParamsTest {

    @Test
    fun `defaults are valid`() {
        SessionParams()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tiny context is rejected`() {
        SessionParams(contextSize = 64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `batch larger than the context is rejected`() {
        SessionParams(contextSize = 512, batchSize = 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative temperature is rejected`() {
        SessionParams(temperature = -0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `top p above one is rejected`() {
        SessionParams(topP = 1.5f)
    }

    @Test
    fun `roles map to the names chat templates expect`() {
        assertEquals("system", ChatMessage.system("x").role.wireName)
        assertEquals("user", ChatMessage.user("x").role.wireName)
        assertEquals("assistant", ChatMessage.assistant("x").role.wireName)
    }

    @Test
    fun `tokens per second is derived from the numbers`() {
        assertEquals(20.0, GenerationStats(10, 40, 2000).tokensPerSecond, 1e-9)
        assertEquals(0.0, GenerationStats(10, 40, 0).tokensPerSecond, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tiny embedding context is rejected`() {
        ModelParams(embeddingContextSize = 4)
    }

    @Test
    fun `prompt speed counts only the tokens that were processed`() {
        val stats = GenerationStats(promptTokens = 120, generatedTokens = 10, generationMillis = 500, promptMillis = 200, cachedPromptTokens = 100)
        assertEquals(100.0, stats.promptTokensPerSecond, 1e-9)
        assertEquals(0.0, GenerationStats(10, 1, 1).promptTokensPerSecond, 1e-9)
    }
}
