package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.net.URI
import java.net.URLDecoder
import kotlin.io.resolveSibling
import kotlin.uuid.Uuid

private const val TAG = "ChatDraftManager"
private const val DEFAULT_MAX_DRAFTS = 100
private const val DEFAULT_SAVE_DEBOUNCE_MS = 300L

sealed interface DraftKey {
    val storageKey: String

    /** 已经开始（已落库、正在生成或已有排队消息）的会话，按会话 ID 绑定 */
    data class Conversation(val conversationId: Uuid) : DraftKey {
        override val storageKey: String = "$CONV_PREFIX$conversationId"
    }

    /** 尚未发出第一条消息的空白新对话，按助手 ID 各自独立保留（方案 1A） */
    data class NewChat(val assistantId: Uuid) : DraftKey {
        override val storageKey: String = "$NEW_PREFIX$assistantId"
    }

    companion object {
        private const val CONV_PREFIX = "conv:"
        private const val NEW_PREFIX = "new:"

        fun parse(raw: String): DraftKey? = when {
            raw.startsWith(CONV_PREFIX) -> runCatching {
                Conversation(Uuid.parse(raw.removePrefix(CONV_PREFIX)))
            }.getOrNull()

            raw.startsWith(NEW_PREFIX) -> runCatching {
                NewChat(Uuid.parse(raw.removePrefix(NEW_PREFIX)))
            }.getOrNull()

            else -> null
        }
    }
}

@Serializable
data class ChatDraft(
    val text: String = "",
    val attachments: List<UIMessagePart> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    // 编辑历史消息的上下文仅在当次运行内存里保留，重启后不恢复半截编辑态以免节点已变或误删原消息附件
    @Transient val editingMessage: Uuid? = null,
    @Transient val editingReasoning: String? = null,
    @Transient val editingParts: List<UIMessagePart>? = null,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && attachments.isEmpty() && editingMessage == null

    /** 是否需要写入磁盘（仅持久化普通未发送草稿，排除空草稿和半截历史编辑态） */
    val isPersistable: Boolean
        get() = editingMessage == null && (text.isNotBlank() || attachments.isNotEmpty())

    fun hasSameContent(other: ChatDraft): Boolean =
        text == other.text &&
            attachments == other.attachments &&
            editingMessage == other.editingMessage &&
            editingReasoning == other.editingReasoning &&
            editingParts == other.editingParts

    fun localAttachmentUrls(): Set<String> =
        (attachments + editingParts.orEmpty()).draftLocalFileUrls()
}

@Serializable
private data class PersistedDraftsPayload(
    val version: Int = 1,
    val drafts: Map<String, ChatDraft> = emptyMap(),
)

/**
 * 全局聊天输入草稿管理器：
 * - 内存层实时读写，支持跨 `clearAndNavigate` 页面切换保留文本、未发附件及内存态消息编辑上下文；
 * - 磁盘层 300ms 防抖原子写入 `chat_drafts.json`，跨进程重启保留未发文本与本地附件；
 * - 追踪每个槽位的当前活跃持有者（`ownerId`），防止页面转场动画期间旧 `ChatVM.onCleared()` 晚到覆盖新状态；
 * - 在草稿被丢弃、覆盖或 LRU 淘汰时回调清理未引用的本地附件文件，避免产生磁盘孤儿文件。
 */
