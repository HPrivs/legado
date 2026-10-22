package io.legado.app.ui.book.read

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookProgressSync
import io.legado.app.data.entities.BookProgressSyncDecision
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.ai.AiPurifiedContentStore
import io.legado.app.help.ai.AiPurificationDiagnostics
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.ai.client.OpenAiCompatibleClient
import io.legado.app.model.ai.client.DeepSeekException
import io.legado.app.model.ai.purify.NovelPurificationRuntimeConfig
import io.legado.app.model.ai.purify.NovelPurificationRuntimeConfigProvider
import io.legado.app.model.ai.purify.NovelPurificationService
import io.legado.app.model.ai.purify.PurificationResultValidator
import io.legado.app.model.ai.purify.PurificationStreamDiagnostic
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.utils.DocumentUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.postEvent
import io.legado.app.utils.toStringArray
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.ArrayDeque

/**
 * 阅读界面数据处理
 */
class ReadBookViewModel(application: Application) : BaseViewModel(application) {

    sealed interface AiPurifyState {
        data object Idle : AiPurifyState

        data class Running(
            val bookUrl: String,
            val chapterIndex: Int
        ) : AiPurifyState

        data class Ready(
            val bookUrl: String,
            val chapterIndex: Int
        ) : AiPurifyState

        data class Failed(
            val bookUrl: String,
            val chapterIndex: Int
        ) : AiPurifyState
    }

    private data class AiPurifyTask(
        val book: Book,
        val chapter: BookChapter,
        val originalContent: String,
        val config: NovelPurificationRuntimeConfig,
        val store: AiPurifiedContentStore,
        val cacheKey: AiPurifiedContentStore.CacheKey
    )

    private data class AiChapterKey(
        val bookUrl: String,
        val chapterIndex: Int
    )

    private data class AiActiveStreamSession(
        val key: AiChapterKey,
        val cacheKey: String,
        val traceId: String,
        val requestStartedAtMillis: Long,
        var latestContent: String = "",
        var latestCompletedLines: String = "",
        var readerAttached: Boolean = false
    )

    private data class AiQueueRequest(
        val book: Book,
        val chapterIndex: Int,
        val force: Boolean = false,
        val traceId: String = AiPurificationDiagnostics.newTraceId(),
        val enqueuedAtMillis: Long = AiPurificationDiagnostics.nowMillis()
    ) {
        val key = AiChapterKey(book.bookUrl, chapterIndex)
    }

    private enum class AiChapterStatus {
        RUNNING,
        READY,
        FAILED
    }

    private enum class AiProcessOutcome {
        READY,
        SKIPPED,
        FAILED
    }

    val permissionDenialLiveData = MutableLiveData<Int>()
    private val _aiPurifyState = MutableLiveData<AiPurifyState>(AiPurifyState.Idle)
    val aiPurifyState: LiveData<AiPurifyState> = _aiPurifyState
    var isInitFinish = false
    var searchContentQuery = ""
    var searchResultList: List<SearchResult>? = null
    var searchResultIndex: Int = 0
    private var changeSourceCoroutine: Coroutine<*>? = null
    private var aiPurifyWorkerJob: Job? = null
    private var aiStreamingReloadJob: Job? = null
    private var lastAiStreamingReloadAt = 0L
    private var aiPurifyGeneration = 0
    private var aiSessionBookUrl: String? = null
    private var activeAiChapter: AiChapterKey? = null
    private var activeAiStreamSession: AiActiveStreamSession? = null
    private val aiPurifyQueue = ArrayDeque<AiQueueRequest>()
    private val queuedAiChapters = mutableSetOf<AiChapterKey>()
    private val aiChapterStatuses = mutableMapOf<AiChapterKey, AiChapterStatus>()
    private val failedAiCacheKeys = mutableSetOf<String>()
    private val backgroundFailedAiChapters = mutableMapOf<AiChapterKey, String?>()
    private val pendingAiOriginalContent = mutableSetOf<AiChapterKey>()
    private val availableAiOriginalContent = mutableSetOf<AiChapterKey>()
    private val validatingReadyAiChapters = mutableSetOf<AiChapterKey>()

    init {
        AppConfig.detectClickArea()
    }

