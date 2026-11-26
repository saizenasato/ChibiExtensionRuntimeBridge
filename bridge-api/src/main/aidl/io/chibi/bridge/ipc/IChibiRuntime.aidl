// Stable runtime entry point exposed by the ChibiExtensionRuntimeBridge host APK.
// Replaces the Flutter MethodChannels ("anymeXBridge", "aniyomiExtensionBridge",
// "cloudstreamExtensionBridge", "kotatsuExtensionBridge") with a binder contract.
package io.chibi.bridge.ipc;

import io.chibi.bridge.ipc.IChibiStreamCallback;

interface IChibiRuntime {
    /** Wire protocol version; the SDK refuses to talk to a mismatched host. */
    int protocolVersion();

    /** HostInfo as JSON (see io.chibi.bridge.models.HostInfo). */
    String hostInfo();

    /** RuntimeStatus as JSON (see io.chibi.bridge.models.RuntimeStatus). */
    String status();

    /**
     * Executes one BridgeRequest (JSON) and returns a BridgeResponse (JSON).
     * Never throws across the binder; failures are encoded in the response.
     */
    String execute(String requestJson);

    /** Starts a streaming video extraction; results arrive on the callback. */
    void streamVideos(String requestJson, IChibiStreamCallback callback);

    /** Cancels an in-flight request started with the given cancellation token. */
    void cancel(String token);

    /** Releases runtime resources (equivalent to the old shutdown call). */
    void shutdown();
}
