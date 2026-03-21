// Copyright 2009-2025 Weibo, Inc.
// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.core

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationInfo
import com.roocode.jetbrains.core.RPCManager
import com.roocode.jetbrains.editor.EditorAndDocManager
import com.roocode.jetbrains.ipc.NodeSocket
import com.roocode.jetbrains.ipc.PersistentProtocol
import com.roocode.jetbrains.ipc.proxy.ResponsiveState
import com.roocode.jetbrains.ipc.proxy.interfaces.ExtHostDiagnosticsProxy
import com.roocode.jetbrains.problems.Problem
import com.roocode.jetbrains.problems.ProblemListener
import com.roocode.jetbrains.problems.Topics
import com.roocode.jetbrains.util.PluginConstants
import com.roocode.jetbrains.util.PluginResourceUtil
import com.roocode.jetbrains.util.URI
import com.roocode.jetbrains.workspace.WorkspaceFileChangeManager
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.Socket
import java.nio.channels.SocketChannel
import java.nio.file.Paths
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Extension host manager, responsible for communication with extension processes.
 * Handles Ready and Initialized messages from extension processes.
 */
class ExtensionHostManager : Disposable {
    private val logger = Logger.getInstance(ExtensionHostManager::class.java)

    private val project: Project
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

     // Communication protocol
    private var nodeSocket: NodeSocket
    private var protocol: PersistentProtocol? = null

     // RPC manager
    private var rpcManager: RPCManager? = null

     // Extension manager
    private var extensionManager: ExtensionManager? = null

     // Plugin identifier
    private var rooCodeIdentifier: String? = null

     // Message bus connection
    private var messageBusConnection: MessageBusConnection? = null

    // JSON serialization
    private val gson = Gson()

    // Last diagnostic log time
    private var lastDiagnosticLogTime = 0L

    private var  projectPath: String? = null

     // Support Socket constructor
    constructor(clientSocket: Socket, projectPath: String,project: Project) {
        clientSocket.tcpNoDelay = true
        this.nodeSocket = NodeSocket(clientSocket, "extension-host")
        this.projectPath = projectPath
        this.project = project
    }
     // Support SocketChannel constructor
    constructor(clientChannel: SocketChannel, projectPath: String , project: Project) {
        this.nodeSocket = NodeSocket(clientChannel, "extension-host")
        this.projectPath = projectPath
        this.project = project
    }

    /**
     * Start communication with the extension process.
     */
    fun start() {
        try {
             // Initialize extension manager
            extensionManager = ExtensionManager()
            val extensionPath = PluginResourceUtil.getResourcePath(PluginConstants.PLUGIN_ID, PluginConstants.PLUGIN_CODE_DIR)
            rooCodeIdentifier = extensionPath?.let { extensionManager!!.registerExtension(it).identifier.value }
             // Create protocol
            protocol = PersistentProtocol(
                PersistentProtocol.PersistentProtocolOptions(
                    socket = nodeSocket,
                    initialChunk = null,
                    loadEstimator = null,
                    sendKeepAlive = true
                ),
                this::handleMessage
            )
    
            logger.debug("ExtensionHostManager started successfully")
        } catch (e: Exception) {
            logger.error("Failed to start ExtensionHostManager", e)
            dispose()
        }
    }

    /**
     * Get RPC responsive state.
     * @return Responsive state, or null if RPC manager is not initialized.
     */
    fun getResponsiveState(): ResponsiveState? {
        val currentTime = System.currentTimeMillis()
         // Limit diagnostic log frequency, at most once every 60 seconds
        val shouldLogDiagnostics = currentTime - lastDiagnosticLogTime > 60000
        if (rpcManager == null) {
            if (shouldLogDiagnostics) {
                logger.debug("Unable to get responsive state: RPC manager is not initialized")
                lastDiagnosticLogTime = currentTime
            }
            return null
        }
         // Log connection diagnostic information
        if (shouldLogDiagnostics) {
            val socketInfo = buildString {
                append("NodeSocket: ")
                append(if (nodeSocket.isClosed()) "closed" else "active")
                append(", input stream: ")
                append(if (nodeSocket.isInputClosed()) "closed" else "normal")
                append(", output stream: ")
                append(if (nodeSocket.isOutputClosed()) "closed" else "normal")
                append(", disposed=")
                append(nodeSocket.isDisposed())
            }

            val protocolInfo = protocol?.let { proto ->
                "Protocol: ${if (proto.isDisposed()) "disposed" else "active"}"
            } ?: "Protocol is null"
            logger.debug("Connection diagnostics: $socketInfo, $protocolInfo")
            lastDiagnosticLogTime = currentTime
        }
        return rpcManager?.getRPCProtocol()?.responsiveState
    }

