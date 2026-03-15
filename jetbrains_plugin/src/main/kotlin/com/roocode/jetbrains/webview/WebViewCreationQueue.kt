package com.roocode.jetbrains.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global queue for serializing WebView creation requests to avoid JCEF concurrency issues.
 * This is an Application-level service.
 */
@Service(Service.Level.APP)
class WebViewCreationQueue : Disposable {
    private val logger = Logger.getInstance(WebViewCreationQueue::class.java)
    private val creationQueue = ConcurrentLinkedQueue<QueueItem>()
    private val isProcessing = AtomicBoolean(false)
    
    // Use a single thread executor to ensure serial execution
    private val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private data class QueueItem(val project: Project, val action: () -> Unit)

    /**
     * Queue a WebView creation action.
     * The action will be executed serially in a single thread.
     * 
     * @param project The project associated with the WebView
     * @param action The creation logic (e.g., creating JBCefBrowser)
     */
    fun queueCreation(project: Project, action: () -> Unit) {
        logger.debug("Queueing WebView creation action for project: ${project.name}")
        creationQueue.offer(QueueItem(project, action))
        processQueue()
    }

    private fun processQueue() {
        // Ensure only one thread is processing the queue at a time
        if (isProcessing.compareAndSet(false, true)) {
            singleThreadExecutor.submit {
                try {
                    processNextItem()
                } catch (e: Exception) {
                    logger.error("Error processing WebView creation queue", e)
                    isProcessing.set(false)
                    // Try to recover/continue processing remaining items
                    if (!creationQueue.isEmpty()) {
                        processQueue()
                    }
                }
            }
        }
    }

    private fun processNextItem() {
        val item = creationQueue.poll()
        
        if (item == null) {
            isProcessing.set(false)
            return
        }
        
        val (project, action) = item

        if (project.isDisposed) {
            logger.debug("Project ${project.name} was disposed, skipping creation action")
            // Process next item immediately
            processNextItem()
            return
        }

        // Execute the creation action
        // Note: The action itself is responsible for switching to EDT if needed (e.g. for UI operations)
        // But usually JBCefBrowser creation might need to happen on specific threads or just serially.
        // Here we just ensure serial execution.
        try {
            logger.debug("Executing WebView creation action for project: ${project.name}")
            
            // We wrap the action execution in invokeAndWait to ensure it completes before moving to next
            // But wait, if the action does invokeLater internally, invokeAndWait returns immediately?
            // No, invokeAndWait waits for the runnable to finish.
            
            // However, WebViewManager.registerProvider does UI operations.
            // So we should run the action on EDT, but wait for it to finish before processing next item.
            ApplicationManager.getApplication().invokeAndWait {
                if (!project.isDisposed) {
                    try {
                        action()
                    } catch (e: Exception) {
                        logger.error("Error executing creation action", e)
                    }
                }
            }
            
        } catch (e: Exception) {
            logger.error("Error during serial execution", e)
        } finally {
            // Release lock and trigger next item
            isProcessing.set(false)
            processQueue()
        }
    }

    override fun dispose() {
        logger.debug("Disposing WebViewCreationQueue, shutting down executor")
        singleThreadExecutor.shutdownNow()
    }
}