class ChatDraftManager(
    private val storageFile: File,
    private val scope: CoroutineScope,
    private val json: Json = JsonInstant,
    private val maxDrafts: Int = DEFAULT_MAX_DRAFTS,
    private val saveDebounceMs: Long = DEFAULT_SAVE_DEBOUNCE_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fileExists: (String) -> Boolean = ::defaultLocalFileExists,
    private val logWarning: (String, Throwable) -> Unit = { message, error ->
        runCatching { Log.w(TAG, message, error) }
    },
) {
    private class ActiveOwner(
        val ownerId: Uuid,
        val snapshotProvider: () -> ChatDraft,
        val onDiscarded: () -> Unit,
    )

    private val lock = Any()
    private val saveJobLock = Any()
    private val diskWriteLock = Any()
    private var loaded = false
    private var saveJob: Job? = null
    private val drafts = LinkedHashMap<DraftKey, ChatDraft>()
    private val activeOwners = HashMap<DraftKey, ActiveOwner>()

    @Volatile
    private var orphanAttachmentHandler: ((Set<String>) -> Unit)? = null

    init {
        require(maxDrafts >= 1) { "maxDrafts must be >= 1" }
    }

    fun setOrphanAttachmentHandler(handler: (Set<String>) -> Unit) {
        orphanAttachmentHandler = handler
    }

    fun ensureLoaded() {
        synchronized(lock) {
            ensureLoadedLocked()
        }
    }

    /**
     * 绑定当前页面持有者并读取该槽位的草稿。
     * 若同一槽位的旧 `ChatVM` 仍处于退出动画尚未 `onCleared()`，先同步拉取其最新快照再移交所有权。
     */
    fun attachAndRestore(
        key: DraftKey,
        ownerId: Uuid,
        snapshotProvider: () -> ChatDraft = { ChatDraft() },
        onDiscarded: () -> Unit = {},
    ): ChatDraft? {
        var persistNeeded = false
        val orphaned = mutableSetOf<String>()
        val result = synchronized(lock) {
            ensureLoadedLocked()
            val previousOwner = activeOwners[key]
            if (previousOwner != null && previousOwner.ownerId != ownerId) {
                val latestFromPrevious = runCatching { previousOwner.snapshotProvider() }.getOrNull()
                if (latestFromPrevious != null) {
                    if (putOrRemoveLocked(key, latestFromPrevious, orphaned)) {
                        persistNeeded = true
                    }
                }
            }
            activeOwners[key] = ActiveOwner(
                ownerId = ownerId,
                snapshotProvider = snapshotProvider,
                onDiscarded = onDiscarded,
            )
            sanitizeAndGetLocked(key).also { (_, changed) ->
                if (changed) persistNeeded = true
            }.first.also {
                orphaned.removeAll(allReferencedUrlsLocked())
            }
        }
        if (persistNeeded) requestSave()
        notifyOrphanAttachments(orphaned)
        return result
    }

    /**
     * 外部传入初始输入（如系统分享文本/图片）时直接绑定所有权并写入初始草稿，不恢复旧草稿。
     */
    fun bindOwner(
        key: DraftKey,
        ownerId: Uuid,
        initialDraft: ChatDraft,
        snapshotProvider: () -> ChatDraft = { ChatDraft() },
        onDiscarded: () -> Unit = {},
    ) {
        var persistNeeded = false
        val orphaned = mutableSetOf<String>()
        synchronized(lock) {
            ensureLoadedLocked()
            activeOwners[key] = ActiveOwner(
                ownerId = ownerId,
                snapshotProvider = snapshotProvider,
                onDiscarded = onDiscarded,
            )
            if (putOrRemoveLocked(key, initialDraft, orphaned)) {
                persistNeeded = true
            }
            orphaned.removeAll(allReferencedUrlsLocked())
        }
        if (persistNeeded) requestSave()
        notifyOrphanAttachments(orphaned)
    }

    /**
     * 实时同步输入状态快照到内存，并在需要落盘时触发防抖写盘。
     */
    fun update(key: DraftKey, ownerId: Uuid, draft: ChatDraft) {
        var persistNeeded = false
        val orphaned = mutableSetOf<String>()
        synchronized(lock) {
            ensureLoadedLocked()
            val currentOwner = activeOwners[key]
            if (currentOwner != null && currentOwner.ownerId != ownerId) {
                return
            }
            if (putOrRemoveLocked(key, draft, orphaned)) {
                persistNeeded = true
            }
            orphaned.removeAll(allReferencedUrlsLocked())
        }
        if (persistNeeded) requestSave()
        notifyOrphanAttachments(orphaned)
    }

    /**
     * 空白新对话首次发送消息时的槽位交接：
     * 清空 `new:$assistantId` 槽位（附件已移交给消息，不作为孤儿删除），并将当前页面切换到 `conv:$conversationId`。
     */
    fun promoteOnFirstSend(
        fromKey: DraftKey.NewChat,
        toKey: DraftKey.Conversation,
        ownerId: Uuid,
        snapshotProvider: () -> ChatDraft = { ChatDraft() },
        onDiscarded: () -> Unit = {},
    ) {
        var persistNeeded = false
        synchronized(lock) {
            ensureLoadedLocked()
            if (activeOwners[fromKey]?.ownerId == ownerId) {
                activeOwners.remove(fromKey)
            }
            val removed = drafts.remove(fromKey)
            if (removed?.isPersistable == true) {
                persistNeeded = true
            }
            activeOwners[toKey] = ActiveOwner(
                ownerId = ownerId,
                snapshotProvider = snapshotProvider,
                onDiscarded = onDiscarded,
            )
        }
        if (persistNeeded) requestSave()
    }

    /**
     * 会话未通过输入框发送但已落库开始（例如语音模式发消息后），将当前未发草稿平滑迁移到正式会话槽位。
     */
    fun transferDraftKey(
        fromKey: DraftKey,
        toKey: DraftKey,
        ownerId: Uuid,
        currentDraft: ChatDraft,
        snapshotProvider: () -> ChatDraft = { ChatDraft() },
        onDiscarded: () -> Unit = {},
    ) {
        if (fromKey == toKey) {
            update(toKey, ownerId, currentDraft)
            return
        }
        var persistNeeded = false
        val orphaned = mutableSetOf<String>()
        synchronized(lock) {
            ensureLoadedLocked()
            if (activeOwners[fromKey]?.ownerId == ownerId) {
                activeOwners.remove(fromKey)
            }
            val removed = drafts.remove(fromKey)
            if (removed?.isPersistable == true) {
                persistNeeded = true
            }
            activeOwners[toKey] = ActiveOwner(
                ownerId = ownerId,
                snapshotProvider = snapshotProvider,
                onDiscarded = onDiscarded,
            )
            if (putOrRemoveLocked(toKey, currentDraft, orphaned)) {
                persistNeeded = true
            }
            orphaned.removeAll(allReferencedUrlsLocked())
        }
        if (persistNeeded) requestSave()
        notifyOrphanAttachments(orphaned)
    }

    /**
     * 已开始的会话点击发送或完成消息编辑时清除草稿（附件已移交消息，不触发孤儿文件删除）。
     */
    fun clearOnSend(key: DraftKey, ownerId: Uuid) {
        var persistNeeded = false
        synchronized(lock) {
            ensureLoadedLocked()
            val currentOwner = activeOwners[key]
            if (currentOwner != null && currentOwner.ownerId != ownerId) {
                return
            }
            val removed = drafts.remove(key)
            if (removed?.isPersistable == true) {
                persistNeeded = true
            }
        }
        if (persistNeeded) requestSave()
    }

    /**
     * `ChatVM.onCleared()` 兜底同步：仅当当前 `ownerId` 仍是该槽位持有者时写入最终快照并释放持有权。
     */
    fun detach(key: DraftKey, ownerId: Uuid, finalDraft: ChatDraft) {
        var persistNeeded = false
        val orphaned = mutableSetOf<String>()
        synchronized(lock) {
            ensureLoadedLocked()
            val currentOwner = activeOwners[key]
            if (currentOwner == null || currentOwner.ownerId != ownerId) {
                return
            }
            activeOwners.remove(key)
            if (putOrRemoveLocked(key, finalDraft, orphaned)) {
                persistNeeded = true
            }
            orphaned.removeAll(allReferencedUrlsLocked())
        }
        if (persistNeeded) requestSave()
        notifyOrphanAttachments(orphaned)
    }

    /**
     * 删除会话或删除助手时彻底丢弃草稿，返回该草稿曾引用的本地文件 URL（已排除其他草稿仍在引用的文件）。
     */
    fun discard(key: DraftKey): Set<String> {
        var persistNeeded = false
        var discardedOwner: ActiveOwner? = null
        val orphaned = mutableSetOf<String>()
        synchronized(lock) {
            ensureLoadedLocked()
            discardedOwner = activeOwners.remove(key)
            val liveUrls = runCatching {
                discardedOwner?.snapshotProvider?.invoke()?.localAttachmentUrls()
            }.getOrNull().orEmpty()
            orphaned.addAll(liveUrls)

            val removed = drafts.remove(key)
            if (removed != null) {
                orphaned.addAll(removed.localAttachmentUrls())
                if (removed.isPersistable) {
                    persistNeeded = true
                }
            }
            orphaned.removeAll(allReferencedUrlsLocked())
        }
        if (persistNeeded) requestSave()
        discardedOwner?.onDiscarded?.invoke()
        return orphaned
    }

    fun getDraft(key: DraftKey): ChatDraft? {
        var persistNeeded = false
        val draft = synchronized(lock) {
            ensureLoadedLocked()
            sanitizeAndGetLocked(key).also { (_, changed) ->
                if (changed) persistNeeded = true
            }.first
        }
        if (persistNeeded) requestSave()
        return draft
    }

    /** 当前所有草稿（含活跃输入框）引用的本地 `file://` 附件集合，用于防止垃圾清理误删 */
    fun allReferencedLocalFileUrls(): Set<String> = synchronized(lock) {
        ensureLoadedLocked()
        allReferencedUrlsLocked()
    }

    internal fun flushToDisk() {
        synchronized(diskWriteLock) {
            val snapshot: Map<String, ChatDraft> = synchronized(lock) {
                ensureLoadedLocked()
                drafts.entries
                    .filter { (_, draft) -> draft.isPersistable }
                    .associate { (key, draft) -> key.storageKey to draft }
            }
            try {
                val tmpFile = storageFile.resolveSibling("${storageFile.name}.tmp")
                if (snapshot.isEmpty()) {
                    if (storageFile.exists()) storageFile.delete()
                    if (tmpFile.exists()) tmpFile.delete()
                    return
                }
                storageFile.parentFile?.mkdirs()
                val encoded = json.encodeToString(PersistedDraftsPayload(drafts = snapshot))
                tmpFile.writeText(encoded, Charsets.UTF_8)
                if (!tmpFile.renameTo(storageFile)) {
                    tmpFile.copyTo(storageFile, overwrite = true)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                logWarning("Failed to persist chat drafts to ${storageFile.absolutePath}", e)
            }
        }
    }

    private fun requestSave() {
        synchronized(saveJobLock) {
            saveJob?.cancel()
            saveJob = scope.launch {
                if (saveDebounceMs > 0) {
                    delay(saveDebounceMs)
                }
                withContext(ioDispatcher) {
                    flushToDisk()
                }
            }
        }
    }

    private fun notifyOrphanAttachments(urls: Set<String>) {
        if (urls.isNotEmpty()) {
            orphanAttachmentHandler?.invoke(urls)
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!storageFile.isFile) return
        try {
            val rawText = storageFile.readText(Charsets.UTF_8)
            if (rawText.isBlank()) return
            val payload = json.decodeFromString<PersistedDraftsPayload>(rawText)
            payload.drafts.entries
                .mapNotNull { (rawKey, draft) ->
                    val parsedKey = DraftKey.parse(rawKey) ?: return@mapNotNull null
                    if (draft.isEmpty) return@mapNotNull null
                    parsedKey to draft
                }
                .sortedBy { (_, draft) -> draft.updatedAt }
                .takeLast(maxDrafts)
                .forEach { (key, draft) ->
                    drafts[key] = draft
                }
        } catch (e: Exception) {
            logWarning("Failed to read chat drafts from ${storageFile.absolutePath}, resetting", e)
            drafts.clear()
        }
    }

    /**
     * 更新或移除内存草稿，执行 LRU 容量淘汰。
     * @return 磁盘持久化内容是否发生变化
     */
    private fun putOrRemoveLocked(
        key: DraftKey,
        incoming: ChatDraft,
        displacedUrls: MutableSet<String>?,
    ): Boolean {
        val previous = drafts[key]
        if (incoming.isEmpty) {
            val removed = drafts.remove(key) ?: return false
            displacedUrls?.addAll(removed.localAttachmentUrls())
            return removed.isPersistable
        }

        if (previous != null && previous.hasSameContent(incoming)) {
            return false
        }

        val stored = incoming.copy(updatedAt = nowMillis())
        drafts[key] = stored
        if (previous != null) {
            displacedUrls?.addAll(previous.localAttachmentUrls() - stored.localAttachmentUrls())
        }

        var persistChanged = previous?.isPersistable == true || stored.isPersistable
        while (drafts.size > maxDrafts) {
            val candidate = drafts.entries
                .filter { it.key != key && it.key !in activeOwners }
                .minByOrNull { it.value.updatedAt }
                ?: drafts.entries
                    .filter { it.key != key }
                    .minByOrNull { it.value.updatedAt }
                ?: break
            val evicted = drafts.remove(candidate.key)
            if (evicted != null) {
                displacedUrls?.addAll(evicted.localAttachmentUrls())
                if (evicted.isPersistable) persistChanged = true
            }
        }
        return persistChanged
    }

    private fun sanitizeAndGetLocked(key: DraftKey): Pair<ChatDraft?, Boolean> {
        val existing = drafts[key] ?: return null to false
        val validAttachments = existing.attachments.filter(::isAttachmentAvailable)
        if (validAttachments.size == existing.attachments.size) {
            return existing to false
        }
        val cleaned = existing.copy(attachments = validAttachments)
        return if (cleaned.isEmpty) {
            drafts.remove(key)
            null to existing.isPersistable
        } else {
            drafts[key] = cleaned
            cleaned to existing.isPersistable
        }
    }

    private fun isAttachmentAvailable(part: UIMessagePart): Boolean {
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Document -> part.url
            else -> null
        } ?: return true
        return if (url.startsWith("file:")) fileExists(url) else true
    }

    private fun allReferencedUrlsLocked(): Set<String> = buildSet {
        drafts.values.forEach { addAll(it.localAttachmentUrls()) }
        activeOwners.values.forEach { owner ->
            runCatching { owner.snapshotProvider().localAttachmentUrls() }
                .getOrNull()
                ?.let(::addAll)
        }
    }
}

private fun List<UIMessagePart>.draftLocalFileUrls(): Set<String> = buildSet {
    this@draftLocalFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.draftLocalFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file:") == true) add(url)
    }
}

internal fun resolveLocalFileFromUrl(url: String): File? {
    if (!url.startsWith("file:")) return null
    return runCatching { File(URI(url)) }.getOrElse {
        val rawPath = url.removePrefix("file://").removePrefix("file:")
        if (rawPath.isBlank()) null
        else runCatching {
            File(URLDecoder.decode(rawPath, Charsets.UTF_8.name()))
        }.getOrElse { File(rawPath) }
    }
}

private fun defaultLocalFileExists(url: String): Boolean {
    val file = resolveLocalFileFromUrl(url) ?: return true
    return file.exists()
}
