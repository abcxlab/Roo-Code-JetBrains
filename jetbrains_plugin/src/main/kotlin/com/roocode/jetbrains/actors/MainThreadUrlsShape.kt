// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.actors

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * URL handling related interface
 */
interface MainThreadUrlsShape : Disposable {
    /**
     * Register URI handler
     * @param handle Handler identifier
     * @param extensionId Extension ID
     * @param extensionDisplayName Extension display name
     * @return Execution result
     */
    suspend fun registerUriHandler(handle: Int, extensionId: Map<String, String>, extensionDisplayName: String): Any

    /**
     * Unregister URI handler
     * @param handle Handler identifier
     * @return Execution result
     */
    suspend fun unregisterUriHandler(handle: Int): Any

    /**
     * Create application URI
     * @param uri URI components
     * @return Created URI components
     */
    suspend fun createAppUri(uri: Map<String, Any?>): Map<String, Any?>
    
    /**
     * Get registered handler ID for extension
     * @param extensionId ID of the extension
     * @return Handler ID if registered, null otherwise
     */
    fun getHandlerForExtension(extensionId: String): Int?
}

class MainThreadUrls : MainThreadUrlsShape {
    private val logger = Logger.getInstance(MainThreadUrls::class.java)
    private val handlers = ConcurrentHashMap<String, Int>() // extensionId -> handle

    override suspend fun registerUriHandler(handle: Int, extensionId: Map<String, String>, extensionDisplayName: String): Any {
        val id = extensionId["value"]
        if (id != null) {
            handlers[id] = handle
            logger.info("Registered URI handler for extension: $id, handle: $handle")
        }
        return CompletableDeferred<Unit>().also { it.complete(Unit) }.await()
    }

    override suspend fun unregisterUriHandler(handle: Int): Any {
        val iterator = handlers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value == handle) {
                iterator.remove()
            }
        }
        logger.debug("Unregistered URI handler: handle=$handle")
        return CompletableDeferred<Unit>().also { it.complete(Unit) }.await()
    }

    override suspend fun createAppUri(uri: Map<String, Any?>): Map<String, Any?> {
        // DEBUG: RooCode Cloud Integration
        logger.info("Creating application URI: uri=$uri")
        val originalAuthority = uri["authority"] as? String ?: ""
        val path = uri["path"] as? String ?: ""
        val query = uri["query"] as? String?
        val fragment = uri["fragment"] as? String?

        val scheme = com.roocode.jetbrains.core.ExtensionHostManager.getIdeProtocolScheme()

        val externalUri = mutableMapOf<String, Any?>(
            "scheme" to scheme,
            "authority" to originalAuthority,
            "path" to path
        )
        query?.let { externalUri["query"] = it }
        fragment?.let { externalUri["fragment"] = it }

        // DEBUG: RooCode Cloud Integration
        logger.info("Created external URI: $externalUri (original authority: $originalAuthority)")
        return externalUri
    }
    
    override fun getHandlerForExtension(extensionId: String): Int? {
        return handlers[extensionId]
    }

    override fun dispose() {
        logger.debug("Disposing MainThreadUrls resources")
        handlers.clear()
    }
}
