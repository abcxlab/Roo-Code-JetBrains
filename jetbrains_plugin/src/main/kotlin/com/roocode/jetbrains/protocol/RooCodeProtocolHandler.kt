package com.roocode.jetbrains.protocol

import com.intellij.openapi.application.JBProtocolCommand
import com.intellij.openapi.application.JBProtocolCommandResult
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.roocode.jetbrains.core.ExtensionHostManager
import com.roocode.jetbrains.core.PluginContext
import com.roocode.jetbrains.core.ServiceProxyRegistry
import java.net.URI

/**
 * Custom protocol handler for RooCode.
 * Handles URIs with the IDE's specific scheme (e.g., idea://, webstorm://) via JBProtocolCommand.
 *
 * Expected URI format: idea://WeCode-AI.RunVSAgent.roo-cline/auth/clerk/callback?code=...
 * The command name is "WeCode-AI.RunVSAgent.roo-cline", which matches the extensionId.
 * The target parameter will contain the sub-path (e.g., "auth/clerk/callback").
 *
 * Note: We override [executeAndGetResult] instead of [execute] because the base class's
 * [execute] method uses a raw Java `Map` type (without generics) which causes a Kotlin
 * compiler error "'execute' overrides nothing" when trying to override with `Map<String, String>`.
 */
class RooCodeProtocolHandler : JBProtocolCommand("WeCode-AI.RunVSAgent.roo-cline") {
    private val logger = Logger.getInstance(RooCodeProtocolHandler::class.java)

    override suspend fun executeAndGetResult(
        target: String?,
        parameters: Map<String, String>,
        fragment: String?
    ): JBProtocolCommandResult? {
        // target contains the path after idea://WeCode-AI.RunVSAgent.roo-cline/
        // e.g. "auth/clerk/callback"
        logger.debug("Received protocol command: target=$target, parameters=$parameters, fragment=$fragment")

        try {
            // Use the actual extension ID.
            val extensionId = "WeCode-AI.RunVSAgent.roo-cline"
            val subPath = target ?: ""

            // Reconstruct URI components for the extension host.
            // We use the dynamically determined scheme to match what Extension Host expects.
            val scheme = com.roocode.jetbrains.core.ExtensionHostManager.getIdeProtocolScheme()
            val queryString = parameters.entries.joinToString("&") { "${it.key}=${it.value}" }
            val uriString = "$scheme://$extensionId/$subPath" +
                (if (queryString.isNotEmpty()) "?$queryString" else "") +
                (if (!fragment.isNullOrEmpty()) "#$fragment" else "")
            
            val parsedUri = URI.create(uriString)
            logger.debug("Reconstructed URI for Extension Host: $uriString")
            
            // Dispatch to the correct project's extension host handler via RPC
            dispatchUri(extensionId, parsedUri)
        } catch (e: Exception) {
            logger.error("Failed to handle protocol command: target=$target", e)
            return JBProtocolCommandResult("Error: ${e.message}")
        }

        // null dialogMessage means success
        return JBProtocolCommandResult(null)
    }

    /**
     * Dispatches a URI to the registered extension handler via RPC.
     * Iterates over all open projects to find the one with an active RPC protocol.
     */
    private fun dispatchUri(extensionId: String, uri: URI) {
        val projects = ProjectManager.getInstance().openProjects
        // DEBUG: RooCode Cloud Integration
        logger.debug("Attempting to dispatch URI to ${projects.size} open projects")
        
        if (projects.isEmpty()) {
            logger.warn("No open projects available to dispatch URI")
            return
        }

        for (project in projects) {
            // DEBUG: RooCode Cloud Integration
            logger.debug("Trying to dispatch to project: ${project.name}")
            val rpcProtocol = project.getService(PluginContext::class.java)?.getRPCProtocol() ?: continue
            val proxy = rpcProtocol.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostUrls)

            // The proxy is dynamically generated; try to dispatch.
            // We use handle=0 as a fallback. In VS Code's ExtHostUrls implementation,
            // if the specific handle is not found, it will broadcast to all registered UriHandlers.
            // Since MainThreadUrls is not a project service, we cannot easily retrieve the exact handle here.
            try {
                proxy.handleExternalUri(0, uriToComponents(uri))
                logger.debug("Successfully dispatched URI to project '${project.name}' for extension $extensionId")
                return
            } catch (e: Exception) {
                logger.debug("Failed to dispatch URI via project '${project.name}': ${e.message}")
            }
        }

        logger.warn("No project could handle URI for extension: $extensionId")
    }

    private fun uriToComponents(uri: URI): Map<String, Any?> {
        return mapOf(
            "scheme" to uri.scheme,
            "authority" to uri.authority,
            "path" to uri.path,
            "query" to uri.query,
            "fragment" to uri.fragment
        )
    }
}
