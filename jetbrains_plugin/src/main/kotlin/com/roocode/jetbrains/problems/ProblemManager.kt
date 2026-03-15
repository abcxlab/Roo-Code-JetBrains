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

        try {
            // Use reflection or a separate handler class to avoid class loading issues
            // when com.intellij.java plugin is not available (e.g. in PyCharm)
            val handler = CompilerProblemHandler(project, this) { problems ->
                publishProblems(problems)
            }
            handler.subscribe()
            logger.debug("CompilerProblemHandler subscribed successfully.")
        } catch (e: NoClassDefFoundError) {
            logger.info("Compiler API not available, skipping compiler problem subscription. (This is expected in non-Java IDEs)")
        } catch (e: Exception) {
            logger.warn("Failed to subscribe to compiler problems", e)
        }
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
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (psiFile == null) {
            logger.warn("Could not get PSI file for document, skipping problem analysis.")
            return emptyList()
        }

        val highlightInfos = mutableListOf<com.intellij.codeInsight.daemon.impl.HighlightInfo>()

        DaemonCodeAnalyzerImpl.processHighlights(
                document,
                project,
                HighlightSeverity.ERROR, // MODIFIED: Only fetch ERROR level problems
                0,
                document.textLength,
                com.intellij.util.Processor { highlightInfo ->
                    highlightInfos.add(highlightInfo)
                    true
                }
        )

        if (highlightInfos.isNotEmpty()) {
            problems.addAll(
                    highlightInfos.map { info -> convertHighlightInfoToProblem(info, document) }
            )
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
            HighlightSeverity.INFO -> 2
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
