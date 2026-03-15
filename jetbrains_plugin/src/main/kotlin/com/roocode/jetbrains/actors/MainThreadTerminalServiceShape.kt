// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.actors

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.roocode.jetbrains.core.PluginContext
import com.roocode.jetbrains.terminal.TerminalInstance
import com.roocode.jetbrains.terminal.TerminalInstanceManager
import com.roocode.jetbrains.terminal.TerminalConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel


/**
 * Main thread terminal service interface.
 * Corresponds to the MainThreadTerminalServiceShape interface in VSCode.
 */
interface MainThreadTerminalServiceShape : Disposable {
    /**
     * Creates terminal.
     * @param extHostTerminalId Extension host terminal ID
     * @param config Terminal launch configuration
     */
    suspend fun createTerminal(extHostTerminalId: String, config: Map<String, Any?>)

    /**
     * Disposes terminal resources.
     * @param id Terminal identifier (can be String or Number)
     */
    fun dispose(id: Any)
    
    /**
     * Hides terminal.
     * @param id Terminal identifier (can be String or Number)
     */
    fun hide(id: Any)
    
    /**
     * Sends text to terminal.
     * @param id Terminal identifier (can be String or Number)
     * @param text Text to send
     * @param shouldExecute Whether to execute
     */
    fun sendText(id: Any, text: String, shouldExecute: Boolean?)
    
    /**
     * Shows terminal.
     * @param id Terminal identifier (can be String or Number)
     * @param preserveFocus Whether to preserve focus
     */
    fun show(id: Any, preserveFocus: Boolean?)
    
    /**
     * Registers process support.
     * @param isSupported Whether supported
     */
    fun registerProcessSupport(isSupported: Boolean)
    
    /**
     * Registers profile provider.
     * @param id Profile provider ID
     * @param extensionIdentifier Extension identifier
     */
    fun registerProfileProvider(id: String, extensionIdentifier: String)
    
    /**
     * Unregisters profile provider.
     * @param id Profile provider ID
     */
    fun unregisterProfileProvider(id: String)
    
    /**
     * Registers completion provider.
     * @param id Completion provider ID
     * @param extensionIdentifier Extension identifier
     * @param triggerCharacters List of trigger characters
     */
    fun registerCompletionProvider(id: String, extensionIdentifier: String, vararg triggerCharacters: String)
    
    /**
     * Unregisters completion provider.
     * @param id Completion provider ID
     */
    fun unregisterCompletionProvider(id: String)
    
    /**
     * Registers quick fix provider.
     * @param id Quick fix provider ID
     * @param extensionIdentifier Extension identifier
     */
    fun registerQuickFixProvider(id: String, extensionIdentifier: String)
    
    /**
     * Unregisters quick fix provider.
     * @param id Quick fix provider ID
     */
    fun unregisterQuickFixProvider(id: String)
    
    /**
     * Set environment variable collection
     * @param extensionIdentifier Extension identifier
     * @param persistent Whether to persist
     * @param collection Serializable environment variable collection
     * @param descriptionMap Serializable environment description mapping
     */
    fun setEnvironmentVariableCollection(
        extensionIdentifier: String,
        persistent: Boolean,
        collection: Map<String, Any?>?,
        descriptionMap: Map<String, Any?>
    )

    /**
     * Start sending data events
     */
    fun startSendingDataEvents()
    
    /**
     * Stop sending data events
     */
    fun stopSendingDataEvents()
    
    /**
     * Start sending command events
     */
    fun startSendingCommandEvents()
    
    /**
     * Stop sending command events
     */
    fun stopSendingCommandEvents()
    
    /**
     * Start link provider
     */
    fun startLinkProvider()
    
    /**
     * Stop link provider
     */
    fun stopLinkProvider()

    /**
     * Send process data
     * @param terminalId Terminal ID
     * @param data Data
     */
    fun sendProcessData(terminalId: Int, data: String)
    
    /**
     * Send process ready
     * @param terminalId Terminal ID
     * @param pid Process ID
     * @param cwd Current working directory
     * @param windowsPty Windows PTY information
     */
    fun sendProcessReady(
        terminalId: Int,
        pid: Int,
        cwd: String,
        windowsPty: Map<String, Any?>?
    )
    
    /**
     * Send process property
     * @param terminalId Terminal ID
     * @param property Process property
     */
    fun sendProcessProperty(terminalId: Int, property: Map<String, Any?>)
    
    /**
     * Send process exit
     * @param terminalId Terminal ID
     * @param exitCode Exit code
     */
    fun sendProcessExit(terminalId: Int, exitCode: Int?)
}

