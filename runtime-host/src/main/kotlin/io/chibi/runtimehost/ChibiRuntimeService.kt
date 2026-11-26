/*
 * Binder entry point of the runtime host APK.
 *
 * This is the native equivalent of the Flutter plugin registration in
 * AnymexExtensionRuntimeBridgePlugin.onAttachedToEngine: it wires a stable
 * transport to HostBridge. The host is headless (no launcher activity) and is
 * started on demand by a client binding to this service.
 */
package io.chibi.runtimehost

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.chibi.bridge.ipc.IChibiRuntime
import io.chibi.bridge.ipc.IChibiStreamCallback
import io.chibi.bridge.models.BridgeError
import io.chibi.bridge.models.BridgeJson
import io.chibi.bridge.models.BridgeProtocol
import io.chibi.bridge.models.BridgeRequest
import io.chibi.bridge.models.BridgeResponse
import io.chibi.bridge.models.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ChibiRuntimeService : Service() {

    companion object {
        private const val TAG = "ChibiRuntimeService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Runtime host service created")
        HostBridge.initialize(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HostBridge.initialize(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Runtime host service destroyed")
        super.onDestroy()
    }

    private val binder = object : IChibiRuntime.Stub() {

        override fun protocolVersion(): Int = BridgeProtocol.VERSION

        override fun hostInfo(): String =
            BridgeJson.encodeToString(io.chibi.bridge.models.HostInfo.serializer(), HostBridge.hostInfo())

        override fun status(): String = runBlocking(Dispatchers.IO) {
            BridgeJson.encodeToString(io.chibi.bridge.models.RuntimeStatus.serializer(), HostBridge.status())
        }

        override fun execute(requestJson: String): String {
            val response = try {
                val request = BridgeJson.decodeFromString(BridgeRequest.serializer(), requestJson)
                HostBridge.initialize(applicationContext)
                runBlocking(Dispatchers.IO) { HostBridge.execute(request) }
            } catch (t: Throwable) {
                BridgeResponse.fail(
                    BridgeError(BridgeError.CODE_EXTENSION_FAILURE, t.message ?: "request decode failed", cause = t::class.java.simpleName)
                )
            }
            return BridgeJson.encodeToString(BridgeResponse.serializer(), response)
        }

        override fun streamVideos(requestJson: String, callback: IChibiStreamCallback?) {
            if (callback == null) return
            val request = try {
                BridgeJson.decodeFromString(BridgeRequest.serializer(), requestJson)
            } catch (t: Throwable) {
                callback.onError(
                    BridgeJson.encodeToString(
                        BridgeError.serializer(),
                        BridgeError(BridgeError.CODE_EXTENSION_FAILURE, t.message ?: "bad request"),
                    )
                )
                return
            }
            HostBridge.streamVideos(
                request = request,
                onEvent = { event ->
                    runCatching {
                        callback.onEvent(BridgeJson.encodeToString(StreamEvent.serializer(), event))
                    }
                },
                onComplete = { runCatching { callback.onComplete() } },
                onError = { error ->
                    runCatching { callback.onError(BridgeJson.encodeToString(BridgeError.serializer(), error)) }
                },
            )
        }

        override fun cancel(token: String) {
            HostBridge.cancel(token)
        }

        override fun shutdown() {
            HostBridge.shutdown()
        }
    }
}
