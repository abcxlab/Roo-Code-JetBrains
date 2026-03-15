// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.webview

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.roocode.jetbrains.core.PluginContext
import com.roocode.jetbrains.core.ServiceProxyRegistry
import com.roocode.jetbrains.util.EnvironmentUtil
import com.roocode.jetbrains.events.WebviewHtmlUpdateData
import com.roocode.jetbrains.events.WebviewViewProviderData
import com.roocode.jetbrains.ipc.proxy.SerializableObjectWithBuffers
import com.roocode.jetbrains.theme.ThemeChangeListener
import com.roocode.jetbrains.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.pathString

/**
 * WebView creation callback interface
 */
interface WebViewCreationCallback {
    /**
     * Called when WebView is created
     * @param instance Created WebView instance
     */
    fun onWebViewCreated(instance: WebViewInstance)
}

/**
 * WebView manager, responsible for managing all WebView instances created during the plugin lifecycle
 */
@Service(Service.Level.PROJECT)
class WebViewManager(var project: Project) : Disposable, ThemeChangeListener {
    private val logger = Logger.getInstance(WebViewManager::class.java)

    // Manually managed ThemeManager instance to avoid service locator issues on plugin reload
    private var themeManagerInstance: ThemeManager? = null

    // Latest created WebView instance
    @Volatile
    private var latestWebView: WebViewInstance? = null

    // Store WebView creation callbacks
    private val creationCallbacks = mutableListOf<WebViewCreationCallback>()

    // Resource root directory path
    @Volatile
    private var resourceRootDir: Path? = null

    // Current theme configuration
    private var currentThemeConfig: JsonObject? = null

    // Current theme type
    private var isDarkTheme: Boolean = true

    // Prevent repeated dispose
    private var isDisposed = false
    private var themeInitialized = false

    /**
     * Initialize theme manager
     * @param resourceRoot Resource root directory
     */
    fun initializeThemeManager(resourceRoot: String) {
        if (isDisposed || themeInitialized) return

        logger.debug("Initializing ThemeManager manually within WebViewManager.")
        themeInitialized = true

        // Manually create and manage ThemeManager to ensure the correct classloader is used.
        val themeManager = ThemeManager(project)
        this.themeManagerInstance = themeManager

        themeManager.initialize(resourceRoot)
        // Pass `this` as the parent disposable. When this WebViewManager (a project service)
        // is disposed, it will automatically remove the listener.
        themeManager.addThemeChangeListener(this, this)
        logger.debug("ThemeManager manually created and initialized successfully.")
    }

    /**
     * Implement ThemeChangeListener interface, handle theme change events
     */
    override fun onThemeChanged(themeConfig: JsonObject, isDarkTheme: Boolean) {
        logger.debug("Received theme change event, isDarkTheme: $isDarkTheme, config: ${themeConfig.size()}")
        this.currentThemeConfig = themeConfig
        this.isDarkTheme = isDarkTheme

        // Send theme config to all WebView instances
        sendThemeConfigToWebViews(themeConfig)
    }

    /**
     * Send theme config to all WebView instances
     */
    private fun sendThemeConfigToWebViews(themeConfig: JsonObject) {
        logger.debug("Send theme config to WebView")

//        getAllWebViews().forEach { webView ->
            try {
                getLatestWebView()?.sendThemeConfigToWebView(themeConfig)
            } catch (e: Exception) {
                logger.error("Failed to send theme config to WebView", e)
            }
//        }
    }

    /**
     * Save HTML content to resource directory
     * @param html HTML content
     * @param filename File name
     * @return Saved file path
     */
    private fun saveHtmlToResourceDir(html: String, filename: String): Path? {
        if( resourceRootDir == null || !resourceRootDir!!.exists() ) {
            logger.warn("Resource root directory does not exist, cannot save HTML content")
            throw IOException("Resource root directory does not exist")
        }

        val filePath = resourceRootDir?.resolve(filename)

        try {
            if (filePath != null) {
                logger.debug("HTML content saved to: $filePath")
                Files.write(filePath, html.toByteArray(StandardCharsets.UTF_8))
                return filePath
            }
            return null
        } catch (e: Exception) {
            logger.error("Failed to save HTML content: $filePath", e)
            throw e
        }
    }