/**
 * Main thread terminal service implementation class
 * Provides implementation of IDEA platform terminal-related functionality
 */
class MainThreadTerminalService(private val project: Project) : MainThreadTerminalServiceShape {
    private val logger = Logger.getInstance(MainThreadTerminalService::class.java)
    
    // Use terminal instance manager
    private val terminalManager = project.service<TerminalInstanceManager>()
    
    // Coroutine scope - use IO dispatcher to avoid Main Dispatcher issues
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override suspend fun createTerminal(extHostTerminalId: String, config: Map<String, Any?>) {
        logger.debug("🚀 Creating terminal: $extHostTerminalId, config: $config")
        
        try {
            // Check if terminal already exists
            if (terminalManager.containsTerminal(extHostTerminalId)) {
                logger.warn("Terminal already exists: $extHostTerminalId")
                return
            }
            
            // Get RPC protocol instance
            val pluginContext = project.getService(PluginContext::class.java)
            val rpcProtocol = pluginContext.getRPCProtocol()
            if (rpcProtocol == null) {
                logger.error("❌ Unable to get RPC protocol instance, terminal creation failed: $extHostTerminalId")
                throw IllegalStateException("RPC protocol not initialized")
            }
            logger.debug("✅ Got RPC protocol instance: ${rpcProtocol.javaClass.simpleName}")
            
            // Allocate numeric ID
            val numericId = terminalManager.allocateNumericId()
            logger.debug("🔢 Allocated terminal numeric ID: $numericId")
            
            // Create terminal instance
            val terminalConfig = TerminalConfig.fromMap(config)
            val terminalInstance = TerminalInstance(extHostTerminalId, numericId, project, terminalConfig, rpcProtocol)

            // Initialize terminal
            terminalInstance.initialize()

            // Register to manager
            terminalManager.registerTerminal(extHostTerminalId, terminalInstance)
            
            logger.debug("✅ Terminal created successfully: $extHostTerminalId (numericId: $numericId)")
            
        } catch (e: Exception) {
            logger.error("❌ Failed to create terminal: $extHostTerminalId", e)
            // Clean up possibly created resources
            terminalManager.unregisterTerminal(extHostTerminalId)
            throw e
        }
    }

    override fun dispose(id: Any) {
        try {
            logger.debug("🧹 Destroying terminal: $id")
            
            val terminalInstance = terminalManager.unregisterTerminal(id.toString())
            if (terminalInstance != null) {
                terminalInstance.dispose()
                logger.debug("✅ Terminal destroyed: $id")
            } else {
                logger.warn("Terminal does not exist: $id")
            }
            
        } catch (e: Exception) {
            logger.error("❌ Failed to destroy terminal: $id", e)
        }
    }

    override fun hide(id: Any) {
        try {
            logger.debug("🙈 Hiding terminal: $id")
            
            val terminalInstance = getTerminalInstance(id)
            if (terminalInstance != null) {
                terminalInstance.hide()
                logger.debug("✅ Terminal hidden: $id")
            } else {
                logger.warn("Terminal does not exist: $id")
            }
            
        } catch (e: Exception) {
            logger.error("❌ Failed to hide terminal: $id", e)
        }
    }

    override fun sendText(id: Any, text: String, shouldExecute: Boolean?) {
        try {
            logger.debug("📤 Sending text to terminal $id: $text (execute: $shouldExecute)")
            
            val terminalInstance = getTerminalInstance(id)
            if (terminalInstance != null) {
                terminalInstance.sendText(text, shouldExecute ?: false)
                logger.debug("✅ Text sent to terminal: $id")
            } else {
                logger.warn("Terminal does not exist: $id")
            }
            
        } catch (e: Exception) {
            logger.error("❌ Failed to send text to terminal: $id", e)
        }
    }

    override fun show(id: Any, preserveFocus: Boolean?) {
        try {
            logger.debug("👁️ Showing terminal: $id (preserve focus: $preserveFocus)")
            
            val terminalInstance = getTerminalInstance(id)
            if (terminalInstance != null) {
                terminalInstance.show(preserveFocus ?: true)
                logger.debug("✅ Terminal shown: $id")
            } else {
                logger.warn("Terminal does not exist: $id")
            }
            
        } catch (e: Exception) {
            logger.error("❌ Failed to show terminal: $id", e)
        }
    }

    override fun registerProcessSupport(isSupported: Boolean) {
        logger.debug("📋 Registering process support: $isSupported")
        // In IDEA, process support is built-in, mainly used for logging state here
    }

