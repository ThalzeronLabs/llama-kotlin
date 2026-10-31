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
}
