package io.chibi.bridge.sample

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.chibi.bridge.models.ItemType
import io.chibi.bridge.sdk.ChibiBridgeException
import io.chibi.bridge.sdk.ChibiRuntime
import kotlinx.coroutines.launch

/**
 * Minimal consumer of the Kotlin SDK: connects to the runtime host, prints the
 * runtime status and lists installed Aniyomi sources. CI builds this app to
 * prove the SDK AAR integrates into a real Android application.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val runtime by lazy { ChibiRuntime(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            setTextIsSelectable(true)
            text = "ChibiExtensionRuntimeBridge sample\n\nNot connected yet."
            setPadding(32, 32, 32, 32)
        }

        val connect = Button(this).apply {
            text = "Connect & list sources"
            setOnClickListener { connectAndList() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(connect)
            addView(ScrollView(this@MainActivity).apply { addView(output) })
        }
        setContentView(layout)
    }

    private fun connectAndList() = lifecycleScope.launch {
        val log = StringBuilder()
        try {
            val status = runtime.connect()
            log.appendLine("Host: ${status.host?.hostPackage} v${status.host?.hostVersionName}")
            log.appendLine("State: ${status.state}")
            log.appendLine("Backends:")
            status.backends.forEach { backend ->
                log.appendLine("  - ${backend.id}: installed=${backend.installedCount}")
            }

            runtime.initialize()
            val sources = runCatching { runtime.installedSources("aniyomi", ItemType.ANIME) }
                .getOrDefault(emptyList())
            log.appendLine("\nAniyomi sources (${sources.size}):")
            sources.take(20).forEach { log.appendLine("  - ${it.name} (${it.lang}) v${it.version}") }
        } catch (e: ChibiBridgeException) {
            log.appendLine("Bridge error ${e.error.code}: ${e.error.message}")
            if (e.error.code == io.chibi.bridge.models.BridgeError.CODE_HOST_NOT_INSTALLED) {
                log.appendLine("Install the runtime host APK from runtime-host/build/outputs/apk.")
            }
        } catch (t: Throwable) {
            log.appendLine("Unexpected: ${t.message}")
        }
        output.text = log.toString()
    }
}