    override fun registerProfileProvider(id: String, extensionIdentifier: String) {
        logger.debug("📋 Registering profile provider: $id (extension: $extensionIdentifier)")
        // TODO: Implement profile provider registration logic
    }

    override fun unregisterProfileProvider(id: String) {
        logger.debug("📋 Unregistering profile provider: $id")
        // TODO: Implement profile provider unregistration logic
    }

    override fun registerCompletionProvider(id: String, extensionIdentifier: String, vararg triggerCharacters: String) {
        logger.debug("📋 Registering completion provider: $id (extension: $extensionIdentifier, trigger characters: ${triggerCharacters.joinToString()})")
        // TODO: Implement completion provider registration logic
    }

    override fun unregisterCompletionProvider(id: String) {
        logger.debug("📋 Unregistering completion provider: $id")
        // TODO: Implement completion provider unregistration logic
    }

    override fun registerQuickFixProvider(id: String, extensionIdentifier: String) {
        logger.debug("📋 Registering quick fix provider: $id (extension: $extensionIdentifier)")
        // TODO: Implement quick fix provider registration logic
    }

    override fun unregisterQuickFixProvider(id: String) {
        logger.debug("📋 Unregistering quick fix provider: $id")
        // TODO: Implement quick fix provider unregistration logic
    }

    override fun setEnvironmentVariableCollection(
        extensionIdentifier: String,
        persistent: Boolean,
        collection: Map<String, Any?>?,
        descriptionMap: Map<String, Any?>
    ) {
        logger.debug("📋 Setting environment variable collection: $extensionIdentifier (persistent: $persistent)")
        // TODO: Implement environment variable collection setting logic
    }

    override fun startSendingDataEvents() {
        logger.debug("📋 Starting to send data events")
        // TODO: Implement data event sending logic
    }

    override fun stopSendingDataEvents() {
        logger.debug("📋 Stopping data event sending")
        // TODO: Implement stopping data event sending logic
    }

    override fun startSendingCommandEvents() {
        logger.debug("📋 Starting to send command events")
        // TODO: Implement command event sending logic
    }

    override fun stopSendingCommandEvents() {
        logger.debug("📋 Stopping command event sending")
        // TODO: Implement stopping command event sending logic
    }

    override fun startLinkProvider() {
        logger.debug("📋 Starting link provider")
        // TODO: Implement link provider startup logic
    }

    override fun stopLinkProvider() {
        logger.debug("📋 Stopping link provider")
        // TODO: Implement link provider stopping logic
    }

    override fun sendProcessData(terminalId: Int, data: String) {
        logger.debug("Send process data to terminal $terminalId")
        // Send process data to terminal
    }

    override fun sendProcessReady(terminalId: Int, pid: Int, cwd: String, windowsPty: Map<String, Any?>?) {
        logger.debug("Send process ready: terminal=$terminalId, pid=$pid, cwd=$cwd")
        // Send process ready information
    }

    override fun sendProcessProperty(terminalId: Int, property: Map<String, Any?>) {
        logger.debug("📋 Sending process property: terminal=$terminalId")
        // TODO: Notify extension host of process property changes
    }

    override fun sendProcessExit(terminalId: Int, exitCode: Int?) {
        logger.debug("📋 Sending process exit: terminal=$terminalId, exit code=$exitCode")
        // TODO: Notify extension host of process exit
    }

    /**
     * Get terminal instance (by string ID or numeric ID)
     */
    fun getTerminalInstance(id: Any): TerminalInstance? {
        return when (id) {
            is String -> terminalManager.getTerminalInstance(id)
            is Number -> terminalManager.getTerminalInstance(id.toInt())
            else -> {
                logger.warn("Unsupported ID type: ${id.javaClass.name}, attempting to convert to string")
                terminalManager.getTerminalInstance(id.toString())
            }
        }
    }
    
    /**
     * Get all terminal instances
     */
    fun getAllTerminals(): Collection<TerminalInstance> {
        return terminalManager.getAllTerminals()
    }

    override fun dispose() {
        logger.debug("🧹 Disposing main thread terminal service")
        
        try {
            // Cancel coroutine scope
            scope.cancel()
            
            // Terminal instance manager will automatically handle cleanup of all terminals
            // No manual cleanup needed here as TerminalInstanceManager is project-level service
            
            logger.debug("✅ Main thread terminal service disposed")
            
        } catch (e: Exception) {
            logger.error("❌ Failed to dispose main thread terminal service", e)
        }
    }
}
