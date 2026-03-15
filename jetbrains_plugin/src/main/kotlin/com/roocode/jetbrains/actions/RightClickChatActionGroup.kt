// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.roocode.jetbrains.util.RooCodeBundle

/**
 * Base class for all RooCode code actions to reduce boilerplate.
 * It handles common logic like getting project, editor, file, and selected text.
 */
abstract class BaseCodeAction(
    text: String,
    private val command: String,
    private val promptType: String
) : AnAction(text), DumbAware {

    override fun update(e: AnActionEvent) {
        // Actions are visible only when there is a selection in the editor.
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selection = getEffectiveRange(editor) ?: return

        val args = mutableMapOf<String, Any?>(
            "filePath" to file.path,
            "selectedText" to selection.text,
            "startLine" to selection.startLine + 1,
            "endLine" to selection.endLine + 1
        )

        handleCodeAction(command, promptType, args, project)
    }

    private fun getEffectiveRange(editor: Editor): EffectiveRange? {
        val document = editor.document
        val selectionModel = editor.selectionModel

        return if (selectionModel.hasSelection()) {
            val selectedText = selectionModel.selectedText ?: ""
            val startLine = document.getLineNumber(selectionModel.selectionStart)
            val endLine = document.getLineNumber(selectionModel.selectionEnd)
            EffectiveRange(selectedText, startLine, endLine)
        } else {
            null
        }
    }
}

/**
 * Action to explain a selected piece of code.
 */
class ExplainCodeAction : BaseCodeAction(
    RooCodeBundle.message("action.RooCode.ExplainCode.text"),
    CommandIds.EXPLAIN,
    PromptTypes.EXPLAIN
)

/**
 * Action to fix the logic of a selected piece of code.
 */
class FixLogicAction : BaseCodeAction(
    RooCodeBundle.message("action.RooCode.FixLogic.text"),
    CommandIds.FIX,
    PromptTypes.FIX
)

/**
 * Action to improve a selected piece of code.
 */
class ImproveCodeAction : BaseCodeAction(
    RooCodeBundle.message("action.RooCode.ImproveCode.text"),
    CommandIds.IMPROVE,
    PromptTypes.IMPROVE
)

/**
 * Action to add a selected piece of code to the context.
 */
class AddToContextAction : BaseCodeAction(
    RooCodeBundle.message("action.RooCode.AddToContext.text"),
    CommandIds.ADD_TO_CONTEXT,
    PromptTypes.ADD_TO_CONTEXT
)
