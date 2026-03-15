// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.actors

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.roocode.jetbrains.core.PluginContext
import com.roocode.jetbrains.core.ServiceProxyRegistry
import com.roocode.jetbrains.ipc.proxy.IRPCProtocol
import com.roocode.jetbrains.ipc.proxy.interfaces.ExtHostDiagnosticsProxy
import com.roocode.jetbrains.problems.Problem
import com.roocode.jetbrains.util.URI

/**
 * Main thread diagnostics interface.
 * Corresponds to the MainThreadDiagnosticsShape interface in VSCode.
 * Defines the contract for diagnostic management operations that can be performed
 * from the main thread of the IDE.
 */
interface MainThreadDiagnosticsShape : Disposable {
    /**
     * Accepts a change in markers (diagnostics).
     * @param markers A list of pairs, where each pair contains a resource URI and a list of problems for that resource.
     */
    fun acceptMarkersChange(markers: List<List<Any>>)
}

/**
 * Implementation of the main thread diagnostics interface.
 * Provides concrete implementation for pushing diagnostic information to the extension host.
 */
class MainThreadDiagnostics(private val project: Project) : MainThreadDiagnosticsShape {

    override fun acceptMarkersChange(markers: List<List<Any>>) {
        val rpcProtocol = project.getService(PluginContext::class.java).getRPCProtocol()
        if (rpcProtocol == null) {
            val logger = com.intellij.openapi.diagnostic.Logger.getInstance(MainThreadDiagnostics::class.java)
            logger.error("Failed to get RPC protocol, cannot push diagnostics.")
            return
        }
        val extHostDiagnostics: ExtHostDiagnosticsProxy = rpcProtocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostDiagnostics)
        extHostDiagnostics.acceptMarkersChange(markers)
    }
    
    override fun dispose() {
        // No resources to dispose
    }

}