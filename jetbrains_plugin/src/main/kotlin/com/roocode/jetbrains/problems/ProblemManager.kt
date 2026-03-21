package com.roocode.jetbrains.problems

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.messages.MessageBusConnection
import com.roocode.jetbrains.util.URI
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ProblemManager(private val project: Project) : Disposable {
    companion object {
        private val logger = Logger.getInstance(ProblemManager::class.java)
    }

    private var messageBusConnection: MessageBusConnection? = null
    @Volatile private var disposed = false

    fun initialize() {
        logger.debug("ProblemManager initializing...")
        subscribeToEvents()
        logger.debug("ProblemManager initialization complete.")
    }

    private fun subscribeToEvents() {
        messageBusConnection = project.messageBus.connect(this)

        messageBusConnection?.subscribe(
                DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
                object : DaemonCodeAnalyzer.DaemonListener {
                    override fun daemonFinished() {
                        if (disposed || project.isDisposed) return
                        logger.debug("DaemonListener daemonFinished triggered.")
                        publishEditorProblems()
                    }
                }
        )
    }

    fun receiveExternalProblems(problems: Map<URI, List<Problem>>) {
        publishProblems(problems)
    }

    private fun publishEditorProblems() {
        val problems = ConcurrentHashMap<URI, List<Problem>>()
        ApplicationManager.getApplication().runReadAction {
            val openFiles = FileEditorManager.getInstance(project).openFiles
            for (virtualFile in openFiles) {
                if (disposed || project.isDisposed) return@runReadAction
                val document =
                        FileDocumentManager.getInstance().getDocument(virtualFile) ?: continue
                val uri = URI.file(virtualFile.path)
                problems[uri] = fetchEditorProblems(document)
            }
            logger.debug(
                    "Fetched editor problems for ${openFiles.size} open documents."
            )
        }
        publishProblems(problems)
    }

    private fun publishProblems(problems: Map<URI, List<Problem>>) {
        if (disposed || project.isDisposed) return
        val totalProblems = problems.values.flatten().size
        logger.debug(
            "PUBLISH: Publishing ${problems.size} files with $totalProblems total problems to MessageBus."
        )
        project.messageBus.syncPublisher(Topics.PROBLEMS_TOPIC).problemsUpdated(problems)
    }

    private fun fetchEditorProblems(document: Document): List<Problem> {
        val problems = mutableListOf<Problem>()

        val markupModel = com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(document, project, false)
        val highlighters = markupModel.allHighlighters

        for (highlighter in highlighters) {
            val info = com.intellij.codeInsight.daemon.impl.HighlightInfo.fromRangeHighlighter(highlighter) ?: continue
            if (info.severity == HighlightSeverity.ERROR) {
                problems.add(convertHighlightInfoToProblem(info, document))
            }
        }

        return problems
    }

    private fun convertHighlightInfoToProblem(
            info: com.intellij.codeInsight.daemon.impl.HighlightInfo,
            document: Document
    ): Problem {
        val startLine = document.getLineNumber(info.startOffset)
        val startColumn = info.startOffset - document.getLineStartOffset(startLine)
        val endLine = document.getLineNumber(info.endOffset)
        val endColumn = info.endOffset - document.getLineStartOffset(endLine)

        return Problem(
                message = info.description ?: "Unknown problem",
                severity = convertSeverity(info.severity),
                startLineNumber = startLine + 1,
                startColumn = startColumn + 1,
                endLineNumber = endLine + 1,
                endColumn = endColumn + 1
        )
    }

    private fun convertSeverity(severity: HighlightSeverity): Int {
        return when (severity) {
            HighlightSeverity.ERROR -> 8
            HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING -> 4
            else -> 1
        }
    }

    override fun dispose() {
        logger.debug("Disposing ProblemManager...")
        disposed = true
        messageBusConnection?.dispose()
        logger.debug("ProblemManager disposed.")
    }
}
