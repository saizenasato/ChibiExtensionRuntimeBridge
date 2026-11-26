package io.chibi.bridge.ipc

/** Helpers shared by host and SDK for the AIDL transport. */
object IpcConstants {
    const val ACTION_RUNTIME_HOST = "io.chibi.bridge.action.RUNTIME_HOST"
    const val EXTRA_PROTOCOL_VERSION = "protocol_version"

    /** Binder transactions are limited to ~1 MiB; keep payloads below that. */
    const val MAX_PAYLOAD_BYTES = 900_000
}
