package com.roocode.jetbrains.problems

import com.intellij.openapi.Disposable
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.roocode.jetbrains.util.URI
import java.util.concurrent.ConcurrentHashMap

class CompilerProblemHandler(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val onProblemsUpdated: (Map<URI, List<Problem>>) -> Unit
) {
    companion object {
        private val logger = Logger.getInstance(CompilerProblemHandler::class.java)
    }

    fun subscribe() {
        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(
            CompilerTopics.COMPILATION_STATUS,
            object : CompilationStatusListener {
                override fun compilationFinished(
                    aborted: Boolean,
                    errors: Int,
                    warnings: Int,
                    compileContext: CompileContext
                ) {
                    if (!aborted) {
                        logger.debug(
                            "Compilation finished with $errors errors and $warnings warnings."
                        )
                        publishCompilerProblems(compileContext)
                    }
                }
            }
        )
    }

    private fun publishCompilerProblems(context: CompileContext) {
        logger.debug("Parsing compiler problems...")
        val compilerProblems = ConcurrentHashMap<URI, MutableList<Problem>>()
        val errorMessages = context.getMessages(CompilerMessageCategory.ERROR)

        errorMessages.forEach { message ->
            val virtualFile = message.virtualFile ?: return@forEach
            val uri = URI.file(virtualFile.path)

            val prefix = message.exportTextPrefix
            val match = Regex("\\((\\d+), (\\d+)\\)").find(prefix)
            val line: Int
            val column: Int

            if (match != null && match.groupValues.size == 3) {
                line = match.groupValues[1].toInt()
                column = match.groupValues[2].toInt()
            } else {
                line = 1
                column = 1
            }

            val problem =
                Problem(
                    message = message.message,
                    severity = 8, // ERROR
                    startLineNumber = line,
                    startColumn = column,
                    endLineNumber = line,
                    endColumn = column,
                    source = "compiler"
                )
            compilerProblems.computeIfAbsent(uri) { mutableListOf() }.add(problem)
        }
        logger.debug(
            "Parsed ${compilerProblems.values.flatten().size} compiler errors across ${compilerProblems.keys.size} files."
        )
        onProblemsUpdated(compilerProblems)
    }
}
