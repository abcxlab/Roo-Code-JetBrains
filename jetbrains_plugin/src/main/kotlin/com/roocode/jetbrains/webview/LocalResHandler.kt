// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.webview

import com.intellij.openapi.diagnostic.Logger
import io.ktor.http.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.File


class LocalResHandler(val resourcePath:String , val request: CefRequest?) : CefResourceRequestHandlerAdapter() {

    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler {
        return LocalCefResHandle(resourcePath,request)
    }

}

class LocalCefResHandle(val resourceBasePath: String, val request: CefRequest?) : CefResourceHandler{
    private val logger = Logger.getInstance(LocalCefResHandle::class.java)

    private var file: File? = null
    private var fileContent: ByteArray? = null
    private var offset = 0

    init {
        val requestPath = request?.url?.decodeURLPart()?.replace("http://localhost:","")?.substringAfter("/")?.substringBefore("?")
            requestPath?.let {
                val filePath = if (requestPath.isEmpty()) {
                    "$resourceBasePath/index.html"
                } else {
                    "$resourceBasePath/$requestPath"
                }
                file = File(filePath)

                if (file!!.exists() && file!!.isFile) {
                try {
                    var content = file!!.readBytes()

                    // If it's an HTML file, remove preload links for .map files to avoid performance warnings
                    if (filePath.endsWith(".html", true)) {
                        val htmlContent = String(content, Charsets.UTF_8)
                        // Use a robust regex to match <link> tags with href pointing to map files, including source-map query params
                        val mapLinkRegex = Regex("""<link\s+[^>]*href=["'][^"']*(?:\.map(?=["'?])|\.sourcemap|\.map\.json|source-map=)[^"']*["'][^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

                        val modifiedHtml = htmlContent.replace(mapLinkRegex, "")
                        content = modifiedHtml.toByteArray(Charsets.UTF_8)
                        logger.debug("Removed links for sourcemap files in HTML: $filePath")
                    }

                    // If it's a JS file, remove source map comments to avoid performance warnings
                    if (filePath.endsWith(".js", true)) {
                        val jsContent = String(content, Charsets.UTF_8)
                        // Regex to match source map comments: //# sourceMappingURL=... or //@ sourceMappingURL=...
                        val sourceMapCommentRegex = Regex("""//[#@]\s*sourceMappingURL\s*=\s*.*""", RegexOption.IGNORE_CASE)

                        if (sourceMapCommentRegex.containsMatchIn(jsContent)) {
                            logger.debug("Found source map comment in JS file: $filePath, removing it.")
                            val modifiedJs = jsContent.replace(sourceMapCommentRegex, "")
                            content = modifiedJs.toByteArray(Charsets.UTF_8)
                        }
                    }

                    fileContent = content
                } catch (e: Exception) {
                    logger.warn("cannot get fileContent,e:${e}")
                    file = null
                    fileContent = null
                }
            } else {
                file = null
                fileContent = null
            }
                logger.debug("init LocalCefResHandle,filePath:${filePath},file:${file},exists:${file?.exists()}")
        }
    }


    override fun processRequest(p0: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return true
    }

    /**
     * Get MIME type according to file path
     */
    fun getMimeTypeForFile(filePath: String): String {
        return when {
            filePath.endsWith(".html", true) -> "text/html"
            filePath.endsWith(".css", true) -> "text/css"
            filePath.endsWith(".js", true) -> "application/javascript"
            filePath.endsWith(".json", true) -> "application/json"
            filePath.endsWith(".png", true) -> "image/png"
            filePath.endsWith(".jpg", true) || filePath.endsWith(".jpeg", true) -> "image/jpeg"
            filePath.endsWith(".gif", true) -> "image/gif"
            filePath.endsWith(".svg", true) -> "image/svg+xml"
            filePath.endsWith(".woff", true) -> "font/woff"
            filePath.endsWith(".woff2", true) -> "font/woff2"
            filePath.endsWith(".ttf", true) -> "font/ttf"
            filePath.endsWith(".eot", true) -> "application/vnd.ms-fontobject"
            filePath.endsWith(".otf", true) -> "font/otf"
            else -> "application/octet-stream"
        }
    }

    override fun getResponseHeaders(resp: CefResponse?, p1: IntRef?, p2: StringRef?) {
        if (fileContent == null) {
            resp?.status = 404
            resp?.statusText = "Not Found"
            return
        }

        resp?.status = 200
        resp?.statusText = "OK"
        resp?.mimeType = getMimeTypeForFile(file?.name ?: "index.html")
        resp?.setHeaderByName("Content-Length", fileContent!!.size.toString(), true)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        if (fileContent == null || dataOut == null || bytesRead == null) {
            return false
        }

        val remaining = fileContent!!.size - offset
        if (remaining <= 0) {
            return false
        }

        val readSize = minOf(bytesToRead, remaining)
        System.arraycopy(fileContent, offset, dataOut, 0, readSize)
        offset += readSize
        bytesRead.set(readSize)

        return offset <= fileContent!!.size
    }

    override fun cancel() {
        file = null
        fileContent = null
        offset = 0
    }

}
