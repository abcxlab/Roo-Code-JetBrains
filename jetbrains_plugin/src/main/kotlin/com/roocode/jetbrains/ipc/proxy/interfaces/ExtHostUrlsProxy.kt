package com.roocode.jetbrains.ipc.proxy.interfaces

import com.roocode.jetbrains.ipc.proxy.LazyPromise

/**
 * Proxy interface for ExtHostUrls.
 * Corresponds to ExtHostUrlsShape in TypeScript.
 */
interface ExtHostUrlsProxy {
    /**
     * Handles an external URI.
     * @param handle The handler identifier
     * @param uri The URI components
     */
    fun handleExternalUri(handle: Int, uri: Map<String, Any?>): LazyPromise
}
