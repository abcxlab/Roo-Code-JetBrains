// Copyright 2009-2025 Weibo, Inc.
// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.roocode.jetbrains.ipc.proxy.IRPCProtocol

/**
 * Plugin global context
 * Used for managing globally accessible resources and objects
 */
@Service(Service.Level.PROJECT)
class PluginContext: Disposable {
    private val logger = Logger.getInstance(PluginContext::class.java)
    
    // RPC protocol instance
    @Volatile
    private var rpcProtocol: IRPCProtocol? = null
    
    /**
     * Set RPC protocol instance
     * @param protocol RPC protocol instance
     */
    fun setRPCProtocol(protocol: IRPCProtocol) {
        logger.debug("Setting RPC protocol instance")
        rpcProtocol = protocol
    }
    
    /**
     * Get RPC protocol instance
     * @return RPC protocol instance, or null if not set
     */
    fun getRPCProtocol(): IRPCProtocol? {
        return rpcProtocol
    }
    
    /**
     * Clear all resources
     */
    fun clear() {
        logger.debug("Clearing resources in PluginContext")
        rpcProtocol = null
    }
    
    override fun dispose() {
        clear()
    }
}
