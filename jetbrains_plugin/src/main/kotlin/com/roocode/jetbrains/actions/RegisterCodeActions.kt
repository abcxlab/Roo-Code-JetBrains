// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.roocode.jetbrains.webview.WebViewManager

/**
 * Data class representing an effective range of selected text.
 * Contains the selected text and its start/end line numbers.
 *
 * @property text The selected text content
 * @property startLine The starting line number (0-based)
 * @property endLine The ending line number (0-based)
 */
data class EffectiveRange(
    val text: String,
    val startLine: Int,
    val endLine: Int
)

/**
 * Registers a code action with the specified parameters.
 *
 * @param command The command identifier
 * @param promptType The type of prompt to use
 * @param inputPrompt Optional prompt text for user input dialog
 * @param inputPlaceholder Optional placeholder text for input field
 * @return An AnAction instance that can be registered with the IDE
 */
fun registerCodeAction(
    command: String,
    promptType: String,
    inputPrompt: String? = null,
    inputPlaceholder: String? = null
) : AnAction {
    return object : AnAction(command) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return

            var userInput: String? = null
            if (inputPrompt != null) {
                userInput = Messages.showInputDialog(
                    project,
                    inputPrompt,
                    "RooCode",
                    null,
                    inputPlaceholder,
                    null
                )
                if (userInput == null) return // Cancelled
            }

            // Get selected content, line numbers, etc.
            val document = editor.document
            val selectionModel = editor.selectionModel
            val selectedText = selectionModel.selectedText ?: ""
            val startLine = if (selectionModel.hasSelection()) document.getLineNumber(selectionModel.selectionStart) else null
            val endLine = if (selectionModel.hasSelection()) document.getLineNumber(selectionModel.selectionEnd) else null
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val filePath = file?.path ?: ""

            val params = mutableMapOf<String, Any?>(
                "filePath" to filePath,
                "selectedText" to selectedText
            )
            if (startLine != null) params["startLine"] = (startLine + 1).toString()
            if (endLine != null) params["endLine"] = (endLine + 1).toString()
            if (!userInput.isNullOrEmpty()) params["userInput"] = userInput

            handleCodeAction(command, promptType, params, e.project)
        }
    }
}
/**
 * Registers a pair of code actions with the specified parameters.
 *
 * @param baseCommand The base command identifier
 * @param inputPrompt Optional prompt text for user input dialog
 * @param inputPlaceholder Optional placeholder text for input field
 * @return An AnAction instance for the new task version
 */
fun registerCodeActionPair(
    baseCommand: String,
    inputPrompt: String? = null,
    inputPlaceholder: String? = null
) : AnAction {
    // New task version
    return registerCodeAction(baseCommand, baseCommand, inputPrompt, inputPlaceholder)
}

/**
 * Core logic for handling code actions.
 * Processes different types of commands and sends appropriate messages to the webview.
 *
 * @param command The command identifier
 * @param promptType The type of prompt to use
 * @param params Parameters for the action (can be Map or List)
 * @param project The current project
 */
fun handleCodeAction(command: String, promptType: String, params: Any, project: Project?) {
    val latestWebView = project?.getService(WebViewManager::class.java)?.getLatestWebView()
    if (latestWebView == null) {
        return
    }

    val promptParams = if (params is Map<*, *>) params as Map<String, Any?> else emptyMap()

    val messageContent = if (promptType == PromptTypes.ADD_TO_CONTEXT) {
        mapOf(
            "type" to "invoke",
            "invoke" to "setChatBoxMessage",
            "text" to SupportPrompt.create(promptType, promptParams)
        )
    } else {
        // For all other actions, we send a message to the current task.
        mapOf(
            "type" to "invoke",
            "invoke" to "sendMessage",
            "text" to SupportPrompt.create(promptType, promptParams)
        )
    }

    // Convert to JSON and send
    val messageJson = com.google.gson.Gson().toJson(messageContent)
    latestWebView.postMessageToWebView(messageJson)
}
