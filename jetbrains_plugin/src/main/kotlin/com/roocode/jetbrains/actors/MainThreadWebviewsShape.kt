// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.actors

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.roocode.jetbrains.events.EventBus
import com.roocode.jetbrains.events.ProjectEventBus
import com.roocode.jetbrains.events.WebviewHtmlUpdateData
import com.roocode.jetbrains.events.WebviewHtmlUpdateEvent
import com.roocode.jetbrains.webview.WebViewManager
import java.util.concurrent.ConcurrentHashMap
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Webview handle type
 * Corresponds to WebviewHandle type in TypeScript
 */
typealias WebviewHandle = String

/**
 * Main thread Webviews service interface
 * Corresponds to MainThreadWebviewsShape interface in VSCode
 */
interface MainThreadWebviewsShape : Disposable {
    /**
     * Set HTML content
     * Corresponds to $setHtml method in TypeScript interface
     * @param handle Webview handle
     * @param value HTML content
     */
    fun setHtml(handle: WebviewHandle, value: String)

    /**
     * Set Webview options
     * Corresponds to $setOptions method in TypeScript interface
     * @param handle Webview handle
     * @param options Webview content options
     */
    fun setOptions(handle: WebviewHandle, options: Map<String, Any?>)

    /**
     * Send message to Webview
     * Corresponds to $postMessage method in TypeScript interface
     * @param handle Webview handle
     * @param value Message content
     * @param buffers Binary buffer array
     * @return Whether operation succeeded
     */
    fun postMessage(handle: WebviewHandle, value: String): Boolean
}

/**
 * Main thread Webviews service implementation class
 */
class MainThreadWebviews(val project: Project) : MainThreadWebviewsShape {
    private val logger = Logger.getInstance(MainThreadWebviews::class.java)
    private val globalSeq = AtomicInteger(1000)

    // Store registered Webviews
    private val webviews = ConcurrentHashMap<WebviewHandle, Any?>()
    private var webviewHandle : WebviewHandle = ""

    override fun setHtml(handle: WebviewHandle, value: String) {
        logger.debug("Setting Webview HTML: handle=$handle, length=${value.length}")
        webviewHandle = handle
        try {
            // Replace vscode-file protocol format, using regex to match from vscode-file:/ to /roo-code/ part
            val modifiedHtml = value.replace(Regex("vscode-file:/.*?/roo-code/"), "/")
            logger.debug("Replaced vscode-file protocol path format")

            // Send HTML content update event through EventBus
            val data = WebviewHtmlUpdateData(handle, modifiedHtml)
//            project.getService(ProjectEventBus::class.java).emitInApplication(WebviewHtmlUpdateEvent, data)
            project.getService(WebViewManager::class.java).updateWebViewHtml(data)
            logger.debug("Sent HTML content update event: handle=$handle")
        } catch (e: Exception) {
            logger.error("Failed to set Webview HTML", e)
        }
    }

    override fun setOptions(handle: WebviewHandle, options: Map<String, Any?>) {
        logger.debug("Setting Webview options: handle=$handle, options=$options")
        webviewHandle = handle
        try {
            // Actual implementation should set options for Webview component on IDEA platform
            // Here we just log
        } catch (e: Exception) {
            logger.error("Failed to set Webview options: $e")
        }
    }

    override fun postMessage(handle: WebviewHandle, value: String): Boolean {
//        logger.debug("Sending message to Webview: handle=$handle")
        if(value.contains("theme")) {
            logger.debug("Sending theme message to Webview")
        }

        return try {
            var finalValue = value
            try {
                val jsonElement = JsonParser.parseString(value)
                if (jsonElement.isJsonObject) {
                    val jsonObject = jsonElement.asJsonObject
                    if (jsonObject.has("type") && jsonObject.get("type").asString == "state") {
                        val state = jsonObject.getAsJsonObject("state")
                        if (state != null) {
                            val newSeq = globalSeq.incrementAndGet()
                            state.addProperty("clineMessagesSeq", newSeq)
                            finalValue = jsonObject.toString()
                            logger.debug("Injected sequence number into state message: seq=$newSeq")
                        }
                    }
                }
            } catch (e: Exception) {
                // Not a JSON or other parsing error, ignore and use original value
            }

            val mangler = project.getService(WebViewManager::class.java)

//            mangler.getWebView(handle)?.postMessageToWebView(value)
            mangler.getLatestWebView()?.postMessageToWebView(finalValue)
            true
        } catch (e: Exception) {
            logger.error("Failed to send message to Webview: $e")
            false
        }
    }

    override fun dispose() {
        logger.debug("Disposing MainThreadWebviews resources")
        webviews.clear()
    }
}