    fun initReadBookConfig(intent: Intent) {
        val bookUrl = intent.getStringExtra("bookUrl")
        val book = when {
            bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
            else -> appDb.bookDao.getBook(bookUrl)
        } ?: return
        ReadBook.upReadBookConfig(book)
    }

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            ReadBook.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            ReadBook.chapterChanged = intent.getBooleanExtra("chapterChanged", false)
            val bookUrl = intent.getStringExtra("bookUrl")
            val book = when {
                bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
                else -> appDb.bookDao.getBook(bookUrl)
            } ?: ReadBook.book
            when {
                book != null -> initBook(book)
                else -> {
                    ReadBook.upMsg(context.getString(R.string.no_book))
                    AppLog.put("未找到书籍\nbookUrl:$bookUrl")
                }
            }
            val index = intent.getIntExtra("index", -1)
            val chapterPos = intent.getIntExtra("chapterPos", -1)
            if (index >= 0 && chapterPos >= 0) { //从书签打开的正文，有进度传递
                ReadBook.saveCurrentBookProgress() //启用恢复进度提示
                openChapter(index, chapterPos)
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            val msg = "初始化数据失败\n${it.localizedMessage}"
            ReadBook.upMsg(msg)
            AppLog.put(msg, it)
        }.onFinally {
            ReadBook.saveRead()
        }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = ReadBook.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            ReadBook.upData(book)
        } else {
            ReadBook.resetData(book)
        }
        isInitFinish = true
        if (!book.isLocal && book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return
        }
        if (book.isLocal && !checkLocalBookFileExist(book)) {
            return
        }
        if ((ReadBook.chapterSize == 0 || book.isLocalModified()) && !loadChapterListAwait(book)) {
            return
        }
        ReadBook.upMsg(null)
        if (!isSameBook) {
            ReadBook.loadContent(resetPageOffset = true) {
                ReadBook.bookSource?.let {
                    SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, ReadBook.curTextChapter?.chapter)
                }
            }
        } else {
            ReadBook.loadOrUpContent {
                ReadBook.bookSource?.let {
                    SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, ReadBook.curTextChapter?.chapter)
                }
            }
        }
        if (ReadBook.chapterChanged) {
            // 有章节跳转不同步阅读进度
            ReadBook.chapterChanged = false
        } else if (!(isSameBook && BaseReadAloudService.isRun) && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress({ progress -> ReadBook.callBack?.sureNewProgress(progress) })
            } else {
                syncBookProgress(book)
            }
        }
        if (!book.isLocal && ReadBook.bookSource == null) {
            autoChangeSource(book.name, book.author)
            return
        }
    }

    private fun checkLocalBookFileExist(book: Book): Boolean {
        try {
            LocalBook.getBookInputStream(book)
            return true
        } catch (e: Throwable) {
            ReadBook.upMsg("打开本地书籍出错: ${e.localizedMessage}")
            if (e is SecurityException || e is FileNotFoundException) {
                permissionDenialLiveData.postValue(0)
            }
            return false
        }
    }

    /**
     * 加载详情页
     */
    private suspend fun loadBookInfo(book: Book): Boolean {
        val source = ReadBook.bookSource ?: return true
        try {
            WebBook.getBookInfoAwait(source, book, canReName = false)
            return true
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            ReadBook.upMsg("详情页出错: ${e.localizedMessage}")
            return false
        }
    }

    /**
     * 加载目录
     */
    fun loadChapterList(book: Book) {
        execute {
            if (loadChapterListAwait(book)) {
                ReadBook.upMsg(null)
            }
        }
    }

    private suspend fun loadChapterListAwait(book: Book): Boolean {
        if (book.isLocal) {
            kotlin.runCatching {
                LocalBook.getChapterList(book).let {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    appDb.bookDao.update(book)
                    ReadBook.onChapterListUpdated(book)
                }
                return true
            }.onFailure {
                when (it) {
                    is SecurityException, is FileNotFoundException -> {
                        permissionDenialLiveData.postValue(1)
                    }

                    else -> {
                        AppLog.put("LoadTocError:${it.localizedMessage}", it)
                        ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                    }
                }
                return false
            }
        } else {
            ReadBook.bookSource?.let {
                val oldBook = book.copy()
                WebBook.getChapterListAwait(it, book, true)
                    .onSuccess { cList ->
                        if (oldBook.bookUrl == book.bookUrl) {
                            appDb.bookDao.update(book)
                        } else {
                            appDb.bookDao.replace(oldBook, book)
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*cList.toTypedArray())
                        ReadBook.onChapterListUpdated(book)
                        return true
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        ReadBook.upMsg(context.getString(R.string.error_load_toc))
                        return false
                    }
            }
        }
        return true
    }

    /**
     * 同步进度
     */
    fun syncBookProgress(
        book: Book,
        alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        execute {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
        }.onSuccess { progress ->
            progress ?: return@onSuccess
            when (BookProgressSync.decide(BookProgress(book), progress)) {
                BookProgressSyncDecision.KEEP_LOCAL -> alertSync?.invoke(progress)
                BookProgressSyncDecision.USE_REMOTE -> {
                    if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                        ReadBook.setProgress(progress)
                        AppLog.put("自动同步阅读进度成功《${book.name}》 ${progress.durChapterTitle}")
                        context.toastOnUi("已同步最新阅读进度")
                    }
                }

                BookProgressSyncDecision.ALREADY_SYNCED -> execute {
                    when {
                        progress.durChapterTime > book.durChapterTime -> {
                            book.durChapterTime = progress.durChapterTime
                            book.durChapterTitle = progress.durChapterTitle
                            appDb.bookDao.update(book)
                        }

                        progress.durChapterTime < book.durChapterTime -> {
                            AppWebDav.uploadBookProgress(book)
                            appDb.bookDao.update(book)
                        }
                    }
                }
            }
        }
    }

    /**
     * 换源
     */
    fun changeTo(book: Book, toc: List<BookChapter>) {
        changeSourceCoroutine?.cancel()
        changeSourceCoroutine = execute {
            ReadBook.upMsg(context.getString(R.string.loading))
            ReadBook.book?.migrateTo(book, toc)
            book.removeType(BookType.updateError)
            ReadBook.book?.delete()
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            ReadBook.resetData(book)
            ReadBook.upMsg(null)
            ReadBook.loadContent(resetPageOffset = true)
        }.onError {
            AppLog.put("换源失败\n$it", it, true)
            ReadBook.upMsg(null)
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    /**
     * 自动换源
     */
    private fun autoChangeSource(name: String, author: String) {
        if (!AppConfig.autoChangeSource) return
        execute {
            val sources = appDb.bookSourceDao.allTextEnabledPart
            flow {
                for (source in sources) {
                    source.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                ReadBook.upMsg(context.getString(R.string.source_auto_changing))
            }.mapParallelSafe(AppConfig.threadCount) { source ->
                val book = WebBook.preciseSearchAwait(source, name, author).getOrThrow()
                if (book.tocUrl.isEmpty()) {
                    WebBook.getBookInfoAwait(source, book)
                }
                val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
                val chapter = toc.getOrElse(book.durChapterIndex) {
                    toc.last()
                }
                val nextChapter = toc.getOrElse(chapter.index) {
                    toc.first()
                }
                WebBook.getContentAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    nextChapterUrl = nextChapter.url
                )
                book to toc
            }.take(1).onEach { (book, toc) ->
                changeTo(book, toc)
            }.onEmpty {
                throw NoStackTraceException("没有合适书源")
            }.onCompletion {
                ReadBook.upMsg(null)
            }.catch {
                AppLog.put("自动换源失败\n${it.localizedMessage}", it)
                context.toastOnUi("自动换源失败\n${it.localizedMessage}")
            }.collect()
        }
    }

    fun openChapter(index: Int, durChapterPos: Int = 0, success: (() -> Unit)? = null) {
        ReadBook.openChapter(index, durChapterPos, success = success)
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        val book = ReadBook.book
        Coroutine.async {
            book?.delete()
        }.onSuccess {
            success?.invoke()
        }
    }

    fun upBookSource(success: (() -> Unit)?) {
        execute {
            ReadBook.book?.let { book ->
                ReadBook.bookSource = appDb.bookSourceDao.getBookSource(book.origin)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun refreshContentDur(book: Book) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.delContent(book, chapter)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
        }
    }

    fun refreshContentAfter(book: Book) {
        execute {
            appDb.bookChapterDao.getChapterList(
                book.bookUrl,
                ReadBook.durChapterIndex,
                book.totalChapterNum
            ).forEach { chapter ->
                BookHelp.delContent(book, chapter)
            }
            ReadBook.loadContent(false)
        }
    }

    fun refreshContentAll(book: Book) {
        execute {
            BookHelp.clearCache(book)
            ReadBook.loadContent(false)
        }
    }

    /**
     * 保存内容
     */
    fun saveContent(book: Book, content: String) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.saveText(book, chapter, content)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
        }
    }

    /**
     * 反转内容
     */
    fun reverseContent(book: Book) {
        execute {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@execute
            val content = BookHelp.getContent(book, chapter) ?: return@execute
            val stringBuilder = StringBuilder()
            content.toStringArray().forEach {
                stringBuilder.insert(0, it)
            }
            BookHelp.saveText(book, chapter, stringBuilder.toString())
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }
    }

    /**
     * 内容搜索跳转
     */
    fun searchResultPositions(
        textChapter: TextChapter,
        searchResult: SearchResult
    ): Array<Int> {
        // calculate search result's pageIndex
        val pages = textChapter.pages
        val content = textChapter.getContent()
        var queryLength = searchContentQuery.length

        var index: Int
        if (searchResult.isRegex) {
            val regex = Regex(searchContentQuery)
            val matches = regex.findAll(content)
            val match = matches.elementAtOrNull(searchResult.resultCountWithinChapter)
            queryLength = match?.value?.length ?: 0
            index = match?.range?.first ?: -1
        } else {
            var count = 0
            index = content.indexOf(searchContentQuery)
            while (count != searchResult.resultCountWithinChapter) {
                index = content.indexOf(searchContentQuery, index + queryLength)
                count += 1
            }
        }
        val contentPosition = index
        var pageIndex = 0
        var length = pages[pageIndex].text.length
        while (length < contentPosition && pageIndex + 1 < pages.size) {
            pageIndex += 1
            length += pages[pageIndex].text.length
        }

        // calculate search result's lineIndex
        val currentPage = pages[pageIndex]
        val curTextLines = currentPage.lines
        var lineIndex = 0
        var curLine = curTextLines[lineIndex]
        length = length - currentPage.text.length + curLine.text.length
        if (curLine.isParagraphEnd) length++
        while (length <= contentPosition && lineIndex + 1 < curTextLines.size) {
            lineIndex += 1
            curLine = curTextLines[lineIndex]
            length += curLine.text.length
            if (curLine.isParagraphEnd) length++
        }

        // charIndex
        val currentLine = currentPage.lines[lineIndex]
        var curLineLength = currentLine.text.length
        if (currentLine.isParagraphEnd) curLineLength++
        length -= curLineLength

        val charIndex = contentPosition - length
        var addLine = 0
        var charIndex2 = 0
        // change line
        if ((charIndex + queryLength) > curLineLength) {
            addLine = 1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        // changePage
        if ((lineIndex + addLine + 1) > currentPage.lines.size) {
            addLine = -1
            charIndex2 = charIndex + queryLength - curLineLength - 1
        }
        return arrayOf(pageIndex, lineIndex, charIndex, addLine, charIndex2, queryLength)
    }

    /**
     * 翻转删除重复标题
     */
    fun reverseRemoveSameTitle() {
        execute {
            val book = ReadBook.book ?: return@execute
            val textChapter = ReadBook.curTextChapter ?: return@execute
            BookHelp.setRemoveSameTitle(
                book, textChapter.chapter, !textChapter.sameTitleRemoved
            )
            ReadBook.loadContent(ReadBook.durChapterIndex)
        }
    }

    /**
     * 刷新图片
     */
    fun refreshImage(src: String) {
        execute {
            ReadBook.book?.let { book ->
                val vFile = BookHelp.getImage(book, src)
                ImageProvider.bitmapLruCache.remove(vFile.absolutePath)
                vFile.delete()
            }
        }.onFinally {
            ReadBook.loadContent(false)
        }
    }

    /**
     * 保存图片
     */
    fun saveImage(src: String?, uri: Uri) {
        src ?: return
        val book = ReadBook.book ?: return
        execute {
            val image = BookHelp.getImage(book, src)
            FileInputStream(image).use { input ->
                if (uri.isContentScheme()) {
                    DocumentFile.fromTreeUri(context, uri)?.let { doc ->
                        val imageDoc = DocumentUtils.createFileIfNotExist(doc, image.name)!!
                        context.contentResolver.openOutputStream(imageDoc.uri)!!.use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    val dir = File(uri.path ?: uri.toString())
                    val file = FileUtils.createFileIfNotExist(dir, image.name)
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }.onError {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            context.toastOnUi("保存图片出错\n${it.localizedMessage}")
        }
    }

    /**
     * 替换规则变化
     */
    fun replaceRuleChanged() {
        execute {
            ReadBook.book?.let {
                ContentProcessor.get(it.name, it.origin).upReplaceRules()
                ReadBook.loadContent(resetPageOffset = false)
            }
        }
    }

    /** AI 净化开关变化。关闭时取消任务并恢复原文；开启时刷新当前章。 */
    fun aiPurifyChanged() {
        resetAiPurifyQueue()
        _aiPurifyState.value = AiPurifyState.Idle
        ReadBook.loadContent(resetPageOffset = false)
        ReadBook.book?.takeIf { it.getUseAiPurify() }?.let {
            aiSessionBookUrl = it.bookUrl
            ensureCurrentChapterPurified()
        }
    }

    /** Retries a skipped speculative chapter once its original text is actually available. */
    fun onChapterOriginalContentReady(bookUrl: String, chapterIndex: Int) {
        val book = ReadBook.book ?: return
        if (!book.getUseAiPurify() || book.bookUrl != bookUrl) return
        val key = AiChapterKey(bookUrl, chapterIndex)
        availableAiOriginalContent.add(key)
        retryPendingAiOriginalContent(book, key)
    }

    /** 当前章优先入队；同章翻页不会取消或重复创建整章任务。 */
    fun ensureCurrentChapterPurified() {
        val book = ReadBook.book ?: return
        if (!book.getUseAiPurify()) return
        ensureAiSession(book)
        val key = AiChapterKey(book.bookUrl, ReadBook.durChapterIndex)
        when (aiChapterStatuses[key]) {
            AiChapterStatus.READY -> {
                refreshCurrentAiPurifyState()
                validateReadyAiChapter(book, key)
                return
            }

            AiChapterStatus.FAILED -> {
                if (key in backgroundFailedAiChapters) {
                    backgroundFailedAiChapters.remove(key)?.let(failedAiCacheKeys::remove)
                    aiChapterStatuses.remove(key)
                    enqueueCurrentChapter(book, key.chapterIndex, force = false)
                    return
                }
                refreshCurrentAiPurifyState()
                return
            }

            AiChapterStatus.RUNNING -> {
                if (activeAiChapter == key) {
                    attachActiveAiStreamToCurrent(key)
                    refreshCurrentAiPurifyState()
                    return
                }
                if (key in queuedAiChapters) {
                    refreshCurrentAiPurifyState()
                    return
                }
            }

            null -> Unit
        }
        enqueueCurrentChapter(book, key.chapterIndex, force = false)
    }

    /** 取消旧任务和临时结果，绕过缓存并明确发起一次全新的整章请求。 */
    fun repurifyCurrentChapter() {
        val book = ReadBook.book ?: return
        if (!book.getUseAiPurify()) return
        val chapterIndex = ReadBook.durChapterIndex
        resetAiPurifyQueue()
        ensureAiSession(book)
        enqueueCurrentChapter(book, chapterIndex, force = true)
    }

    private fun enqueueCurrentChapter(book: Book, chapterIndex: Int, force: Boolean) {
        val key = AiChapterKey(book.bookUrl, chapterIndex)
        val retainedForce = aiPurifyQueue.firstOrNull { it.key == key }?.force == true
        preemptActiveAiPurifyFor(key)
        queuedAiChapters.forEach(aiChapterStatuses::remove)
        aiPurifyQueue.clear()
        queuedAiChapters.clear()
        if (activeAiChapter != key) {
            val queuedRequest = AiQueueRequest(
                book,
                chapterIndex,
                force = force || retainedForce
            )
            aiPurifyQueue.addFirst(queuedRequest)
            queuedAiChapters.add(key)
            AiPurificationDiagnostics.log(
                traceId = queuedRequest.traceId,
                stage = "queued_current",
                details = "chapterIndex=$chapterIndex force=${queuedRequest.force} " +
                    "activeChapterIndex=${activeAiChapter?.chapterIndex ?: -1}"
            )
        }
        aiChapterStatuses[key] = AiChapterStatus.RUNNING
        refreshCurrentAiPurifyState()
        startAiPurifyWorker()
    }

    /** A visible chapter always wins over a different speculative/background request. */
    private fun preemptActiveAiPurifyFor(currentKey: AiChapterKey) {
        val previousKey = activeAiChapter ?: return
        if (previousKey == currentKey) return
        aiPurifyGeneration++
        cancelAiStreamingReload()
        aiPurifyWorkerJob?.cancel()
        aiPurifyWorkerJob = null
        activeAiStreamSession = null
        activeAiChapter = null
        aiChapterStatuses.remove(previousKey)
        validatingReadyAiChapters.clear()
        ReadBook.clearAiStreamingContent(previousKey.bookUrl, previousKey.chapterIndex)
    }

    private fun enqueueNextChapterIfNeeded(book: Book, chapterIndex: Int) {
        val nextIndex = chapterIndex + 1
        if (nextIndex !in 0 until ReadBook.chapterSize) return
        val key = AiChapterKey(book.bookUrl, nextIndex)
        if (activeAiChapter == key || key in queuedAiChapters) return
        if (aiChapterStatuses[key] == AiChapterStatus.READY ||
            aiChapterStatuses[key] == AiChapterStatus.FAILED
        ) return
        pendingAiOriginalContent.remove(key)
        availableAiOriginalContent.remove(key)
        val queuedRequest = AiQueueRequest(book, nextIndex)
        aiPurifyQueue.addLast(queuedRequest)
        queuedAiChapters.add(key)
        AiPurificationDiagnostics.log(
            traceId = queuedRequest.traceId,
            stage = "queued_background",
            details = "chapterIndex=$nextIndex"
        )
        aiChapterStatuses[key] = AiChapterStatus.RUNNING
        refreshCurrentAiPurifyState()
        startAiPurifyWorker()
    }

    private fun retryPendingAiOriginalContent(book: Book, key: AiChapterKey) {
        if (key !in pendingAiOriginalContent || key !in availableAiOriginalContent) return
        if (activeAiChapter == key) return
        val isStillNext = ReadBook.book?.bookUrl == key.bookUrl &&
            key.chapterIndex == ReadBook.durChapterIndex + 1
        val currentKey = AiChapterKey(key.bookUrl, ReadBook.durChapterIndex)
        if (!isStillNext || aiChapterStatuses[currentKey] != AiChapterStatus.READY) {
            pendingAiOriginalContent.remove(key)
            availableAiOriginalContent.remove(key)
            return
        }
        enqueueNextChapterIfNeeded(book, ReadBook.durChapterIndex)
    }

    private fun startAiPurifyWorker() {
        if (aiPurifyWorkerJob?.isActive == true || aiPurifyQueue.isEmpty()) return
        val generation = aiPurifyGeneration
        aiPurifyWorkerJob = viewModelScope.launch {
            try {
                while (aiPurifyQueue.isNotEmpty()) {
                    val request = aiPurifyQueue.removeFirst()
                    queuedAiChapters.remove(request.key)
                    activeAiChapter = request.key
                    aiChapterStatuses[request.key] = AiChapterStatus.RUNNING
                    refreshCurrentAiPurifyState()
                    val outcome = processAiPurifyRequest(request)
                    if (activeAiStreamSession?.key == request.key) {
                        activeAiStreamSession = null
                    }
                    activeAiChapter = null
                    refreshCurrentAiPurifyState()
                    if (outcome == AiProcessOutcome.READY && isCurrentAiChapter(request.key)) {
                        enqueueNextChapterIfNeeded(request.book, request.chapterIndex)
                    } else if (outcome == AiProcessOutcome.SKIPPED) {
                        retryPendingAiOriginalContent(request.book, request.key)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (generation == aiPurifyGeneration) {
                    activeAiChapter = null
                    aiPurifyWorkerJob = null
                    refreshCurrentAiPurifyState()
                    if (aiPurifyQueue.isNotEmpty()) startAiPurifyWorker()
                }
            }
        }
    }

    private suspend fun processAiPurifyRequest(request: AiQueueRequest): AiProcessOutcome {
        var task: AiPurifyTask? = null
        val workerStartedAtMillis = AiPurificationDiagnostics.nowMillis()
        var requestStartedAtMillis: Long? = null
        AiPurificationDiagnostics.log(
            traceId = request.traceId,
            stage = "worker_started",
            startedAtMillis = workerStartedAtMillis,
            details = "chapterIndex=${request.chapterIndex} " +
                "queueWaitMs=${(workerStartedAtMillis - request.enqueuedAtMillis).coerceAtLeast(0L)} " +
                "current=${isCurrentAiChapter(request.key)}"
        )
        return try {
            task = withContext(IO) {
                prepareAiPurifyTask(request.book, request.chapterIndex)
            }
            if (task == null) {
                if (!isCurrentAiChapter(request.key) &&
                    ReadBook.book?.bookUrl == request.key.bookUrl &&
                    request.key.chapterIndex == ReadBook.durChapterIndex + 1
                ) {
                    pendingAiOriginalContent.add(request.key)
                }
                AiPurificationDiagnostics.log(
                    request.traceId,
                    "skipped_no_original",
                    workerStartedAtMillis
                )
                aiChapterStatuses.remove(request.key)
                return AiProcessOutcome.SKIPPED
            }
            if (request.force) {
                if (!task.config.isReady) {
                    val hasCache = withContext(IO) { task.store.read(task.cacheKey) != null }
                    aiChapterStatuses[request.key] = if (hasCache) {
                        AiChapterStatus.READY
                    } else {
                        AiChapterStatus.FAILED
                    }
                    refreshCurrentAiPurifyState()
                    toastAiFailureIfCurrent(
                        request.key,
                        context.getString(R.string.ai_reader_missing_settings)
                    )
                    return AiProcessOutcome.FAILED
                }
                ReadBook.clearAiStreamingContent(request.key.bookUrl, request.key.chapterIndex)
                withContext(IO) { task.store.delete(task.cacheKey) }
                failedAiCacheKeys.remove(task.cacheKey.value)
                AiPurificationDiagnostics.log(
                    request.traceId,
                    "forced_cache_deleted",
                    workerStartedAtMillis
                )
            } else {
                if (task.cacheKey.value in failedAiCacheKeys) {
                    aiChapterStatuses[request.key] = AiChapterStatus.FAILED
                    refreshCurrentAiPurifyState()
                    return AiProcessOutcome.FAILED
                }
                if (withContext(IO) { task.store.read(task.cacheKey) } != null) {
                    AiPurificationDiagnostics.log(
                        request.traceId,
                        "cache_hit",
                        workerStartedAtMillis
                    )
                    markAiPurifyReady(request.key, task.cacheKey.value)
                    reloadAiChapterIfRelevant(request.key, task.cacheKey.value)
                    return AiProcessOutcome.READY
                }
                if (!task.config.isReady) {
                    if (isCurrentAiChapter(request.key)) {
                        failAiPurify(
                            request.key,
                            task.cacheKey.value,
                            context.getString(R.string.ai_reader_missing_settings)
                        )
                        return AiProcessOutcome.FAILED
                    }
                    aiChapterStatuses.remove(request.key)
                    return AiProcessOutcome.SKIPPED
                }
            }

            val service = NovelPurificationService(
                OpenAiCompatibleClient(task.config.baseUrl, task.config.apiKey)
            )
            val streamStartedAtMillis = AiPurificationDiagnostics.nowMillis()
            requestStartedAtMillis = streamStartedAtMillis
            var contentBufferReloadLogged = false
            activeAiStreamSession = AiActiveStreamSession(
                key = request.key,
                cacheKey = task.cacheKey.value,
                traceId = request.traceId,
                requestStartedAtMillis = streamStartedAtMillis
            )
            AiPurificationDiagnostics.log(
                traceId = request.traceId,
                stage = "request_start",
                startedAtMillis = streamStartedAtMillis,
                details = "chapterIndex=${request.chapterIndex} provider=${task.config.provider.value} " +
                    "thinking=${task.config.thinkingEnabled} " +
                    "effort=${task.config.reasoningEffort.preferenceValue} " +
                    "inputChars=${task.originalContent.length}"
            )
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (activeAiChapter == request.key && isCurrentAiChapter(request.key)) {
                    attachActiveAiStreamToCurrent(request.key)
                }
            }
            when (val result = service.purifyStreaming(
                request = task.config.createRequest(task.originalContent),
                onDiagnostic = { event ->
                    logAiStreamDiagnostic(request.traceId, streamStartedAtMillis, event)
                },
                onContentAvailable = { update ->
                    withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        if (activeAiChapter != request.key) {
                            return@withContext
                        }
                        val streamSession = activeAiStreamSession
                            ?.takeIf { it.key == request.key }
                            ?: return@withContext
                        streamSession.latestContent = update.content
                        val completedLines = update.completedLines ?: return@withContext
                        streamSession.latestCompletedLines = completedLines
                        if (!isCurrentAiChapter(request.key)) return@withContext
                        if (!streamSession.readerAttached) {
                            attachActiveAiStreamToCurrent(request.key)
                            return@withContext
                        }
                        val shouldReload = ReadBook.updateAiStreamingContent(
                            bookUrl = request.key.bookUrl,
                            chapterIndex = request.key.chapterIndex,
                            cacheKey = task.cacheKey.value,
                            content = completedLines,
                            traceId = request.traceId,
                            requestStartedAtMillis = streamStartedAtMillis
                        )
                        if (shouldReload) {
                            if (!contentBufferReloadLogged) {
                                contentBufferReloadLogged = true
                                AiPurificationDiagnostics.log(
                                    request.traceId,
                                    "content_buffer_reload_requested",
                                    streamStartedAtMillis,
                                    "streamChars=${completedLines.length}"
                                )
                            }
                            scheduleAiStreamingReload(request.key)
                        }
                    }
                }
            )) {
                is NovelPurificationService.Result.Success -> {
                    cancelAiStreamingReload()
                    val originalUnchanged = withContext(IO) {
                        val currentOriginal = BookHelp.getContent(task.book, task.chapter)
                        currentOriginal != null &&
                            PurificationResultValidator.hashOriginal(currentOriginal) ==
                            PurificationResultValidator.hashOriginal(task.originalContent)
                    }
                    if (!originalUnchanged) {
                        failAiPurify(
                            request.key,
                            task.cacheKey.value,
                            context.getString(R.string.ai_purification_original_changed)
                        )
                        return AiProcessOutcome.FAILED
                    }
                    withContext(IO) { task.store.write(task.cacheKey, result.content) }
                    AiPurificationDiagnostics.log(
                        request.traceId,
                        "cache_committed",
                        streamStartedAtMillis,
                        "outputChars=${result.content.length}"
                    )
                    ReadBook.clearAiStreamingContent(
                        request.key.bookUrl,
                        request.key.chapterIndex
                    )
                    markAiPurifyReady(request.key, task.cacheKey.value)
                    // A forced re-purification keeps the same content-addressed cache key.
                    // If the response finishes before the first streaming layout replaces the
                    // old chapter, cache-key equality would incorrectly suppress this reload.
                    // The committed result must always win over every in-flight preview layout.
                    reloadAiChapterIfRelevant(
                        request.key,
                        task.cacheKey.value,
                        force = true
                    )
                    AiPurificationDiagnostics.log(
                        request.traceId,
                        "request_succeeded",
                        streamStartedAtMillis
                    )
                    AiProcessOutcome.READY
                }

                NovelPurificationService.Result.EmptyInput -> {
                    AiPurificationDiagnostics.log(
                        request.traceId,
                        "request_invalid_empty_input",
                        streamStartedAtMillis
                    )
                    failAiPurify(
                        request.key,
                        task.cacheKey.value,
                        context.getString(R.string.ai_purification_input_required)
                    )
                }

                is NovelPurificationService.Result.ContextTooLarge -> {
                    AiPurificationDiagnostics.log(
                        request.traceId,
                        "request_invalid_context_too_large",
                        streamStartedAtMillis
                    )
                    failAiPurify(
                        request.key,
                        task.cacheKey.value,
                        context.getString(R.string.ai_purification_context_too_large)
                    )
                }

                is NovelPurificationService.Result.InvalidResponse -> {
                    AiPurificationDiagnostics.log(
                        request.traceId,
                        "request_invalid_response",
                        streamStartedAtMillis,
                        "reason=${result.validation.reason} " +
                            "finishReason=${result.validation.finishReason.orEmpty()}"
                    )
                    failAiPurify(
                        request.key,
                        task.cacheKey.value,
                        invalidResponseMessage(result.validation)
                    )
                }
            }
        } catch (error: CancellationException) {
            AiPurificationDiagnostics.log(
                request.traceId,
                "request_cancelled",
                requestStartedAtMillis ?: workerStartedAtMillis
            )
            throw error
        } catch (error: DeepSeekException) {
            AiPurificationDiagnostics.log(
                request.traceId,
                "request_failed",
                requestStartedAtMillis ?: workerStartedAtMillis,
                "kind=${error.kind}"
            )
            AppLog.put(
                "AI净化请求失败: ${error.kind}, ${error.detail.orEmpty()}",
                error
            )
            failAiPurify(
                request.key,
                task?.cacheKey?.value,
                context.getString(R.string.ai_reader_purification_failed)
            )
        } catch (error: Exception) {
            AiPurificationDiagnostics.log(
                request.traceId,
                "request_failed",
                requestStartedAtMillis ?: workerStartedAtMillis,
                "kind=${error::class.java.simpleName}"
            )
            AppLog.put("AI净化失败\n${error.localizedMessage}", error)
            failAiPurify(
                request.key,
                task?.cacheKey?.value,
                context.getString(R.string.ai_reader_purification_failed)
            )
        }
    }

    /** Attaches a request that may have started as background work to the visible reader. */
    private fun attachActiveAiStreamToCurrent(key: AiChapterKey): Boolean {
        val session = activeAiStreamSession?.takeIf { it.key == key } ?: return false
        if (!isCurrentAiChapter(key)) return false
        if (session.readerAttached) return true
        session.readerAttached = true
        cancelAiStreamingReload()
        val shouldReload = ReadBook.beginAiStreamingContent(
            bookUrl = key.bookUrl,
            chapterIndex = key.chapterIndex,
            cacheKey = session.cacheKey,
            traceId = session.traceId,
            requestStartedAtMillis = session.requestStartedAtMillis
        )
        if (session.latestCompletedLines.isNotBlank()) {
            ReadBook.updateAiStreamingContent(
                bookUrl = key.bookUrl,
                chapterIndex = key.chapterIndex,
                cacheKey = session.cacheKey,
                content = session.latestCompletedLines,
                traceId = session.traceId,
                requestStartedAtMillis = session.requestStartedAtMillis
            )
        }
        if (shouldReload) {
            AiPurificationDiagnostics.log(
                session.traceId,
                if (session.latestCompletedLines.isBlank()) {
                    "blank_page_reload_requested"
                } else {
                    "background_stream_attached"
                },
                session.requestStartedAtMillis,
                "bufferedChars=${session.latestContent.length} " +
                    "publishedChars=${session.latestCompletedLines.length}"
            )
            ReadBook.reloadAiContent(
                chapterIndex = key.chapterIndex,
                resetPageOffset = true
            )
        }
        return shouldReload
    }

    private fun logAiStreamDiagnostic(
        traceId: String,
        requestStartedAtMillis: Long,
        event: PurificationStreamDiagnostic
    ) {
        val stage: String
        val details: String
        when (event) {
            PurificationStreamDiagnostic.Collecting -> {
                stage = "stream_collecting"
                details = ""
            }

            is PurificationStreamDiagnostic.FirstSseEvent -> {
                stage = "first_sse_event"
                details = "kind=${event.kind}"
            }

            is PurificationStreamDiagnostic.FirstReasoningDelta -> {
                stage = "first_reasoning_delta"
                details = "deltaChars=${event.chars}"
            }

            is PurificationStreamDiagnostic.FirstContentDelta -> {
                stage = "first_content_delta"
                details = "deltaChars=${event.chars}"
            }

            is PurificationStreamDiagnostic.Usage -> {
                stage = "usage"
                val usage = event.value
                details = "promptTokens=${usage.promptTokens ?: -1} " +
                    "completionTokens=${usage.completionTokens ?: -1} " +
                    "reasoningTokens=${usage.completionTokensDetails?.reasoningTokens ?: -1} " +
                    "cacheHitTokens=${usage.promptCacheHitTokens ?: -1} " +
                    "cacheMissTokens=${usage.promptCacheMissTokens ?: -1}"
            }

            is PurificationStreamDiagnostic.Finished -> {
                stage = "finish_event"
                details = "reason=${event.reason.orEmpty()}"
            }

            is PurificationStreamDiagnostic.Completed -> {
                stage = "stream_completed"
                details = "reasoningDeltas=${event.reasoningDeltas} " +
                    "reasoningChars=${event.reasoningChars} " +
                    "contentDeltas=${event.contentDeltas} contentChars=${event.contentChars}"
            }
        }
        AiPurificationDiagnostics.log(traceId, stage, requestStartedAtMillis, details)
    }

    private fun prepareAiPurifyTask(book: Book, chapterIndex: Int): AiPurifyTask? {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return null
        val originalContent = BookHelp.getContent(book, chapter) ?: return null
        if (originalContent.isBlank()) return null
        val config = NovelPurificationRuntimeConfigProvider.current(context)
        val store = AiPurifiedContentStore.get(context)
        return AiPurifyTask(
            book = book,
            chapter = chapter,
            originalContent = originalContent,
            config = config,
            store = store,
            cacheKey = store.keyFor(book, chapter, originalContent, config)
        )
    }

    private fun isCurrentAiChapter(key: AiChapterKey): Boolean {
        return ReadBook.book?.bookUrl == key.bookUrl &&
            ReadBook.durChapterIndex == key.chapterIndex &&
            ReadBook.book?.getUseAiPurify() == true
    }

    private fun markAiPurifyReady(key: AiChapterKey, cacheKey: String) {
        failedAiCacheKeys.remove(cacheKey)
        backgroundFailedAiChapters.remove(key)
        pendingAiOriginalContent.remove(key)
        availableAiOriginalContent.remove(key)
        aiChapterStatuses[key] = AiChapterStatus.READY
        refreshCurrentAiPurifyState()
    }

    private fun failAiPurify(
        key: AiChapterKey,
        cacheKey: String?,
        message: String
    ): AiProcessOutcome {
        val failedInBackground = !isCurrentAiChapter(key)
        cancelAiStreamingReload()
        val removedPartial = ReadBook.clearAiStreamingContent(
            key.bookUrl,
            key.chapterIndex,
            restorePosition = true
        )
        if (removedPartial && isCurrentAiChapter(key)) {
            ReadBook.reloadAiContent(key.chapterIndex)
        }
        cacheKey?.let {
            failedAiCacheKeys.add(it)
        }
        if (failedInBackground) {
            backgroundFailedAiChapters[key] = cacheKey
        } else {
            backgroundFailedAiChapters.remove(key)
        }
        pendingAiOriginalContent.remove(key)
        availableAiOriginalContent.remove(key)
        aiChapterStatuses[key] = AiChapterStatus.FAILED
        refreshCurrentAiPurifyState()
        toastAiFailureIfCurrent(key, message)
        return AiProcessOutcome.FAILED
    }

    private fun toastAiFailureIfCurrent(key: AiChapterKey, message: String) {
        if (isCurrentAiChapter(key)) context.toastOnUi(message)
    }

    private fun validateReadyAiChapter(book: Book, key: AiChapterKey) {
        if (!validatingReadyAiChapters.add(key)) return
        val generation = aiPurifyGeneration
        viewModelScope.launch {
            try {
                val task = runCatching {
                    withContext(IO) {
                        prepareAiPurifyTask(book, key.chapterIndex)
                    }
                }.getOrNull()
                val currentCacheTask = if (task != null && runCatching {
                        withContext(IO) { task.store.read(task.cacheKey) != null }
                    }.getOrDefault(false)
                ) {
                    task
                } else {
                    null
                }

                if (generation != aiPurifyGeneration) return@launch
                if (aiChapterStatuses[key] != AiChapterStatus.READY) return@launch

                if (currentCacheTask != null) {
                    markAiPurifyReady(key, currentCacheTask.cacheKey.value)
                    reloadAiChapterIfRelevant(key, currentCacheTask.cacheKey.value)
                    if (isCurrentAiChapter(key)) {
                        enqueueNextChapterIfNeeded(book, key.chapterIndex)
                    }
                } else {
                    aiChapterStatuses.remove(key)
                    refreshCurrentAiPurifyState()
                    reloadAiChapterIfRelevant(key, cacheKey = null)
                    if (task != null && isCurrentAiChapter(key)) {
                        enqueueCurrentChapter(book, key.chapterIndex, force = false)
                    }
                }
            } finally {
                if (generation == aiPurifyGeneration) {
                    validatingReadyAiChapters.remove(key)
                }
            }
        }
    }

    private fun reloadAiChapterIfRelevant(
        key: AiChapterKey,
        cacheKey: String?,
        force: Boolean = false
    ) {
        if (ReadBook.book?.bookUrl != key.bookUrl) return
        val textChapter = ReadBook.textChapterForIndex(key.chapterIndex)
        if (!force && textChapter?.aiPurifyCacheKey == cacheKey) return
        ReadBook.reloadAiContent(
            chapterIndex = key.chapterIndex,
            resetPageOffset = force && ReadBook.durChapterIndex == key.chapterIndex
        )
    }

    private fun refreshCurrentAiPurifyState() {
        val book = ReadBook.book
        val nextState = if (book == null || !book.getUseAiPurify()) {
            AiPurifyState.Idle
        } else {
            val key = AiChapterKey(book.bookUrl, ReadBook.durChapterIndex)
            when (aiChapterStatuses[key]) {
                AiChapterStatus.RUNNING -> AiPurifyState.Running(
                    key.bookUrl,
                    key.chapterIndex
                )

                AiChapterStatus.READY -> AiPurifyState.Ready(
                    key.bookUrl,
                    key.chapterIndex
                )

                AiChapterStatus.FAILED -> AiPurifyState.Failed(
                    key.bookUrl,
                    key.chapterIndex
                )

                null -> AiPurifyState.Idle
            }
        }
        if (_aiPurifyState.value != nextState) {
            _aiPurifyState.value = nextState
        }
    }

    private fun ensureAiSession(book: Book) {
        if (aiSessionBookUrl != null && aiSessionBookUrl != book.bookUrl) {
            resetAiPurifyQueue()
        }
        aiSessionBookUrl = book.bookUrl
    }

    private fun resetAiPurifyQueue() {
        aiPurifyGeneration++
        cancelAiStreamingReload()
        aiPurifyWorkerJob?.cancel()
        aiPurifyWorkerJob = null
        activeAiChapter = null
        activeAiStreamSession = null
        aiPurifyQueue.clear()
        queuedAiChapters.clear()
        aiChapterStatuses.clear()
        failedAiCacheKeys.clear()
        backgroundFailedAiChapters.clear()
        pendingAiOriginalContent.clear()
        availableAiOriginalContent.clear()
        validatingReadyAiChapters.clear()
        aiSessionBookUrl = null
        ReadBook.clearAllAiStreamingContent(restorePosition = true)
    }

    private fun scheduleAiStreamingReload(key: AiChapterKey) {
        if (aiStreamingReloadJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val delayMillis = (lastAiStreamingReloadAt + AI_STREAMING_RELOAD_INTERVAL_MS - now)
            .coerceAtLeast(0L)
        if (delayMillis == 0L) {
            lastAiStreamingReloadAt = now
            ReadBook.reloadAiContent(key.chapterIndex)
            return
        }
        aiStreamingReloadJob = viewModelScope.launch {
            try {
                delay(delayMillis)
                if (activeAiChapter == key && isCurrentAiChapter(key)) {
                    lastAiStreamingReloadAt = System.currentTimeMillis()
                    ReadBook.reloadAiContent(key.chapterIndex)
                }
            } finally {
                aiStreamingReloadJob = null
            }
        }
    }

    private fun cancelAiStreamingReload() {
        aiStreamingReloadJob?.cancel()
        aiStreamingReloadJob = null
        lastAiStreamingReloadAt = 0L
    }

    private fun invalidResponseMessage(
        result: PurificationResultValidator.Result.Invalid
    ): String {
        return when (result.reason) {
            PurificationResultValidator.FailureReason.ORIGINAL_CHANGED -> context.getString(
                R.string.ai_purification_original_changed
            )
            PurificationResultValidator.FailureReason.LENGTH -> context.getString(
                R.string.ai_purification_response_length
            )
            PurificationResultValidator.FailureReason.CONTENT_FILTER -> context.getString(
                R.string.ai_purification_response_filtered
            )
            PurificationResultValidator.FailureReason.INSUFFICIENT_SYSTEM_RESOURCE ->
                context.getString(R.string.ai_purification_response_resource_interrupted)
            PurificationResultValidator.FailureReason.EMPTY_CONTENT -> context.getString(
                R.string.ai_chat_test_empty_response
            )
            PurificationResultValidator.FailureReason.INCOMPLETE -> context.getString(
                R.string.ai_reader_purification_failed
            )
        }
    }

    fun disableSource() {
        execute {
            ReadBook.bookSource?.let {
                it.enabled = false
                appDb.bookSourceDao.update(it)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (BaseReadAloudService.isRun && BaseReadAloudService.pause) {
            ReadAloud.stop(context)
        }
    }

    private companion object {
        const val AI_STREAMING_RELOAD_INTERVAL_MS = 400L
    }

}
