// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.roocode.jetbrains.editor

import com.intellij.diff.DiffContentFactory
import java.util.concurrent.ConcurrentHashMap
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.FileDocumentContentImpl
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffRequestProcessorEditor
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diff.DiffBundle
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.roocode.jetbrains.util.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import kotlin.math.max

@Service(Service.Level.PROJECT)
class EditorAndDocManager(val project: Project) : Disposable {

    private val logger = Logger.getInstance(EditorAndDocManager::class.java)
    private val ideaEditorListener : FileEditorManagerListener

    private val messageBusConnection = project.messageBus.connect()

    private var state = DocumentsAndEditorsState()
    private var lastNotifiedState = DocumentsAndEditorsState()
    private var editorHandles = ConcurrentHashMap<String, EditorHolder>()
    private val ideaOpenedEditor = ConcurrentHashMap<String, Editor>()
    private var tabManager : TabStateManager = TabStateManager(project)

    private val updateChannel = Channel<Unit>(Channel.CONFLATED)
    private val editorStateService:EditorStateService = EditorStateService(project)
    private val stateLock = Any()

    init {
        // Start the update actor
        CoroutineScope(Dispatchers.IO).launch {
            for (item in updateChannel) {
                try {
                    delay(10) // Debounce
                    processUpdates()
                } catch (e: Exception) {
                    logger.error("Error in update actor", e)
                }
            }
        }

        ideaEditorListener = object : FileEditorManagerListener {
            // Update and synchronize editor state when file is opened
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {

                source.getEditorList(file).forEach {editor->
                    if(file == editor.file){
                        // Record and synchronize
                        if (isSubClassof(editor, "com.intellij.diff.editor.DiffEditorBase") || isSubClassof(editor, "com.intellij.diff.editor.DiffFileEditorBase")){
                            if(editor.filesToRefresh.size == 1){
                                val reffile = editor.filesToRefresh[0]
                                val uri = URI.file(reffile.path)
                                val older = getEditorHandleByUri(uri,true)
                                if(older != null &&  older.ideaEditor == null){
                                    older.ideaEditor = editor
                                }
                            }
                        }else{
                            val older = getEditorHandleByUri(URI.file(file.path),false)
                            if(older == null){
                                val uri = URI.file(editor.file.path)
                                val isText = FileDocumentManager.getInstance().getDocument(file) != null
                                CoroutineScope(Dispatchers.IO).launch {
                                    val handle = sync2ExtHost(uri, false,isText)
                                    handle.ideaEditor = editor
                                    val group = getOrCreateMainGroup()
                                    handle.mutex.withLock {
                                        if (handle.tab == null) {
                                            val options = TabOptions(isActive = true)
                                            val tab = group.addTab(EditorTabInput(uri, uri.path, ""), options)
                                            handle.tab = tab
                                            handle.group = group
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }

            private fun isSubClassof(editor: FileEditor?, s: String): Boolean {
                if (editor == null) return false
                var clazz: Class<*>? = editor.javaClass
                while (clazz != null) {
                    if (clazz.name == s) {
                        return true
                    }
                    clazz = clazz.superclass
                }
                return false
            }

            override fun fileClosed(source: FileEditorManager, cFile: VirtualFile) {
                logger.debug("file closed $cFile")
                var diff = false
                var path = cFile.path
                if(cFile is ChainDiffVirtualFile){
                    (cFile.chain.requests[0] as? SimpleDiffRequest).let {
                        it?.contents?.forEach{ content->
                            if( content is FileDocumentContentImpl){
                                path = content.file.path
                                diff = true
                            }
                        }
                    }
                }
                getEditorHandleByUri(URI.file(path),diff)?.let { handle ->
                    handle.setActive(false)
                    logger.debug("file closed handle $handle")
                    removeEditor(handle.id)
                }
            }
        }
        messageBusConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, ideaEditorListener)
    }


    fun initCurrentIdeaEditor(){
        CoroutineScope(Dispatchers.Default).launch {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val allEditors = fileEditorManager.allEditors
            val selectedEditor = fileEditorManager.selectedEditor

            // Create a single tab group for the main editor window
            val group = getOrCreateMainGroup()
            
            val BATCH_SIZE = 10
            var count = 0
            
            allEditors.forEach {editor->
                // Record and synchronize
                if (editor is FileEditor) {
                    val uri = URI.file(editor.file.path)
                    val handle = sync2ExtHost(uri,false, processUpdate = false)
                    handle.ideaEditor = editor
                    
                    // Only the selected editor should be active
                    val isActive = (editor == selectedEditor)
                    handle.mutex.withLock {
                        if (handle.tab == null) {
                            val options = TabOptions(isActive = isActive)
                            val tab = group.addTab(EditorTabInput(uri, uri.path, ""), options)
                            handle.tab = tab
                            handle.group = group
                        }
                    }
                    
                    count++
                    if (count % BATCH_SIZE == 0) {
                        processUpdates()
                        delay(50) // Give Extension Host some time to breathe
                    }
                }
            }
            // Send remaining updates
            processUpdates()
        }
    }

    suspend fun sync2ExtHost(
        documentUri: URI,
        diff: Boolean,
        isText: Boolean = true,
        options: ResolvedTextEditorConfiguration = ResolvedTextEditorConfiguration(),
        processUpdate: Boolean = true
    ): EditorHolder {
        // [Step 1: Check outside lock] Optimization to avoid unnecessary lock contention
        var eh = getEditorHandleByUri(documentUri, diff)
        if (eh != null) return eh

        // [Step 2: IO outside lock] Prepare document data (reading file content is time-consuming IO, must be done outside lock)
        val documentData = prepareDocumentData(documentUri, isText)

        // [Step 3: Atomic update inside lock] Acquire lock, enter critical section
        val handle = synchronized(stateLock) {
            // Double Check: Check again if it was created by another thread while waiting for the lock
            eh = getEditorHandleByUri(documentUri, diff)
            if (eh != null) return@synchronized eh!!

            // Register document (pure memory operation)
            registerDocument(documentUri, documentData)

            // Create editor handle
            val id = java.util.UUID.randomUUID().toString()
            val editorState = TextEditorAddData(
                id = id,
                documentUri = documentUri,
                options = options,
                selections = emptyList(),
                visibleRanges = emptyList(),
                editorPosition = null
            )
            // Create editor handle
            val newHandle = EditorHolder(id, editorState, documentData, diff, this)
            // Update state
            state.editors[id] = editorState
            editorHandles[id] = newHandle
            newHandle
        }

        // [Step 4: Post-operation outside lock]
        handle.setActive(true)

        // Decide whether to send notification immediately based on parameter (IO operation)
        if (processUpdate) {
            processUpdates()
        }
        return handle
    }

    fun createContent(uri: URI, project: Project,type: FileType?=null) : DiffContent?{
        val path = uri.path
        val scheme = uri.scheme
        val query = uri.query
        if(scheme != null && scheme.isNotEmpty()){
            val contentFactory = DiffContentFactory.getInstance()
            if(scheme == "file"){
                val vfs = LocalFileSystem.getInstance()
                val fileIO = File(path)
                if(!fileIO.exists()){
                    fileIO.createNewFile()
                    vfs.refreshIoFiles(listOf(fileIO.parentFile))
                }
                val file = vfs.refreshAndFindFileByPath(path) ?: run {
                    logger.warn("File not found: $path")
                    return null
                }
                return contentFactory.create(project, file)
            }else if(scheme == "cline-diff"){
                val string = if(query != null){
                    val bytes = java.util.Base64.getDecoder().decode(query)
                    String(bytes)
                }else ""
                val content = contentFactory.create(project, string,type)
                return content
            }
            return null
        }else{
            return null
        }
    }


    suspend fun openEditor(documentUri: URI ,options: ResolvedTextEditorConfiguration = ResolvedTextEditorConfiguration()): EditorHolder {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val path = documentUri.path
        var ideaEditor : Array<FileEditor?>? = null

        val vfs = LocalFileSystem.getInstance()
        val file = vfs.findFileByPath(path)
        file?.let {
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    ideaEditor = fileEditorManager.openFile(it, true)
                } catch (e: Throwable) {
                    logger.warn("Failed to open file editor for $path, possibly due to a JCEF issue in a preview panel. Continuing without editor reference.", e)
                    ideaEditor = emptyArray() // Ensure ideaEditor is not null
                }
            }
        }
        val eh = getEditorHandleByUri(documentUri,false)
        if (eh != null)
            return eh
        val handle = sync2ExtHost(documentUri, false, true, options)
        ideaEditor?.let {
            if(it.isNotEmpty()){
                handle.ideaEditor = it[0]
            }
        }
        val group = getOrCreateMainGroup()
        handle.mutex.withLock {
            if (handle.tab == null) {
                val options = TabOptions(isActive = true)
                val tab = group.addTab(EditorTabInput(documentUri, documentUri.path, ""), options)
                handle.tab = tab
                handle.group = group
            }
        }
        return handle
    }

    suspend fun openDiffEditor(left: URI,documentUri: URI,title:String, options: ResolvedTextEditorConfiguration = ResolvedTextEditorConfiguration()): EditorHolder {
        val content2 = createContent(documentUri, project)
        val content1 = createContent(left, project,content2?.contentType)
        if (content1 != null && content2 != null){
            val request = SimpleDiffRequest(title, content1, content2, left.path, documentUri.path)
            var ideaEditor : Array<out FileEditor?>? = null
            ApplicationManager.getApplication().invokeAndWait{
                try {
                    LocalFileSystem.getInstance().findFileByPath(documentUri.path)
                        ?.let {
                            ApplicationManager.getApplication().runReadAction {  FileEditorManager.getInstance(project).closeFile(it) }
                        }

                    val diffEditorTabFilesManager = DiffEditorTabFilesManager.getInstance(project)
                    val requestChain: DiffRequestChain = SimpleDiffRequestChain(request)
                    val diffFile = ChainDiffVirtualFile(requestChain, DiffBundle.message("label.default.diff.editor.tab.name", *arrayOfNulls<Any>(0)))
                    ideaEditor = diffEditorTabFilesManager.showDiffFile(diffFile, true)
                } catch (e: Throwable) {
                    logger.warn("Failed to open diff editor", e)
                }
            }
            val handle = sync2ExtHost(documentUri, true, true, options)
            ideaEditor?.let {
                if(it.isNotEmpty()){
                    handle.ideaEditor = it[0]
                }
                handle.title = title

                val group = getOrCreateMainGroup()
                handle.mutex.withLock {
                    if (handle.tab == null) {
                        val options = TabOptions(isActive = true)
                        val tab = group.addTab(TextDiffTabInput(left, documentUri), options)
                        handle.tab = tab
                        handle.group = group
                    }
                }
            }
            return handle
        }else{
            val handle = sync2ExtHost(documentUri, true, true, options)
            return handle
        }
    }

    fun getEditorHandleByUri(resource: URI,diff: Boolean): EditorHolder? {
        val values = editorHandles.values
        for (handle in values){
            if (handle.document.uri.path == resource.path && handle.diff == diff) {
                return handle
            }
        }
        return null
    }

    fun getEditorHandleByUri(resource: URI): List<EditorHolder> {
        val list = mutableListOf<EditorHolder>()
        val values = editorHandles.values
        for (handle in values){
            if (handle.document.uri.path == resource.path ) {
                list.add(handle)
            }
        }
        return list
    }


    fun getEditorHandleById(id: String): EditorHolder? {
        return editorHandles[id]
    }

    suspend fun openDocument(uri: URI, isText: Boolean = true, processUpdate: Boolean = true): ModelAddedData {
        // 1. IO outside lock
        val documentData = prepareDocumentData(uri, isText)

        // 2. Atomic update inside lock
        val shouldNotify = synchronized(stateLock) {
            if (state.documents[uri] == null) {
                registerDocument(uri, documentData)
                true
            } else {
                false
            }
        }

        // 3. Notify outside lock
        if (shouldNotify && processUpdate) {
            processUpdates()
        }

        return synchronized(stateLock) {
            state.documents[uri]!!
        }
    }

    private suspend fun prepareDocumentData(uri: URI, isText: Boolean): ModelAddedData {
        return ApplicationManager.getApplication().runReadAction<ModelAddedData> {
            val text = if (isText) {
                val vfs = LocalFileSystem.getInstance()
                val file = vfs.findFileByPath(uri.path)
                if (file != null) {
                    val len = file.length
                    if (len > 3 * 1024 * 1024) {
                        val buffer = ByteArray(3 * 1024 * 1024)
                        val inputStream = FileInputStream(File(file.path))
                        val bytesRead = inputStream.read(buffer)
                        inputStream.close()
                        String(buffer, 0, bytesRead, Charsets.UTF_8)
                    } else {
                        file.readText()
                    }
                } else {
                    ""
                }
            } else {
                "bin"
            }

            ModelAddedData(
                uri = uri,
                versionId = 1,
                lines = text.lines(),
                EOL = "\n",
                languageId = "",
                isDirty = false,
                encoding = "utf8"
            )
        }
    }

    private fun registerDocument(uri: URI, document: ModelAddedData) {
        val existing = state.documents[uri]
        if (existing == null || existing.lines != document.lines) {
            state.documents[uri] = document
        }
    }

    fun removeEditor(id: String) {
        val handler = synchronized(stateLock) {
            state.editors.remove(id)
            val handler = editorHandles.remove(id)
            var needDeleteDoc = true
            val values = editorHandles.values
            values.forEach { value ->
                if (value.document.uri == handler?.document?.uri) {
                    needDeleteDoc = false
                }
            }
            if (needDeleteDoc) {
                state.documents.remove(handler?.document?.uri)
            }
            if (state.activeEditorId == id) {
                state.activeEditorId = null
            }
            handler
        }
        scheduleUpdate()

        handler?.tab?.let {
            tabManager.removeTab(it.id)
        }
    }

    //from exthost
    fun closeTab(id: String) {
        val tab = tabManager.removeTab(id)
        tab?.let { tab ->
            val handler = getEditorHandleByTabId(id)
            handler?.let {
                synchronized(stateLock) {
                    state.editors.remove(it.id)
                    editorHandles.remove(it.id)
                    
                    // Only remove document if no other editors are using it
                    val uri = it.document.uri
                    val hasOtherEditors = editorHandles.values.any { handle -> handle.document.uri == uri }
                    if (!hasOtherEditors) {
                        this.state.documents.remove(uri)
                    }
                    
                    if (state.activeEditorId == it.id) {
                        state.activeEditorId = null
                    }
                }
                handler.let { h ->
                    if (h.ideaEditor != null) {
                        ApplicationManager.getApplication().invokeAndWait {
                            h.ideaEditor?.dispose()
                        }
                    } else {
                        ApplicationManager.getApplication().invokeAndWait {
                            FileEditorManager.getInstance(project).allEditors.forEach {
                                if (it is DiffRequestProcessorEditor && handler.diff) {
                                    val differ = it
                                    differ.processor.activeRequest?.let { req ->
                                        for (filesToRefresh in req.filesToRefresh) {
                                            if (filesToRefresh.path == handler.document.uri.path) {
                                                differ.dispose()
                                            }
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
                scheduleUpdate()
            }
        }
    }

    fun closeGroup(id:Int){
        tabManager.removeGroup(id);
    }

    private fun getEditorHandleByTabId(id: String): EditorHolder? {
        for ((_, handle) in editorHandles){
            if (handle.tab != null && handle.tab?.id == id) {
                return handle
            }
        }
        return null
    }

    override fun dispose() {
        messageBusConnection.dispose()
    }

    fun didUpdateActive(handle: EditorHolder) {
        synchronized(stateLock) {
            if (handle.isActive) {
                state.activeEditorId = handle.id
                scheduleUpdate()
            } else if (state.activeEditorId == handle.id) {
                // If the current active editor is set to inactive, select the first active editor
                editorHandles.values.firstOrNull { it.isActive }?.let {
                    state.activeEditorId = it.id
                    scheduleUpdate()
                }
            }
        }
    }

    private fun setActiveEditor(id: String) {
        synchronized(stateLock) {
            state.activeEditorId = id
        }
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        updateChannel.trySend(Unit)
    }
    private fun copy(state: DocumentsAndEditorsState): DocumentsAndEditorsState {
        val rst = DocumentsAndEditorsState(
            editors = ConcurrentHashMap(),
            documents = ConcurrentHashMap(),
            activeEditorId = state.activeEditorId
        )
        // Deep copy to avoid shared mutable state
        state.editors.forEach { (k, v) -> rst.editors[k] = v.copy() }
        state.documents.forEach { (k, v) -> rst.documents[k] = v.copy() }
        return rst
    }
    private suspend fun processUpdates() {
        withContext(NonCancellable) {
            // [Step 1: Snapshot inside lock] Atomically calculate delta and update baseline state
            val delta = synchronized(stateLock) {
                val d = state.delta(lastNotifiedState)
                lastNotifiedState = copy(state) // Update snapshot
                d
            }

            // [Step 2: Send outside lock] Execute RPC calls (IO operation, time-consuming)
            // At this point the lock is released, not blocking other threads from reading or writing state

            // Send document and editor change notifications
            delta.itemsDelta?.let { itemsDelta ->
                itemsDelta.addedDocuments?.forEach { doc -> logger.debug("  [ADDED DOC] ${doc.uri}") }
                itemsDelta.removedDocuments?.forEach { uri -> logger.debug("  [REMOVED DOC] $uri") }
                itemsDelta.addedEditors?.forEach { editor -> logger.debug("  [ADDED EDITOR] ${editor.id} for doc ${editor.documentUri}") }
                itemsDelta.removedEditors?.forEach { id -> logger.debug("  [REMOVED EDITOR] $id") }
                editorStateService.acceptDocumentsAndEditorsDelta(itemsDelta)
            }

            // Send editor property change notifications
            if (delta.editorDeltas.isNotEmpty()) {
                editorStateService.acceptEditorPropertiesChanged(delta.editorDeltas)
            }

            // Send document content change notifications
            if (delta.documentDeltas.isNotEmpty()) {
                editorStateService.acceptModelChanged(delta.documentDeltas)
            }
        }
    }

    suspend fun updateDocumentAsync(document: ModelAddedData) {
        // Check if the document exists
        val shouldUpdate = synchronized(stateLock) {
            if (state.documents[document.uri] != null) {
                state.documents[document.uri] = document
                true
            } else {
                false
            }
        }
        if (shouldUpdate) {
            processUpdates()
        }
    }

    fun updateDocument(document: ModelAddedData) {
        // Check if the document exists
        val shouldUpdate = synchronized(stateLock) {
            if (state.documents[document.uri] != null) {
                state.documents[document.uri] = document
                true
            } else {
                false
            }
        }
        if (shouldUpdate) {
            scheduleUpdate()
        }
    }

    suspend fun syncUpdates() {
        processUpdates()
    }

    fun updateEditor(state: TextEditorAddData) {
        val shouldUpdate = synchronized(stateLock) {
            if (this.state.editors[state.id] != null) {
                this.state.editors[state.id] = state
                true
            } else {
                false
            }
        }
        if (shouldUpdate) {
            scheduleUpdate()
        }
    }

    fun getIdeaDiffEditor(uri: URI): WeakReference<Editor>? {
        val editor = ideaOpenedEditor[uri.path] ?: return null
        return WeakReference(editor)
    }

    fun onIdeaDiffEditorCreated(url: URI, editor: Editor) {
        ideaOpenedEditor.put(url.path,editor);
    }

    fun onIdeaDiffEditorReleased(url: URI, editor: Editor) {
        ideaOpenedEditor.remove(url.path)
    }

    private fun getOrCreateMainGroup(): TabGroupHandle {
        val groups = tabManager.getAllGroups()
        return if (groups.isNotEmpty()) {
            tabManager.getTabGroupHandle(groups[0].groupId)
                ?: tabManager.createTabGroup(EditorGroupColumn.beside.value, true)
        } else {
            tabManager.createTabGroup(EditorGroupColumn.beside.value, true)
        }
    }
}


data class DocumentsAndEditorsState (
    var editors: MutableMap<String , TextEditorAddData> =  ConcurrentHashMap(),
    var documents: MutableMap<URI, ModelAddedData> =  ConcurrentHashMap(),
    var activeEditorId: String? = null
){

    fun delta(lastState: DocumentsAndEditorsState): Delta {
        // Calculate document changes
        val currentDocumentUrls = documents.keys.toSet()
        val lastDocumentUrls = lastState.documents.keys.toSet()

        val removedUrls = lastDocumentUrls - currentDocumentUrls
        val addedUrls = currentDocumentUrls - lastDocumentUrls

        val addedDocuments = addedUrls.mapNotNull { documents[it] }

        // Calculate editor changes
        val addedEditors = mutableListOf<TextEditorAddData>()
        val editorDeltas = mutableMapOf<String, EditorPropertiesChangeData>()

        val currentEditorIds = editors.keys.toSet()
        val lastEditorIds = lastState.editors.keys.toSet()

        val removedIds = lastEditorIds - currentEditorIds

        // Iterate through all current editors, handling additions and property changes simultaneously
        editors.forEach { (id, editor) ->
            lastState.editors[id]?.let { lastEditor ->
                // Check for option changes
                val optionsChanged = editor.options != lastEditor.options

                // Check for selection area changes
                val selectionsChanged = editor.selections != lastEditor.selections

                // Check for visible range changes
                val visibleRangesChanged = editor.visibleRanges != lastEditor.visibleRanges

                // If there are any changes, create EditorPropertiesChangeData
                if (optionsChanged || selectionsChanged || visibleRangesChanged) {
                    editorDeltas[id] = EditorPropertiesChangeData(
                        options = if (optionsChanged) editor.options else null,
                        selections = if (selectionsChanged) SelectionChangeEvent(
                            selections =  editor.selections,
                            source = null
                        ) else null,
                        visibleRanges = if (visibleRangesChanged) editor.visibleRanges else null
                    )
                }
            } ?: run {
                // Newly added editor
                addedEditors.add(editor)
            }
        }

        // Calculate document content changes
        val documentDeltas = mutableMapOf<URI, ModelChangedEvent>()

        // Iterate through all current documents, checking for content changes
        documents.forEach { (uri, document) ->
            lastState.documents[uri]?.let { lastDocument ->
                // Check if the document has changes
                val hasChanges = document.lines != lastDocument.lines ||
                        document.EOL != lastDocument.EOL ||
                        document.languageId != lastDocument.languageId ||
                        document.isDirty != lastDocument.isDirty ||
                        document.encoding != lastDocument.encoding

                if (hasChanges) {
                    // If content has changed, create changes for the entire document
                    val changes = listOf(
                        ModelContentChange(
                            range = Range(
                                startLineNumber = 1,
                                startColumn = 1,
                                endLineNumber = max(1, lastDocument.lines.size),
                                endColumn = max(1, (lastDocument.lines.lastOrNull()?.length ?: 0) + 1)
                            ),
                            rangeOffset = 0,
                            rangeLength = lastDocument.lines.joinToString(lastDocument.EOL).length,
                            text = document.lines.joinToString(document.EOL)
                        )
                    )

                    documentDeltas[uri] = ModelChangedEvent(
                        changes = changes,
                        eol = document.EOL,
                        versionId = document.versionId,
                        isUndoing = false,
                        isRedoing = false,
                        isDirty = document.isDirty
                    )
                }
            }
        }

        val itemsDelta = DocumentsAndEditorsDelta(
            removedDocuments = removedUrls.toList(),
            addedDocuments = addedDocuments,
            removedEditors = removedIds.toList(),
            addedEditors = addedEditors,
            newActiveEditor = if (activeEditorId != lastState.activeEditorId) activeEditorId else null
        )

        return Delta(
            itemsDelta = if (itemsDelta.isEmpty()) null else itemsDelta,
            editorDeltas = editorDeltas,
            documentDeltas = documentDeltas
        )
    }

}
data class Delta(
    val itemsDelta : DocumentsAndEditorsDelta?,
    val editorDeltas : MutableMap<String , EditorPropertiesChangeData>,
    val documentDeltas : MutableMap<URI, ModelChangedEvent>
)