    /**
     * Handle messages from the extension process.
     */
    private fun handleMessage(data: ByteArray) {
         // Check if data is a single-byte message (extension host protocol message)
        if (data.size == 1) {
             // Try to parse as extension host message type

            when (ExtensionHostMessageType.fromData(data)) {
                ExtensionHostMessageType.Ready -> handleReadyMessage()
                ExtensionHostMessageType.Initialized -> handleInitializedMessage()
                ExtensionHostMessageType.Terminate -> logger.info("Received Terminate message")
                null -> logger.debug("Received unknown message type: ${data.contentToString()}")
            }
        } else {
            logger.debug("Received message with length ${data.size}, not handling as extension host message")
        }
    }

    /**
     * Handle Ready message, send initialization data.
     */
    private fun handleReadyMessage() {
        logger.debug("Received Ready message from extension host")

        try {
             // Build initialization data
            val initData = createInitData()

             // Send initialization data
            val jsonData = gson.toJson(initData).toByteArray()

            protocol?.send(jsonData)
            logger.debug("Sent initialization data to extension host")
        } catch (e: Exception) {
            logger.error("Failed to handle Ready message", e)
        }
    }

    /**
     * Handle Initialized message, create RPC manager and activate plugin.
     */
    private fun handleInitializedMessage() {
        logger.debug("Received Initialized message from extension host")

        try {
            // Get protocol
            val protocol = this.protocol ?: throw IllegalStateException("Protocol is not initialized")
            val extensionManager = this.extensionManager ?: throw IllegalStateException("ExtensionManager is not initialized")

            // Create RPC manager
            rpcManager = RPCManager(protocol, extensionManager,null, project)

            // Start initialization process
            rpcManager?.startInitialize()

            // Start file monitoring
            project.getService(WorkspaceFileChangeManager::class.java)
//            WorkspaceFileChangeManager.getInstance()
            project.getService(EditorAndDocManager::class.java).initCurrentIdeaEditor()
             // Activate RooCode plugin
            val rooCodeId = rooCodeIdentifier ?: throw IllegalStateException("RooCode identifier is not initialized")
            extensionManager.activateExtension(rooCodeId, rpcManager!!.getRPCProtocol())
                .whenComplete { _, error ->
                    if (error != null) {
                        logger.error("Failed to activate RooCode plugin", error)
                    } else {
                        logger.debug("RooCode plugin activated successfully")
                    }
                }

            logger.debug("Initialized extension host")

            // Subscribe to problem updates after RPC is ready
            subscribeToProblemUpdates()
        } catch (e: Exception) {
            logger.error("Failed to handle Initialized message", e)
        }
    }

    private fun subscribeToProblemUpdates() {
        if (messageBusConnection != null) {
            logger.warn("Message bus connection already established.")
            return
        }

        messageBusConnection = project.messageBus.connect(this)
        messageBusConnection?.subscribe(Topics.PROBLEMS_TOPIC, object : ProblemListener {
            override fun problemsUpdated(problems: Map<URI, List<Problem>>) {
                pushProblemsToExtHost(problems)
            }
        })
        logger.debug("Subscribed to problem updates topic.")
    }

