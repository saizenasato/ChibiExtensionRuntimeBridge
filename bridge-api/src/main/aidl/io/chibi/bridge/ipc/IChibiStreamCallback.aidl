package io.chibi.bridge.ipc;

interface IChibiStreamCallback {
    /** StreamEvent as JSON (see io.chibi.bridge.models.StreamEvent). */
    void onEvent(String eventJson);

    void onComplete();

    void onError(String errorJson);
}
