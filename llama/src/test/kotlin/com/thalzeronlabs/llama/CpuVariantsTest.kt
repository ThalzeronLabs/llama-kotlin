package com.thalzeronlabs.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On arm64 Android the JNI code picks the CPU backend from a fixed list of library names. The list must cover every
 * Android variant that ggml builds, or a newer phone silently falls back to an older, slower variant.
 */
class CpuVariantsTest {

    private fun variants(text: String, pattern: Regex) = pattern.findAll(text).map { it.groupValues[1] }.toSortedSet()

    @Test
    fun `the JNI variant list matches the variants ggml builds for Android`() {
        val cmake = File("../third_party/llama.cpp/ggml/src/CMakeLists.txt")
        val jni = File("src/main/cpp/llama_jni.cpp")
        assertTrue("missing ${cmake.path}: run git submodule update --init", cmake.isFile)

        val built = variants(cmake.readText(), Regex("""ggml_add_cpu_backend_variant\((android_armv[0-9._]+)"""))
        val loaded = variants(jni.readText(), Regex(""""libggml-cpu-(android_armv[0-9._]+)\.so""""))

        assertTrue("ggml builds no Android variants? The CMake layout changed.", built.isNotEmpty())
        assertEquals(built, loaded)
    }
}