    private fun pushProblemsToExtHost(problems: Map<URI, List<Problem>>) {
        val rpcProtocol = rpcManager?.getRPCProtocol()
        if (rpcProtocol == null) {
            logger.warn("RPC protocol not available, unable to push problems.")
            return
        }

        try {
            val proxy = rpcProtocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostDiagnostics)
            val markers = problems.map { (uri, problemList) ->
                listOf(uri, problemList)
            }
            proxy.acceptMarkersChange(markers)
            logger.debug("Pushed ${problems.values.sumOf { it.size }} problems to extension host.")
        } catch (e: Exception) {
            logger.error("Failed to push problems to extension host", e)
        }
    }

    /**
     * Create initialization data.
     * Corresponds to the initData object in main.js.
     */
    private fun createInitData(): Map<String, Any?> {
        val pluginDir = getPluginDir()
        val basePath = projectPath

        return mapOf(
            "commit" to "development",
            "version" to getIDEVersion(),
            "quality" to null,
            "parentPid" to ProcessHandle.current().pid(),
            "environment" to mapOf(
                "isExtensionDevelopmentDebug" to false,
                "appName" to getCurrentIDEName(),
                "appHost" to "node",
                "appLanguage" to "en",
                "appUriScheme" to getIdeProtocolScheme(),
                "appRoot" to uriFromPath(pluginDir),
                "globalStorageHome" to uriFromPath(Paths.get(System.getProperty("user.home"),".roo-cline", "globalStorage").toString()),
                "workspaceStorageHome" to uriFromPath(Paths.get(System.getProperty("user.home"),".roo-cline", "workspaceStorage").toString()),
                "extensionDevelopmentLocationURI" to null,
                "extensionTestsLocationURI" to null,
                "useHostProxy" to false,
                "skipWorkspaceStorageLock" to false,
                "isExtensionTelemetryLoggingOnly" to false
            ),
            "workspace" to mapOf(
                "id" to "intellij-workspace",
                "name" to "IntelliJ Workspace",
                "transient" to false,
                "configuration" to null,
                "isUntitled" to false
            ),
            "remote" to mapOf(
                "authority" to null,
                "connectionData" to null,
                "isRemote" to false
            ),
            "extensions" to mapOf<String, Any>(
                "versionId" to 1,
                "allExtensions" to (extensionManager?.getAllExtensionDescriptions() ?: emptyList<Any>()),
                "myExtensions" to (extensionManager?.getAllExtensionDescriptions()?.map { it.identifier } ?: emptyList<Any>()),
                "activationEvents" to (extensionManager?.getAllExtensionDescriptions()?.associate { ext ->
                    ext.identifier.value to (ext.activationEvents ?: emptyList<String>())
                } ?: emptyMap())
            ),
            "telemetryInfo" to mapOf(
                "sessionId" to "intellij-session",
                "machineId" to "intellij-machine",
                "sqmId" to "",
                "devDeviceId" to "",
                "firstSessionDate" to java.time.Instant.now().toString(),
                "msftInternal" to false
            ),
            "logLevel" to 0, // Info level
            "loggers" to emptyList<Any>(),
            "logsLocation" to uriFromPath(Paths.get(pluginDir, "logs").toString()),
            "autoStart" to true,
            "consoleForward" to mapOf(
                "includeStack" to false,
                "logNative" to false
            ),
            "uiKind" to 1 // Desktop
        )
    }

    /**
     * Get current IDE name.
     */
    private fun getCurrentIDEName(): String {
        val applicationInfo = ApplicationInfo.getInstance()
         // Get product code, which is the main identifier for distinguishing IDEs
        val productCode = applicationInfo.build.productCode
        val fullName = applicationInfo.fullApplicationName

        val ideName = when (productCode) {
            "IC" -> "IntelliJ IDEA"
            "IU" -> "IntelliJ IDEA"
            "AS" -> "Android Studio"
            "AI" -> "Android Studio"
            "WS" -> "WebStorm"
            "PS" -> "PhpStorm"
            "PY" -> "PyCharm Professional"
            "PC" -> "PyCharm Community"
            "GO" -> "GoLand"
            "CL" -> "CLion"
            "RD" -> "Rider"
            "RM" -> "RubyMine"
            "DB" -> "DataGrip"
            "DS" -> "DataSpell"
            else -> if (fullName?.contains("Android Studio") == true) "Android Studio" else "JetBrains"
        }
        logger.debug("Get IDE name, productCode: $productCode ideName: $ideName fullName: $fullName")
        return ideName
    }

    companion object {
        /**
         * Dynamically determines the protocol scheme based on the current IDE.
         * This ensures that callbacks (like OAuth) are routed back to the specific IDE instance,
         * bypassing JetBrains Toolbox which intercepts the generic "jetbrains://" scheme.
         */
        fun getIdeProtocolScheme(): String {
            val productCode = ApplicationInfo.getInstance().build.productCode
            return when (productCode) {
                "IU", "IC", "IE" -> "idea"
                "WS" -> "webstorm"
                "PY", "PC" -> "pycharm"
                "GO" -> "goland"
                "RD" -> "rider"
                "CL" -> "clion"
                "PS" -> "phpstorm"
                "RM" -> "rubymine"
                "AI" -> "studio" // Android Studio
                "DB" -> "datagrip"
                "RR" -> "rustrover"
                "QA" -> "aqua"
                else -> "idea" // Fallback to idea
            }.also { scheme ->
                // DEBUG: RooCode Cloud Integration
                Logger.getInstance(ExtensionHostManager::class.java).debug("Detected IDE protocol scheme: $scheme")
            }
        }
    }

    /**
     * Get current IDE version.
     */
    private fun getIDEVersion(): String {
        val applicationInfo = ApplicationInfo.getInstance()
        val version = applicationInfo.shortVersion ?: "1.0.0"
        logger.debug("Get IDE version: $version")

        val pluginVersion = PluginManagerCore.getPlugin(PluginId.getId(PluginConstants.PLUGIN_ID))?.version
        if (pluginVersion != null) {
            val fullVersion = "$version, $pluginVersion"
            logger.debug("Get IDE version and plugin version: $fullVersion")
            return fullVersion
        }

        return version
    }

    /**
     * Get plugin directory.
     */
    private fun getPluginDir(): String {
        return PluginResourceUtil.getResourcePath(PluginConstants.PLUGIN_ID, "")
            ?: throw IllegalStateException("Unable to get plugin directory")
    }

    /**
     * Create URI object.
     */
    private fun uriFromPath(path: String): URI {
        return URI.file(path)
    }

    /**
     * Resource disposal.
     */
    override fun dispose() {
        logger.info("Disposing ExtensionHostManager")

        // Cancel coroutines
        coroutineScope.cancel()

        // Release RPC manager
        rpcManager = null

        // Dispose message bus connection
        messageBusConnection?.dispose()
        messageBusConnection = null

        // Release protocol
        protocol?.dispose()
        protocol = null

        // Release socket
        nodeSocket.dispose()

        logger.info("ExtensionHostManager disposed")
    }
}