    /**
     * Register WebView creation callback
     * @param callback Callback object
     * @param disposable Associated Disposable object, used for automatic callback removal
     */
    fun addCreationCallback(callback: WebViewCreationCallback, disposable: Disposable? = null) {
        synchronized(creationCallbacks) {
            creationCallbacks.add(callback)

            // If Disposable is provided, automatically remove callback when disposed
            if (disposable != null) {
                Disposer.register(disposable, Disposable {
                    removeCreationCallback(callback)
                })
            }
        }

        // If there is already a latest created WebView, notify immediately
        latestWebView?.let { webview ->
            ApplicationManager.getApplication().invokeLater {
                callback.onWebViewCreated(webview)
            }
        }
    }

    /**
     * Remove WebView creation callback
     * @param callback Callback object to remove
     */
    fun removeCreationCallback(callback: WebViewCreationCallback) {
        synchronized(creationCallbacks) {
            creationCallbacks.remove(callback)
        }
    }

    /**
     * Notify all callbacks that WebView has been created
     * @param instance Created WebView instance
     */
    private fun notifyWebViewCreated(instance: WebViewInstance) {
        val callbacks = synchronized(creationCallbacks) {
            creationCallbacks.toList() // Create a copy to avoid concurrent modification
        }

        // Safely call callbacks in UI thread
        ApplicationManager.getApplication().invokeLater {
            callbacks.forEach { callback ->
                try {
                    callback.onWebViewCreated(instance)
                } catch (e: Exception) {
                    logger.error("Exception occurred when calling WebView creation callback", e)
                }
            }
        }
    }

