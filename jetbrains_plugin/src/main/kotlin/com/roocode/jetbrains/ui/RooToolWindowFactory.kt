// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.jcef.JBCefApp
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.ide.BrowserUtil
import com.intellij.util.ui.UIUtil
import com.roocode.jetbrains.actions.OpenDevToolsAction
import com.roocode.jetbrains.plugin.WecoderPlugin
import com.roocode.jetbrains.plugin.WecoderPluginService
import com.roocode.jetbrains.plugin.DEBUG_MODE
import com.roocode.jetbrains.webview.DragDropHandler
import com.roocode.jetbrains.webview.WebViewCreationCallback
import com.roocode.jetbrains.webview.WebViewInstance
import com.roocode.jetbrains.webview.WebViewManager
import com.roocode.jetbrains.util.PluginConstants
import com.roocode.jetbrains.util.RooCodeBundle
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import java.awt.Dimension
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.border.EmptyBorder

class RooToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Apply a borderless look and feel to the tool window component itself
        toolWindow.component.border = EmptyBorder(0, 0, 0, 0)
        toolWindow.component.background = UIUtil.getPanelBackground()

        // Initialize plugin service
        val pluginService = WecoderPlugin.getInstance(project)
        pluginService.initialize(project)

        // toolbar
        val titleActions = mutableListOf<AnAction>()
        val action = ActionManager.getInstance().getAction("WecoderToolbarGroup")
        if (action != null) {
            titleActions.add(action)
        }
        // Add developer tools button only in debug mode
        if ( WecoderPluginService.getDebugMode() != DEBUG_MODE.NONE) {
            titleActions.add(OpenDevToolsAction { project.getService(WebViewManager::class.java).getLatestWebView() })
        }

        toolWindow.setTitleActions(titleActions)

        // webview panel
        val rooToolWindowContent = RooToolWindowContent(project, toolWindow)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(
            rooToolWindowContent.content,
            "",
            false
        )
       toolWindow.contentManager.addContent(content)
   }

   private class RooToolWindowContent(
        private val project: Project,
        private val toolWindow: ToolWindow
    ) : WebViewCreationCallback {
        private val logger = Logger.getInstance(RooToolWindowContent::class.java)

        // Get WebViewManager instance
        private val webViewManager = project.getService(WebViewManager::class.java)

        // Content panel
        private val contentPanel = JPanel(BorderLayout())

        // Placeholder label
        private val placeholderLabel = JLabel(createLoadingText())

        // System info text for copying
        private val systemInfoText = createSystemInfoPlainText()

        // Local WebView instance for this tool window to prevent cross-window interference
        private var myWebView: WebViewInstance? = null

        // Atomic lock to prevent concurrent layout refreshes
        private val isLayoutRefreshing = AtomicBoolean(false)

        private val resizeDebounceTimer: Timer


        /**
         * Create loading text in HTML format with localized message and theme-aware background
         */
        private fun createLoadingText(): String {
            val loadingText = RooCodeBundle.message("toolwindow.initializing.text")
            val bgColor = UIUtil.getPanelBackground()
            val bgHex = String.format("#%02x%02x%02x", bgColor.red, bgColor.green, bgColor.blue)
            val fgColor = UIUtil.getLabelForeground()
            val fgHex = String.format("#%02x%02x%02x", fgColor.red, fgColor.green, fgColor.blue)

            // Use a table-based layout in HTML to ensure robust vertical and horizontal centering.
            return """
                <html>
                  <body style='background-color: $bgHex; color: $fgHex; margin: 0; padding: 0; font-family: sans-serif; height: 100vh; display: table; width: 100%;'>
                    <div style='display: table-cell; vertical-align: middle; text-align: center;'>
                      $loadingText
                    </div>
                  </body>
                </html>
            """.trimIndent()
        }

        /**
         * Create system information text in HTML format
         */
        private fun createSystemInfoText(): String {
            val appInfo = ApplicationInfo.getInstance()
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(PluginConstants.PLUGIN_ID))
            val pluginVersion = plugin?.version ?: "unknown"
            val osName = System.getProperty("os.name")
            val osVersion = System.getProperty("os.version")
            val osArch = System.getProperty("os.arch")
            val jcefSupported = JBCefApp.isSupported()

            // Check for Linux ARM system
            val isLinuxArm = osName.lowercase().contains("linux") && (osArch.lowercase().contains("aarch64") || osArch.lowercase().contains("arm"))

            return buildString {
                append("<html><body style='width: 300px;'>")
                append("<p>RooCode is initializing...")
                append("<h3>System Information</h3>")
                append("<table>")
                append("<tr><td><b>CPU Architecture:</b></td><td>$osArch</td></tr>")
                append("<tr><td><b>Operating System:</b></td><td>$osName $osVersion</td></tr>")
                append("<tr><td><b>IDE Version:</b></td><td>${appInfo.fullApplicationName} (build ${appInfo.build})</td></tr>")
                append("<tr><td><b>Plugin Version:</b></td><td>$pluginVersion</td></tr>")
                append("<tr><td><b>JCEF Support:</b></td><td>${if (jcefSupported) "Yes" else "No"}</td></tr>")
                append("</table>")

                // Add warning messages
                append("<br>")
                if (isLinuxArm) {
                    append("<div style='background-color: #fff3cd; border: 1px solid #ffeaa7; padding: 10px; border-radius: 4px; color: #856404;'>")
                    append("<b>⚠️ System Not Supported</b><br>")
                    append("Linux ARM systems are not currently supported by this plugin.")
                    append("</div>")
                    append("<br>")
                }

                if (!jcefSupported) {
                    append("<div style='background-color: #f8d7da; border: 1px solid #f5c6cb; padding: 10px; border-radius: 4px; color: #721c24;'>")
                    append("<b>⚠️ JCEF Not Supported</b><br>")
                    append("Your IDE runtime does not support JCEF. Please use a JCEF-enabled runtime.<br>")
                    append("See known issues doc for more information.")
                    append("</div>")
                    append("<br>")
                }

                // Add Known Issues text without link
                append("<div style='text-align: center; margin-top: 10px;'>")
                append("If this interface persists for a long time, you can refer to the ")
                append(" known issues documentation to check if there are any known problems.")
                append("</div>")

                append("</body></html>")
            }
        }

        /**
         * Create system information text in plain text format for copying
         */
        private fun createSystemInfoPlainText(): String {
            val appInfo = ApplicationInfo.getInstance()
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(PluginConstants.PLUGIN_ID))
            val pluginVersion = plugin?.version ?: "unknown"
            val osName = System.getProperty("os.name")
            val osVersion = System.getProperty("os.version")
            val osArch = System.getProperty("os.arch")
            val jcefSupported = JBCefApp.isSupported()

            // Check for Linux ARM system
            val isLinuxArm = osName.lowercase().contains("linux") && (osArch.lowercase().contains("aarch64") || osArch.lowercase().contains("arm"))

            return buildString {
                append("System Information\n")
                append("==================\n")
                append("CPU Architecture: $osArch\n")
                append("Operating System: $osName $osVersion\n")
                append("IDE Version: ${appInfo.fullApplicationName} (build ${appInfo.build})\n")
                append("Plugin Version: $pluginVersion\n")
                append("JCEF Support: ${if (jcefSupported) "Yes" else "No"}\n")

                // Add warning messages
                append("\n")
                if (isLinuxArm) {
                    append("WARNING: System Not Supported\n")
                    append("Linux ARM systems are not currently supported by this plugin.\n")
                    append("\n")
                }

                if (!jcefSupported) {
                    append("WARNING: JCEF Not Supported\n")
                    append("Your IDE runtime does not support JCEF. Please use a JCEF-enabled runtime.\n")
                    append("See Known Issues for more information\n")
                    append("\n")
                }

            }
        }

        /**
         * Copy system information to clipboard
         */
        private fun copySystemInfo() {
            val stringSelection = StringSelection(systemInfoText)
            val clipboard = Toolkit.getDefaultToolkit().getSystemClipboard()
            clipboard.setContents(stringSelection, null)
        }

        // Known Issues button
        private val knownIssuesButton = JButton("Known Issues").apply {
            preferredSize = Dimension(150, 30)
            addActionListener {
                BrowserUtil.browse("https://github.com/wecode-ai/RooCode/blob/main/docs/KNOWN_ISSUES.md")
            }
        }

        // Copy button
        private val copyButton = JButton("Copy System Info").apply {
            preferredSize = Dimension(150, 30)
            addActionListener { copySystemInfo() }
        }

        // Button panel to hold both buttons side by side
        private val buttonPanel = JPanel().apply {
            layout = BorderLayout()
            add(knownIssuesButton, BorderLayout.WEST)
            add(copyButton, BorderLayout.EAST)
        }

        private var dragDropHandler: DragDropHandler? = null

        // Main panel
        val content: JPanel = contentPanel.apply {
            // Set background color to match theme and prevent white flash
            background = UIUtil.getPanelBackground()

            // Set placeholder alignment
            placeholderLabel.horizontalAlignment = javax.swing.SwingConstants.CENTER
            placeholderLabel.verticalAlignment = javax.swing.SwingConstants.CENTER

            add(placeholderLabel, BorderLayout.CENTER)
        }

        init {
            // Try to get existing WebView
            webViewManager.getLatestWebView()?.let { webView ->
                // Bind the WebView instance locally
                myWebView = webView

                // Add WebView component immediately when created
                ApplicationManager.getApplication().invokeLater {
                    addWebViewComponent(webView)
                }
                // Set page load callback to hide system info only after page is loaded
                webView.setPageLoadCallback {
                    ApplicationManager.getApplication().invokeLater {
                        hideSystemInfo()
                    }
                }
                // If page is already loaded, hide system info immediately
                if (webView.isPageLoaded()) {
                    ApplicationManager.getApplication().invokeLater {
                        hideSystemInfo()
                    }
                }
            }?:webViewManager.addCreationCallback(this, toolWindow.disposable)

            // Subscribe to Look and Feel changes to update the native panel background instantly.
            project.messageBus.connect(toolWindow.disposable).subscribe(LafManagerListener.TOPIC, LafManagerListener {
                ApplicationManager.getApplication().invokeLater {
                    val newBgColor = UIUtil.getPanelBackground()
                    if (contentPanel.background != newBgColor) {
                        contentPanel.background = newBgColor
                        // Also update the parent tool window component's background
                        toolWindow.component.background = newBgColor
                        contentPanel.revalidate()
                        contentPanel.repaint()
                        logger.debug("Native panel and tool window background updated on Look and Feel change.")
                    }
                }
            })

            // Initialize a debounce timer to handle component resizing.
            // This prevents overwhelming the system with frequent layout refreshes during window drags,
            // especially in low-performance environments like VMs.
            resizeDebounceTimer = Timer(100, ActionListener {
                forceWebViewRelayout()
            }).apply {
                isRepeats = false
            }
            Disposer.register(toolWindow.disposable, Disposable {
                if (resizeDebounceTimer.isRunning) {
                    resizeDebounceTimer.stop()
                }
            })

            // Add a component listener that restarts the debounce timer on each resize event.
            contentPanel.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    // Instantly request a repaint to make resizing feel responsive.
                    // This ensures the container resizes smoothly without waiting for the debounce timer.
                    contentPanel.revalidate()
                    contentPanel.repaint()
                    // Then, restart the debounce timer to trigger the more expensive JS-based layout fix
                    // once the resizing has stopped.
                    resizeDebounceTimer.restart()
                }
            })

            // Add HierarchyListener to detect when the component becomes showing (e.g. after sleep/wake or tab switch)
            // This is critical for fixing the UI shrinking issue after macOS sleep.
            contentPanel.addHierarchyListener { e ->
                if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                    if (contentPanel.isShowing) {
                        logger.debug("Component is now showing, triggering a full state restoration.")
                        restoreWebViewStateOnShow()
                    }
                }
            }
        }

        /**
         * Executes a lightweight JavaScript snippet to only force the WebView's layout to fill its container.
         * This is called frequently during window resizing.
         */
        private fun forceWebViewRelayout() {
            val webView = myWebView ?: return

            // Only execute layout fix for Windowed mode.
            // In OSR mode, JCEF handles layout automatically via Swing painting.
            // Forcing CSS width/height in OSR mode can conflict with internal sizing logic and cause performance issues.
            if (webView.isOSR) {
                logger.debug("Skipping forceWebViewRelayout for OSR mode: ${webView.viewId}")
                return
            }

            val script = """
                (function() {
                    if (document.head) {
                        const styleId = 'roocode-dynamic-layout-fix';
                        let style = document.getElementById(styleId);
                        if (!style) {
                            style = document.createElement('style');
                            style.id = styleId;
                            style.type = 'text/css';
                            document.head.appendChild(style);
                        }
                        style.innerHTML = 'html, body, #root { width: 100vw; height: 100vh; overflow: hidden; margin: 0; padding: 0; position: absolute; top: 0; left: 0; }';
                    }
                })();
            """.trimIndent()
            webView.executeJavaScript(script)
        }

        /**
         * Restores the full visual state of the WebView, including layout and zoom.
         * This is a more comprehensive (and slightly heavier) operation, intended to be called
         * less frequently, such as after system sleep/wake.
         *
         * Includes robust protection against event storms and race conditions:
         * 1. Atomic lock (isLayoutRefreshing) to prevent concurrent execution.
         * 2. try-finally block to ensure lock release.
         */
        private fun restoreWebViewStateOnShow() {
            // Try to acquire the lock. If failed, it means another refresh is in progress.
            if (!isLayoutRefreshing.compareAndSet(false, true)) {
                return
            }

            try {
                val webView = myWebView ?: return
                
                logger.debug("Restoring WebView state. viewId=${webView.viewId}, isOSR=${webView.isOSR}")

                // Sync zoom level, as it might have changed (e.g., monitor change during sleep).
                // Only sync zoom level if not in OSR mode, as OSR mode inherits Swing scaling automatically.
                if (!webView.isOSR) {
                    webView.syncZoomLevel()
                } else {
                    logger.debug("Skipping syncZoomLevel for OSR mode")
                }

                // Also force the layout to ensure it's correct.
                forceWebViewRelayout()

                logger.debug("WebView state restored (zoom and layout).")
            } catch (e: Exception) {
                logger.info("Failed to restore WebView state", e)
            } finally {
                // Always release the lock
                isLayoutRefreshing.set(false)
            }
        }

        /**
         * WebView creation callback implementation
         */
        override fun onWebViewCreated(instance: WebViewInstance) {
            // Bind the WebView instance locally when created via callback
            myWebView = instance

            // Add WebView component immediately when created
            ApplicationManager.getApplication().invokeLater {
                addWebViewComponent(instance)
            }
            // Set page load callback to hide system info only after page is loaded
            instance.setPageLoadCallback {
                // Ensure UI update in EDT thread
                ApplicationManager.getApplication().invokeLater {
                    hideSystemInfo()
                }
            }
        }

        /**
         * Add WebView component to UI
         */
        private fun addWebViewComponent(webView: WebViewInstance) {
            logger.debug("Adding WebView component to UI: ${webView.viewType}/${webView.viewId}, isOSR=${webView.isOSR}")

            // Check if WebView component is already added
            val components = contentPanel.components
            for (component in components) {
                if (component === webView.browser.component) {
                    logger.debug("WebView component already exists in UI")
                    return
                }
            }

            // Add WebView component without removing existing components
            contentPanel.add(webView.browser.component, BorderLayout.CENTER)

            setupDragAndDropSupport(webView)

            // Relayout
            contentPanel.revalidate()
            contentPanel.repaint()

            logger.debug("WebView component added to tool window")
        }

        /**
         * Hide system info placeholder
         */
        private fun hideSystemInfo() {
            logger.debug("Hiding system info placeholder")

            // Remove all components from content panel except WebView component
            val components = contentPanel.components
            for (component in components) {
                if (component !== webViewManager.getLatestWebView()?.browser?.component) {
                    contentPanel.remove(component)
                }
            }

            // Relayout
            contentPanel.revalidate()
            contentPanel.repaint()

            logger.debug("System info placeholder hidden")
        }

        /**
         * Setup drag and drop support
         */
        private fun setupDragAndDropSupport(webView: WebViewInstance) {
            try {
                logger.debug("Setting up drag and drop support for WebView")

                dragDropHandler = DragDropHandler(webView, contentPanel)

                dragDropHandler?.setupDragAndDrop()

                logger.debug("Drag and drop support enabled")
            } catch (e: Exception) {
                logger.error("Failed to setup drag and drop support", e)
            }
        }
    }
}
