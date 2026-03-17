// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.project

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.roocode.jetbrains.commands.CommandRegistry
import com.roocode.jetbrains.commands.ICommand
import com.roocode.jetbrains.editor.createURI
import java.nio.file.Paths

/**
 * Registers project-related API commands.
 *
 * @param project The current IntelliJ project
 * @param registry The command registry to register commands with
 */
fun registerProjectAPICommands(project: Project, registry: CommandRegistry) {
    registry.registerCommand(
        object : ICommand {
            override fun getId(): String = "vscode.openFolder"
            override fun getMethod(): String = "openFolder"
            override fun handler(): Any = ProjectCommands(project)
            override fun returns(): String = "void"
        }
    )
}

/**
 * Handles project-related commands such as opening or switching folders/projects.
 */
class ProjectCommands(val project: Project) {
    private val logger = Logger.getInstance(ProjectCommands::class.java)

    /**
     * Opens a folder or project.
     * Corresponds to vscode.openFolder.
     *
     * @param uri Map containing URI components
     * @param options Optional map containing options like forceNewWindow
     */
    fun openFolder(uri: Map<String, Any?>, options: Map<String, Any?>?) {
        try {
            val ktUri = createURI(uri)
            if (ktUri.scheme != "file") {
                logger.warn("openFolder: Only 'file' scheme is supported, got '${ktUri.scheme}'")
                return
            }

            val fsPath = ktUri.fsPath
            val forceNewWindow = options?.get("forceNewWindow") as? Boolean ?: false
            val reuseWindow = !forceNewWindow

            logger.info("openFolder: path=$fsPath, forceNewWindow=$forceNewWindow, reuseWindow=$reuseWindow")

            // Use a pooled thread to introduce a delay before project closing.
            // This ensures the RPC response is sent back to Node.js before the extension host is killed.
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // 200ms is usually enough for the RPC response to be flushed to the socket.
                    Thread.sleep(200)
                    
                    ApplicationManager.getApplication().invokeLater {
                        // ProjectUtil.openOrImport is the standard way to open projects.
                        // When reuseWindow is true, it attempts to open in the current window.
                        ProjectUtil.openOrImport(Paths.get(fsPath), project, reuseWindow)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to open project: $fsPath", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to execute openFolder", e)
        }
    }
}