    /**
     * Register WebView provider and create WebView instance
     */
    fun registerProvider(data: WebviewViewProviderData) {
        logger.debug("Register WebView provider and create WebView instance request received: ${data.viewType}")

        // Use global queue to serialize WebView creation to avoid JCEF concurrency issues
        val queue = ApplicationManager.getApplication().getService(WebViewCreationQueue::class.java)
        queue.queueCreation(project) {
            logger.debug("Processing WebView creation for: ${data.viewType}")
            val extension = data.extension

            // Get location info from extension and set resource root directory
            try {
                @Suppress("UNCHECKED_CAST")
                val location = extension?.get("location") as? Map<String, Any?>
                val fsPath = location?.get("fsPath") as? String

                if (fsPath != null) {
                    // Set resource root directory
                    val path = Paths.get(fsPath)
                    logger.debug("Get resource directory path from extension: $path")

                    // Ensure the resource directory exists
                    if (!path.exists()) {
                        path.createDirectories()
                    }

                    // Update resource root directory
                    resourceRootDir = path

                    // Initialize theme manager
                    initializeThemeManager(fsPath)

                }
            } catch (e: Exception) {
                logger.error("Failed to get resource directory from extension", e)
            }

            val protocol = project.getService(PluginContext::class.java).getRPCProtocol()
            if (protocol == null) {
                logger.error("Cannot get RPC protocol instance, cannot register WebView provider: ${data.viewType}")
                return@queueCreation
            }
            // When registration event is notified, create a new WebView instance
            val viewId = UUID.randomUUID().toString()

            val title = data.options["title"] as? String ?: data.viewType
            val state = data.options["state"] as? Map<String, Any?> ?: emptyMap()

            // CRITICAL: This is where JBCefBrowser is created. Must be serialized.
            val webview = WebViewInstance(data.viewType, viewId, title, state, project, data.extension)

            val proxy = protocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostWebviewViews)
            proxy.resolveWebviewView(viewId, data.viewType, title, state, null)


            // Set as the latest created WebView
            latestWebView = webview

            logger.debug("Create WebView instance: viewType=${data.viewType}, viewId=$viewId")

            // Notify callback
            notifyWebViewCreated(webview)
        }
    }

    /**
         * Get the latest created WebView instance
         */
    fun getLatestWebView(): WebViewInstance? {
        return latestWebView
    }

    /**
         * Update the HTML content of the WebView
         * @param data HTML update data
         */
    fun updateWebViewHtml(data: WebviewHtmlUpdateData) {
        val encodedState = getLatestWebView()?.state.toString().replace("\"", "\\\"")
        val mRst = """<script\s+nonce="([A-Za-z0-9]{32})">""".toRegex().find(data.htmlContent)
        val str = mRst?.value ?: ""

        // Inject VSCode API mock directly into HTML, but leave sendMessageToPlugin to be injected dynamically
        data.htmlContent = data.htmlContent.replace(str,"""
                        ${str}
                        // Placeholder for sendMessageToPlugin, will be injected dynamically
                        // Initialize a buffer to store messages sent before the bridge is ready
                        window.messageBuffer = [];
                        window.sendMessageToPlugin = function(message) {
                            console.info("Bridge not ready, buffering message:", message);
                            window.messageBuffer.push(message);
                        };
                        
                        // Inject VSCode API mock
                        globalThis.acquireVsCodeApi = (function() {
                            let acquired = false;
                        
                            let state = JSON.parse('${encodedState}');
                        
                            if (typeof window !== "undefined" && !window.receiveMessageFromPlugin) {
                                console.log("VSCodeAPIWrapper: Setting up receiveMessageFromPlugin for IDEA plugin compatibility");
                                window.receiveMessageFromPlugin = (message) => {
                                    // console.log("receiveMessageFromPlugin received message:", JSON.stringify(message));
                                    // Create a new MessageEvent and dispatch it to maintain compatibility with existing code
                                    const event = new MessageEvent("message", {
                                        data: message,
                                    });
                                    window.dispatchEvent(event);
                                };
                            }
                        
                            return () => {
                                if (acquired) {
                                    throw new Error('An instance of the VS Code API has already been acquired');
                                }
                                acquired = true;
                                return Object.freeze({
                                    postMessage: function(message, transfer) {
                                        // console.log("postMessage: ", message);
                                        window.sendMessageToPlugin(message);
                                    },
                                    setState: function(newState) {
                                        state = newState;
                                        window.sendMessageToPlugin(newState);
                                        return newState;
                                    },
                                    getState: function() {
                                        return state;
                                    }
                                });
                            };
                        })();
                        
                        // Clean up references to window parent for security
                        delete window.parent;
                        delete window.top;
                        delete window.frameElement;
                        
                        console.log("VSCode API mock injected");
                        """)



        logger.debug("Received HTML update event: handle=${data.handle}, html length: ${data.htmlContent.length}")

        // Remove preload links for .map, .sourcemap, .json map files to avoid performance warnings
        // Use a robust regex to match <link> tags with href pointing to map files, including source-map query params
        val mapLinkRegex = Regex("""<link\s+[^>]*href=["'][^"']*(?:\.map(?=["'?])|\.sourcemap|\.map\.json|source-map=)[^"']*["'][^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        data.htmlContent = data.htmlContent.replace(mapLinkRegex, "")

        val webView = getLatestWebView()

        if (webView != null) {
            try {
                // If HTTP server is running
                if ( resourceRootDir != null) {
                    // Generate unique file name for WebView
                    val filename = "index.html"

                    // Save HTML content to file
                    saveHtmlToResourceDir(data.htmlContent, filename)

                    // Use HTTP URL to load WebView content
                    val url = "http://localhost:12345/$filename"
                    logger.debug("Load WebView HTML content via HTTP: $url")

                    webView.loadUrl(url)
                } else {
                    // Fallback to direct HTML loading
                    logger.warn("HTTP server not running or resource directory not set, loading HTML content directly")
                    webView.loadHtml(data.htmlContent)
                }

                    logger.debug("WebView HTML content updated: handle=${data.handle}")

                // If there is already a theme config, send it immediately (synchronous)
                currentThemeConfig?.let { themeConfig ->
                    try {
                        webView.sendThemeConfigToWebView(themeConfig)
                    } catch (e: Exception) {
                        logger.error("Failed to send theme config to WebView", e)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to update WebView HTML content", e)
                // Fallback to direct HTML loading
                webView.loadHtml(data.htmlContent)
            }
        } else {
            logger.warn("WebView instance not found: handle=${data.handle}")
        }
    }


    override fun dispose() {
        if (isDisposed) {
            logger.debug("WebViewManager has already been disposed, ignoring repeated call")
            return
        }
        isDisposed = true

        logger.debug("Releasing WebViewManager resources...")

        // Dispose the manually managed ThemeManager instance
        try {
            themeManagerInstance?.dispose()
            themeManagerInstance = null
            logger.debug("Manually managed ThemeManager instance disposed.")
        } catch (e: Exception) {
            logger.error("Failed to dispose manually managed ThemeManager instance", e)
        }

        // Clean up resource directory
        try {
            // Only delete index.html file, keep other files
            resourceRootDir?.let {
                val indexFile = it.resolve("index.html").toFile()
                if (indexFile.exists() && indexFile.isFile) {
                    val deleted = indexFile.delete()
                    if (deleted) {
                        logger.debug("index.html file deleted")
                    } else {
                        logger.warn("Failed to delete index.html file")
                    }
                } else {
                    logger.debug("index.html file does not exist, no need to clean up")
                }
            }
            resourceRootDir = null
        } catch (e: Exception) {
            logger.error("Failed to clean up index.html file", e)
        }

        try {
            latestWebView?.dispose()
        } catch (e: Exception) {
            logger.error("Failed to release WebView resources", e)
        }

        // Reset theme data
        currentThemeConfig = null

        // Clear callback list
        synchronized(creationCallbacks) {
            creationCallbacks.clear()
        }

        logger.debug("WebViewManager released")
    }


}

/**
 * WebView instance class, encapsulates JCEF browser
 */
class WebViewInstance(
    val viewType: String,
    val viewId: String,
    val title: String,
    val state: Map<String, Any?>,
    val project: Project,
    val extension: Map<String, Any?>
) : Disposable {
    private val logger = Logger.getInstance(WebViewInstance::class.java)

    // JCEF browser instance
    val browser: JBCefBrowser

    // Rendering mode state
    val isOSR: Boolean

    // WebView state
    private var isDisposed = false

    // JavaScript query handler for communication with webview
    var jsQuery: JBCefJSQuery? = null

    // JSON serialization
    private val gson = Gson()

    // Coroutine scope
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var isPageLoaded = false

    private var currentThemeConfig: JsonObject? = null

    // Callback for page load completion
    private var pageLoadCallback: (() -> Unit)? = null
    private val jsBridgeSetupRetries = AtomicInteger(0)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    init {
        // Determine rendering mode based on environment and JVM properties
        val manualMode = System.getProperty("roo.webview.mode")
        isOSR = when (manualMode) {
            "osr" -> {
                logger.info("Initializing WebView in OSR mode (Forced by JVM property)")
                true
            }
            "windowed" -> {
                logger.info("Initializing WebView in Windowed mode (Forced by JVM property)")
                false
            }
            else -> {
                val isVM = EnvironmentUtil.isVirtualMachine
                if (isVM) {
                    logger.info("Initializing WebView in OSR mode (Virtual Machine detected)")
                } else {
                    logger.info("Initializing WebView in Windowed mode (Physical Machine detected)")
                }
                isVM
            }
        }

        // Create JBCefBrowser instance
        browser = JBCefBrowser.createBuilder()
            .setOffScreenRendering(isOSR)
            .build()
        
        logger.debug("WebViewInstance created: viewId=$viewId, isOSR=$isOSR, browser.isOffScreenRendering=${browser.isOffScreenRendering}")

        // Set the background color immediately after creation to prevent white flash
        browser.component.background = UIUtil.getPanelBackground()

        // Defer JS bridge setup to ensure JCEF is fully initialized
        // This fixes a potential NullPointerException in JBCefJSQuery.create
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed) {
                setupJSBridge()
            }
        }
        
        // Enable resource loading interception
        enableResourceInterception(extension)
    }

    /**
     * Send theme config to the specified WebView instance
     */
    fun sendThemeConfigToWebView(themeConfig: JsonObject) {
        currentThemeConfig = themeConfig
        if(isDisposed or !isPageLoaded) {
            logger.warn("WebView has been disposed or not loaded, cannot send theme config:${isDisposed},${isPageLoaded}")
            return
        }
        injectTheme()
    }

    /**
     * Check if page is loaded
     * @return true if page is loaded, false otherwise
     */
    fun isPageLoaded(): Boolean {
        return isPageLoaded
    }

    /**
     * Set callback for page load completion
     * @param callback Callback function to be called when page is loaded
     */
    fun setPageLoadCallback(callback: (() -> Unit)?) {
        pageLoadCallback = callback
    }

    fun syncZoomLevel() {
        // Get the current UI scale factor from the IDE
        // We need to divide by sysScale to avoid double scaling, as CEF handles system DPI automatically in Windowed mode.
        val scaleFactor = JBUI.pixScale(1.0f) / JBUIScale.sysScale()

        // Convert the linear scale factor to CEF's logarithmic zoomLevel.
        // The base is approximately 1.2, as used by Chromium.
        val zoomLevel = Math.log(scaleFactor.toDouble()) / Math.log(1.2)

        // Apply the zoom only if it has changed to avoid unnecessary updates
        if (browser.cefBrowser.zoomLevel != zoomLevel) {
            logger.info("Syncing IDE zoom. UI Scale: ${JBUI.pixScale(1.0f)}, Sys Scale: ${JBUIScale.sysScale()}, Final Factor: $scaleFactor, CEF ZoomLevel: $zoomLevel")
            browser.cefBrowser.zoomLevel = zoomLevel
        }
    }

    private fun injectTheme() {
        if(currentThemeConfig == null) {
            return
        }
        try {
            var cssContent: String? = null

            // Get cssContent from themeConfig and save, then remove from object
            if (currentThemeConfig!!.has("cssContent")) {
                cssContent = currentThemeConfig!!.get("cssContent").asString
                // Create a copy of themeConfig to modify without affecting the original object
                val themeConfigCopy = currentThemeConfig!!.deepCopy()
                // Remove cssContent property from the copy
                themeConfigCopy.remove("cssContent")

                // Inject CSS variables into WebView
                if (cssContent != null) {
                    val injectThemeScript = """
                        (function() {
                            let injected = false;
                            function injectCSSVariables() {
                                if (injected) return;
                                if(document.documentElement && document.head) {
                                    injected = true;
                                    // Convert cssContent to style attribute of html tag
                                    try {
                                        // Extract CSS variables (format: --name:value;)
                                        const cssLines = `$cssContent`.split('\n');
                                        const cssVariables = [];
                                        
                                        // Process each line, extract CSS variable declarations
                                        for (const line of cssLines) {
                                            const trimmedLine = line.trim();
                                            // Skip comments and empty lines
                                            if (trimmedLine.startsWith('/*') || trimmedLine.startsWith('*') || trimmedLine.startsWith('*/') || trimmedLine === '') {
                                                continue;
                                            }
                                            // Extract CSS variable part
                                            if (trimmedLine.startsWith('--')) {
                                                cssVariables.push(trimmedLine);
                                            }
                                        }
                                        
                                        // Merge extracted CSS variables into style attribute string
                                        const styleAttrValue = cssVariables.join(' ');
                                        
                                        // Set as style attribute of html tag
                                        document.documentElement.setAttribute('style', styleAttrValue);
                                        console.log("CSS variables set as style attribute of HTML tag");
                                    } catch (error) {
                                        console.error("Error processing CSS variables:", error);
                                    }
                                    
                                    // Keep original default style injection logic
                                    // Inject default theme style into head, use id="_defaultStyles"
                                    let defaultStylesElement = document.getElementById('_defaultStyles');
                                    if (!defaultStylesElement) {
                                        defaultStylesElement = document.createElement('style');
                                        defaultStylesElement.id = '_defaultStyles';
                                        document.head.appendChild(defaultStylesElement);
                                    }
                                    
                                    // Add default_themes.css content
                                    defaultStylesElement.textContent = `
                                        html {
                                            scrollbar-color: var(--vscode-scrollbarSlider-background) var(--vscode-editor-background);
                                        }
                                        
                                        body {
                                            overscroll-behavior-x: none;
                                            background-color: var(--vscode-sideBar-background);
                                            color: var(--vscode-editor-foreground);
                                            font-family: var(--vscode-font-family);
                                            font-weight: var(--vscode-font-weight);
                                            font-size: var(--vscode-font-size);
                                            margin: 0;
                                            padding: 0 20px;
                                        }
                                        
                                        img, video {
                                            max-width: 100%;
                                            max-height: 100%;
                                        }
                                        
                                        a, a code {
                                            color: var(--vscode-textLink-foreground);
                                        }
                                        
                                        p > a {
                                            text-decoration: var(--text-link-decoration);
                                        }
                                        
                                        a:hover {
                                            color: var(--vscode-textLink-activeForeground);
                                        }
                                        
                                        a:focus,
                                        input:focus,
                                        select:focus,
                                        textarea:focus {
                                            outline: 1px solid -webkit-focus-ring-color;
                                            outline-offset: -1px;
                                        }
                                        
                                        code {
                                            font-family: var(--monaco-monospace-font);
                                            color: var(--vscode-textPreformat-foreground);
                                            background-color: var(--vscode-textPreformat-background);
                                            padding: 1px 3px;
                                            border-radius: 4px;
                                        }
                                        
                                        pre code {
                                            padding: 0;
                                        }
                                        
                                        blockquote {
                                            background: var(--vscode-textBlockQuote-background);
                                            border-color: var(--vscode-textBlockQuote-border);
                                        }
                                        
                                        kbd {
                                            background-color: var(--vscode-keybindingLabel-background);
                                            color: var(--vscode-keybindingLabel-foreground);
                                            border-style: solid;
                                            border-width: 1px;
                                            border-radius: 3px;
                                            border-color: var(--vscode-keybindingLabel-border);
                                            border-bottom-color: var(--vscode-keybindingLabel-bottomBorder);
                                            box-shadow: inset 0 -1px 0 var(--vscode-widget-shadow);
                                            vertical-align: middle;
                                            padding: 1px 3px;
                                        }
                                        
                                        ::-webkit-scrollbar {
                                            width: 10px;
                                            height: 10px;
                                        }
                                        
                                        ::-webkit-scrollbar-corner {
                                            background-color: var(--vscode-editor-background);
                                        }
                                        
                                        ::-webkit-scrollbar-thumb {
                                            background-color: var(--vscode-scrollbarSlider-background);
                                        }
                                        ::-webkit-scrollbar-thumb:hover {
                                            background-color: var(--vscode-scrollbarSlider-hoverBackground);
                                        }
                                        ::-webkit-scrollbar-thumb:active {
                                            background-color: var(--vscode-scrollbarSlider-activeBackground);
                                        }
                                        ::highlight(find-highlight) {
                                            background-color: var(--vscode-editor-findMatchHighlightBackground);
                                        }
                                        ::highlight(current-find-highlight) {
                                            background-color: var(--vscode-editor-findMatchBackground);
                                        }
                                    `;
                                    console.log("Default style injected to id=_defaultStyles");
                                }
                            }
                            
                            // If document is already loaded, try to inject
                            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                                injectCSSVariables();
                            }
                            
                            // Always add listeners as fallbacks for race conditions.
                            // The internal flag will prevent multiple runs.
                            document.addEventListener('DOMContentLoaded', injectCSSVariables);
                            window.addEventListener('load', injectCSSVariables);
                        })()
                    """.trimIndent()

                    logger.debug("Injecting theme style into WebView(${viewId}), size: ${cssContent.length} bytes")
                    executeJavaScript(injectThemeScript)
                }

                // Pass the theme config without cssContent via message
                val themeConfigJson = gson.toJson(themeConfigCopy)
                val message = """
                    {
                        "type": "theme",
                        "text": "${themeConfigJson.replace("\"", "\\\"")}"
                    }
                """.trimIndent()

                postMessageToWebView(message)
                logger.debug("Theme config without cssContent has been sent to WebView")
            } else {
                // If there is no cssContent, send the original config directly
                val themeConfigJson = gson.toJson(currentThemeConfig)
                val message = """
                    {
                        "type": "theme",
                        "text": "${themeConfigJson.replace("\"", "\\\"")}"
                    }
                """.trimIndent()

                postMessageToWebView(message)
                logger.debug("Theme config has been sent to WebView")
            }
        } catch (e: Exception) {
            logger.error("Failed to send theme config to WebView", e)
        }
    }

    private fun setupJSBridge() {
        try {
            if (isDisposed) {
                logger.warn("Browser is disposed, aborting JS bridge setup.")
                return
            }
            // Create JS query object to handle messages from webview
            jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

            // Set callback for receiving messages from webview
            jsQuery?.addHandler { message ->
                coroutineScope.launch {
                    // Handle message
                    val protocol = project.getService(PluginContext::class.java).getRPCProtocol()
                    if (protocol != null) {
                        // Send message to plugin host
                        val serializeParam = SerializableObjectWithBuffers(emptyList<ByteArray>())
                        protocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostWebviews)
                            .onMessage(viewId, message, serializeParam)
                    } else {
                        logger.error("Cannot get RPC protocol instance, cannot handle message: $message")
                    }
                }
                null // No return value needed
            }
            logger.info("JS bridge setup successful after ${jsBridgeSetupRetries.get()} retries.")
            jsBridgeSetupRetries.set(0) // Reset counter on success
        } catch (e: Exception) {
            val attempt = jsBridgeSetupRetries.incrementAndGet()
            if (attempt > 5) {
                logger.error("Failed to setup JS bridge after $attempt attempts. Aborting.", e)
                return
            }
            logger.warn("Attempt $attempt to setup JS bridge failed. Retrying in 100ms.", e)
            scheduler.schedule({
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed) {
                        setupJSBridge()
                    }
                }
            }, 100, TimeUnit.MILLISECONDS)
        }
    }

    /**
         * Send message to WebView
         * @param message Message to send (JSON string)
         */
    fun postMessageToWebView(message: String) {
        if (!isDisposed) {
            // Send message to WebView via JavaScript function
            val script = """
                if (window.receiveMessageFromPlugin) {
                    window.receiveMessageFromPlugin($message);
                } else {
                    console.warn("receiveMessageFromPlugin not available");
                }
            """.trimIndent()
            executeJavaScript(script)
        }
    }

    /**
         * Enable resource request interception
         */
    fun enableResourceInterception(extension: Map<String, Any?>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val location = extension?.get("location") as? Map<String, Any?>
            val fsPath = location?.get("fsPath") as? String

            // Get JCEF client
            val client = browser.jbCefClient

            // Register console message handler
            client.addDisplayHandler(object: CefDisplayHandlerAdapter() {
                override fun onConsoleMessage(
                    browser: CefBrowser?,
                    level: CefSettings.LogSeverity?,
                    message: String?,
                    source: String?,
                    line: Int
                ): Boolean {
                    // Filter out Mermaid LaTeX warnings that cannot be fixed in the dependency
                    if (message?.contains("LaTeX-incompatible input") == true) {
                        return true
                    }

                    val msg = "WebView console message: [$level] $message (line: $line, source: $source)"
                    when (level) {
                       CefSettings.LogSeverity.LOGSEVERITY_FATAL, CefSettings.LogSeverity.LOGSEVERITY_ERROR -> logger.warn(msg)
                       CefSettings.LogSeverity.LOGSEVERITY_WARNING -> logger.warn(msg)
                       else -> logger.debug(msg)
                   }
                    return true
                }
            }, browser.cefBrowser)

            // Register load handler
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(
                    browser: CefBrowser?,
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean
                ) {
                    logger.debug("WebView loading state changed: isLoading=$isLoading, canGoBack=$canGoBack, canGoForward=$canGoForward")
                }

                override fun onLoadStart(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    transitionType: CefRequest.TransitionType?
                ) {
                    logger.debug("WebView started loading: ${frame?.url}, transition type: $transitionType")
                    isPageLoaded = false
                }

                override fun onLoadEnd(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int
                ) {
                    logger.debug("WebView finished loading: ${frame?.url}, status code: $httpStatusCode")
                    isPageLoaded = true

                    // Inject CSS to force full height, fixing the shrinking issue on sleep/wake.
                    // Only needed for Windowed mode.
                    if (!isOSR) {
                        val script = """
                            (function() {
                                if (document.head) {
                                    const style = document.createElement('style');
                                    style.type = 'text/css';
                                    // Use more robust CSS to ensure full height and width, even after sleep/wake
                                    style.innerHTML = 'html, body, #root { width: 100vw; height: 100vh; overflow: hidden; margin: 0; padding: 0; position: absolute; top: 0; left: 0; }';
                                    document.head.appendChild(style);
                                    console.log('RooCode Host: Injected robust full-height styles.');
                                }
                            })();
                        """.trimIndent()
                        executeJavaScript(script)
                    }

                    // Dynamically inject JCEF bridge function
                    // This ensures that window.sendMessageToPlugin always uses the valid cefQuery function for the current page context
                    val jsQueryFunction = jsQuery?.inject("msgStr")
                    if (jsQueryFunction != null) {
                        val bridgeScript = """
                            window.sendMessageToPlugin = function(message) {
                                try {
                                    const msgStr = JSON.stringify(message);
                                    $jsQueryFunction
                                } catch (e) {
                                    console.warn("Failed to send message to plugin", e);
                                }
                            };
                            console.log("JCEF Bridge injected dynamically");
                        """.trimIndent()
                        executeJavaScript(bridgeScript)
                    } else {
                        logger.warn("jsQuery is null, cannot inject JCEF bridge")
                    }

                    // Process any buffered messages
                    val processBufferScript = """
                        (function() {
                            if (window.messageBuffer && window.messageBuffer.length > 0) {
                                console.log("Processing " + window.messageBuffer.length + " buffered messages.");
                                for (const message of window.messageBuffer) {
                                    window.sendMessageToPlugin(message);
                                }
                                window.messageBuffer = []; // Clear the buffer
                            }
                        })();
                    """.trimIndent()
                    executeJavaScript(processBufferScript)

                    // Inject theme immediately after load
                    injectTheme()

                    // Sync zoom level after page load
                    // Only sync zoom level if not in OSR mode, as OSR mode inherits Swing scaling automatically.
                    if (!isOSR) {
                        syncZoomLevel()
                    }

                    // Notify page load completion
                    pageLoadCallback?.invoke()
                }

                override fun onLoadError(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?
                ) {
                    logger.debug("WebView load error: $failedUrl, error code: $errorCode, error message: $errorText")
                }
            }, browser.cefBrowser)
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    user_gesture: Boolean,
                    is_redirect: Boolean
                ): Boolean {
                    logger.debug("onBeforeBrowse,url:${request?.url}")
                    if(request?.url?.startsWith("http://localhost") == false){
                        BrowserUtil.browse(request.url)
                        return true
                    }
                    return false
                }

                override fun getResourceRequestHandler(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    isNavigation: Boolean,
                    isDownload: Boolean,
                    requestInitiator: String?,
                    disableDefaultHandling: BoolRef?
                ): CefResourceRequestHandler? {
                    logger.debug("getResourceRequestHandler,fsPath:${fsPath}")
                    if (fsPath != null && request?.url?.contains("localhost")==true) {
                        // Set resource root directory
                        val path = Paths.get(fsPath)
                        return LocalResHandler(path.pathString,request)
                    }else{
                        return null
                    }

                }
            }, browser.cefBrowser)
            logger.debug("WebView resource interception enabled: $viewType/$viewId")
        } catch (e: Exception) {
            logger.error("Failed to enable WebView resource interception", e)
        }
    }

    /**
         * Load URL
         */
    fun loadUrl(url: String) {
        if (!isDisposed) {
            logger.debug("WebView loading URL: $url")
            browser.loadURL(url)
        }
    }

    /**
         * Load HTML content
         */
    fun loadHtml(html: String, baseUrl: String? = null) {
        if (!isDisposed) {
            logger.debug("WebView loading HTML content, length: ${html.length}, baseUrl: $baseUrl")
            if(baseUrl != null) {
                browser.loadHTML(html, baseUrl)
            }else {
                browser.loadHTML(html)
            }
        }
    }

    /**
         * Execute JavaScript
         */
    fun executeJavaScript(script: String) {
        if (!isDisposed) {
            logger.debug("WebView executing JavaScript, script length: ${script.length}")
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }

    /**
     * Open developer tools
     */
    fun openDevTools() {
        if (!isDisposed) {
            browser.openDevtools()
        }
    }

    override fun dispose() {
        if (!isDisposed) {
            scheduler.shutdownNow()
            browser.dispose()
            isDisposed = true
            logger.debug("WebView instance released: $viewType/$viewId")
        }
    }
}
