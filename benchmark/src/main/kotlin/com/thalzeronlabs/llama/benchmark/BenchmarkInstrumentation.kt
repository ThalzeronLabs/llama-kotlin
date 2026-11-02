package com.thalzeronlabs.llama.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import com.thalzeronlabs.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Entry point: `adb shell am instrument -w -e model model.gguf com.thalzeronlabs.llama.benchmark/.BenchmarkInstrumentation`.
 * Progress and the final Markdown table are printed in the terminal; the table is also saved to
 * `Android/data/com.thalzeronlabs.llama.benchmark/files/benchmark-results.md`.
 */
class BenchmarkInstrumentation : Instrumentation() {

    private var arguments: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val args = arguments.keySet().associateWith { arguments.getString(it).orEmpty() }
            val config = BenchmarkConfig.from(args)
            val report = runBenchmark(config)
            File(targetContext.getExternalFilesDir(null), "benchmark-results.md").writeText(report)
            result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$report\n")
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            Log.e(TAG, "benchmark failed", t)
            result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nBenchmark failed: ${t.message ?: t}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runBenchmark(config: BenchmarkConfig): String {
        val file = File(config.model).let { if (it.isAbsolute) it else File(targetContext.getExternalFilesDir(null), config.model) }
        require(file.isFile) {
            "No model at ${file.absolutePath}. Push one with: adb push model.gguf ${targetContext.getExternalFilesDir(null)}/"
        }
        val thermalBefore = thermalStatus()
        progress("Loading ${file.name}…")
        val runs = runBlocking {
            val model = LlamaModel.load(file)
            try {
                Benchmark(config, ::progress).run(model)
            } finally {
                model.close()
            }
        }
        val env = Environment(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            modelFile = file.name,
            modelBytes = file.length(),
            libraryVersion = BuildConfig.LIBRARY_VERSION,
            thermalBefore = thermalBefore,
            thermalAfter = thermalStatus(),
        )
        return Report.markdown(config, env, runs)
    }

    private fun progress(line: String) {
        Log.i(TAG, line)
        sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$line\n") })
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < 29) return "unknown"
        val power = targetContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private companion object {
        const val TAG = "llama-benchmark"
    }
}
