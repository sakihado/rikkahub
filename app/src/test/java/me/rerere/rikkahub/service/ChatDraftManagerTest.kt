package me.rerere.rikkahub.service

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class ChatDraftManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun TestScope.createManager(
        storageFile: File = File(tempFolder.root, "chat_drafts.json"),
        maxDrafts: Int = 10,
        saveDebounceMs: Long = 300L,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): ChatDraftManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ChatDraftManager(
            storageFile = storageFile,
            scope = backgroundScope,
            maxDrafts = maxDrafts,
            saveDebounceMs = saveDebounceMs,
            ioDispatcher = dispatcher,
            nowMillis = nowMillis,
        )
    }

    @Test
    fun `conversation drafts and per-assistant new chat drafts are isolated`() = runTest {
        val manager = createManager()
        val assistantA = Uuid.random()
        val assistantB = Uuid.random()
        val conv1 = Uuid.random()
        val conv2 = Uuid.random()

        val ownerNewA = Uuid.random()
        val ownerNewB = Uuid.random()
        val ownerConv1 = Uuid.random()

        manager.attachAndRestore(DraftKey.NewChat(assistantA), ownerNewA)
        manager.update(DraftKey.NewChat(assistantA), ownerNewA, ChatDraft(text = "draft for agent A"))
        manager.detach(DraftKey.NewChat(assistantA), ownerNewA, ChatDraft(text = "draft for agent A"))

        assertNull(manager.attachAndRestore(DraftKey.NewChat(assistantB), ownerNewB))
        manager.update(DraftKey.NewChat(assistantB), ownerNewB, ChatDraft(text = "draft for agent B"))
        manager.detach(DraftKey.NewChat(assistantB), ownerNewB, ChatDraft(text = "draft for agent B"))

        manager.attachAndRestore(DraftKey.Conversation(conv1), ownerConv1)
        manager.update(DraftKey.Conversation(conv1), ownerConv1, ChatDraft(text = "reply in conv1"))
        manager.detach(DraftKey.Conversation(conv1), ownerConv1, ChatDraft(text = "reply in conv1"))

        assertEquals("draft for agent A", manager.getDraft(DraftKey.NewChat(assistantA))?.text)
        assertEquals("draft for agent B", manager.getDraft(DraftKey.NewChat(assistantB))?.text)
        assertEquals("reply in conv1", manager.getDraft(DraftKey.Conversation(conv1))?.text)
        assertNull(manager.getDraft(DraftKey.Conversation(conv2)))
    }

    @Test
    fun `debounced persistence writes atomically and restores text and attachments across restarts`() = runTest {
        val storageFile = File(tempFolder.root, "chat_drafts.json")
        val imageFile = tempFolder.newFile("pic.png")
        val docFile = tempFolder.newFile("notes.md")
        val imagePart = UIMessagePart.Image(imageFile.toURI().toString())
        val docPart = UIMessagePart.Document(url = docFile.toURI().toString(), fileName = "notes.md", mime = "text/markdown")

        val assistantId = Uuid.random()
        val convId = Uuid.random()

        val manager1 = createManager(storageFile = storageFile, saveDebounceMs = 300L)
        val owner1 = Uuid.random()
        manager1.attachAndRestore(DraftKey.NewChat(assistantId), owner1)
        manager1.update(
            DraftKey.NewChat(assistantId),
            owner1,
            ChatDraft(text = "first", attachments = listOf(imagePart)),
        )
        manager1.update(
            DraftKey.NewChat(assistantId),
            owner1,
            ChatDraft(text = "updated new chat", attachments = listOf(imagePart)),
        )

        val owner2 = Uuid.random()
        manager1.attachAndRestore(DraftKey.Conversation(convId), owner2)
        manager1.update(
            DraftKey.Conversation(convId),
            owner2,
            ChatDraft(text = "conv draft", attachments = listOf(docPart)),
        )

        // 防抖窗口未到前尚未写盘
        advanceTimeBy(200)
        runCurrent()
        assertFalse(storageFile.exists())

        // 达到 300ms 防抖时间后落盘
        advanceTimeBy(100)
        runCurrent()
        assertTrue(storageFile.isFile)

        // 模拟杀后台重启：新建实例从磁盘恢复
        val manager2 = createManager(storageFile = storageFile)
        val restoredNewChat = manager2.attachAndRestore(DraftKey.NewChat(assistantId), Uuid.random())
        assertNotNull(restoredNewChat)
        assertEquals("updated new chat", restoredNewChat!!.text)
        assertEquals(listOf(imagePart), restoredNewChat.attachments)

        val restoredConv = manager2.attachAndRestore(DraftKey.Conversation(convId), Uuid.random())
        assertNotNull(restoredConv)
        assertEquals("conv draft", restoredConv!!.text)
        assertEquals(listOf(docPart), restoredConv.attachments)
    }

    @Test
    fun `first send promotes new chat slot to conversation slot without orphan deleting sent attachments`() = runTest {
        val storageFile = File(tempFolder.root, "chat_drafts.json")
        val imageFile = tempFolder.newFile("sent.png")
        val imagePart = UIMessagePart.Image(imageFile.toURI().toString())

        val orphaned = mutableSetOf<String>()
        val manager = createManager(storageFile = storageFile)
        manager.setOrphanAttachmentHandler(orphaned::addAll)

        val assistantId = Uuid.random()
        val convId = Uuid.random()
        val ownerId = Uuid.random()
        val newKey = DraftKey.NewChat(assistantId)
        val convKey = DraftKey.Conversation(convId)

        manager.attachAndRestore(newKey, ownerId)
        manager.update(newKey, ownerId, ChatDraft(text = "hello", attachments = listOf(imagePart)))
        advanceTimeBy(300)
        runCurrent()
        assertTrue(storageFile.isFile)

        // 首次发送：清空新对话槽位并升级到正式会话槽位，附件已随消息发出，不能触发孤儿清理
        manager.promoteOnFirstSend(fromKey = newKey, toKey = convKey, ownerId = ownerId)
        assertTrue(orphaned.isEmpty())
        assertNull(manager.getDraft(newKey))

        // 生成回复期间继续输入第二句话，应归属到该正式会话自身，不影响新开对话
        manager.update(convKey, ownerId, ChatDraft(text = "follow up question"))
        advanceTimeBy(300)
        runCurrent()

        val restarted = createManager(storageFile = storageFile)
        assertNull(restarted.getDraft(newKey))
        assertEquals("follow up question", restarted.getDraft(convKey)?.text)
    }

    @Test
    fun `missing local attachment files are filtered out on restore`() = runTest {
        val existingFile = tempFolder.newFile("keep.png")
        val deletedFile = tempFolder.newFile("deleted.png")
        val keepPart = UIMessagePart.Image(existingFile.toURI().toString())
        val deletedPart = UIMessagePart.Image(deletedFile.toURI().toString())
        val remotePart = UIMessagePart.Image("https://example.com/remote.png")

        val manager = createManager()
        val key = DraftKey.Conversation(Uuid.random())
        val owner = Uuid.random()
        manager.attachAndRestore(key, owner)
        manager.update(
            key,
            owner,
            ChatDraft(text = "", attachments = listOf(keepPart, deletedPart, remotePart)),
        )
        manager.detach(
            key,
            owner,
            ChatDraft(text = "", attachments = listOf(keepPart, deletedPart, remotePart)),
        )

        assertTrue(deletedFile.delete())

        val restored = manager.attachAndRestore(key, Uuid.random())
        assertNotNull(restored)
        assertEquals(listOf(keepPart, remotePart), restored!!.attachments)

        // 若唯一附件也被外部清理且无文本，恢复结果应为空并移除空草稿
        val onlyDeletedKey = DraftKey.Conversation(Uuid.random())
        val owner2 = Uuid.random()
        val transientFile = tempFolder.newFile("gone.png")
        val transientPart = UIMessagePart.Image(transientFile.toURI().toString())
        manager.attachAndRestore(onlyDeletedKey, owner2)
        manager.detach(onlyDeletedKey, owner2, ChatDraft(text = "", attachments = listOf(transientPart)))
        assertTrue(transientFile.delete())

        assertNull(manager.attachAndRestore(onlyDeletedKey, Uuid.random()))
    }

    @Test
    fun `editing state survives in-memory navigation but is not persisted as normal draft on restart`() = runTest {
        val storageFile = File(tempFolder.root, "chat_drafts.json")
        val origFile = tempFolder.newFile("history.png")
        val origImage = UIMessagePart.Image(origFile.toURI().toString())
        val messageId = Uuid.random()

        val manager = createManager(storageFile = storageFile)
        val key = DraftKey.Conversation(Uuid.random())
        val owner1 = Uuid.random()

        val editingDraft = ChatDraft(
            text = "edited text",
            attachments = listOf(origImage),
            editingMessage = messageId,
            editingReasoning = "edited thought",
            editingParts = listOf(origImage, UIMessagePart.Text("original text")),
        )
        manager.attachAndRestore(key, owner1)
        manager.update(key, owner1, editingDraft)
        manager.detach(key, owner1, editingDraft)
        advanceTimeBy(300)
        runCurrent()

        // 进程内切回会话：完整恢复编辑上下文
        val inMemoryRestored = manager.attachAndRestore(key, Uuid.random())
        assertNotNull(inMemoryRestored)
        assertEquals(messageId, inMemoryRestored!!.editingMessage)
        assertEquals("edited thought", inMemoryRestored.editingReasoning)
        assertEquals("edited text", inMemoryRestored.text)

        // 进程重启：不把半截历史编辑态当成普通新消息草稿写盘恢复
        val restarted = createManager(storageFile = storageFile)
        assertNull(restarted.attachAndRestore(key, Uuid.random()))
    }

    @Test
    fun `attaching new owner pulls latest snapshot from exiting owner and ignores stale detach`() = runTest {
        val manager = createManager()
        val key = DraftKey.Conversation(Uuid.random())
        val oldOwner = Uuid.random()
        var oldLiveText = "typed right before navigation"

        manager.attachAndRestore(
            key = key,
            ownerId = oldOwner,
            snapshotProvider = { ChatDraft(text = oldLiveText) },
        )

        // 转场动画期间新页面先挂载，旧页面尚未 onCleared：新页面同步拉取旧页面最新快照并接管所有权
        val newOwner = Uuid.random()
        val restored = manager.attachAndRestore(
            key = key,
            ownerId = newOwner,
            snapshotProvider = { ChatDraft(text = "") },
        )
        assertEquals("typed right before navigation", restored?.text)

        // 用户在新页面立即清空或发送
        manager.clearOnSend(key, newOwner)
        assertNull(manager.getDraft(key))

        // 300ms 后旧页面转场结束触发 onCleared()，不能把旧草稿重新写回去
        oldLiveText = "stale text from exiting VM"
        manager.detach(key, oldOwner, ChatDraft(text = oldLiveText))
        assertNull(manager.getDraft(key))
    }

    @Test
    fun `discard revokes active owner notifies callback and returns only unreferenced local attachments`() = runTest {
        val sharedFile = tempFolder.newFile("shared.png")
        val exclusiveFile = tempFolder.newFile("exclusive.png")
        val sharedUrl = sharedFile.toURI().toString()
        val exclusiveUrl = exclusiveFile.toURI().toString()

        val manager = createManager()
        val key1 = DraftKey.Conversation(Uuid.random())
        val key2 = DraftKey.Conversation(Uuid.random())
        val owner1 = Uuid.random()
        val owner2 = Uuid.random()
        var discardedCallbackCount = 0

        manager.attachAndRestore(
            key = key1,
            ownerId = owner1,
            snapshotProvider = {
                ChatDraft(
                    text = "draft 1",
                    attachments = listOf(UIMessagePart.Image(sharedUrl), UIMessagePart.Image(exclusiveUrl)),
                )
            },
            onDiscarded = { discardedCallbackCount++ },
        )
        manager.update(
            key1,
            owner1,
            ChatDraft(
                text = "draft 1",
                attachments = listOf(UIMessagePart.Image(sharedUrl), UIMessagePart.Image(exclusiveUrl)),
            ),
        )

        manager.attachAndRestore(key2, owner2)
        manager.detach(key2, owner2, ChatDraft(text = "draft 2", attachments = listOf(UIMessagePart.Image(sharedUrl))))

        val candidates = manager.discard(key1)
        assertEquals(1, discardedCallbackCount)
        assertEquals(setOf(exclusiveUrl), candidates)

        // 被丢弃会话的旧 VM 销毁时不能再把草稿写回
        manager.detach(key1, owner1, ChatDraft(text = "resurrected"))
        assertNull(manager.getDraft(key1))
    }

    @Test
    fun `lru eviction prefers inactive drafts and reports evicted attachment urls`() = runTest {
        var clock = 1_000L
        val evictedUrls = mutableSetOf<String>()
        val manager = createManager(maxDrafts = 2, nowMillis = { clock })
        manager.setOrphanAttachmentHandler(evictedUrls::addAll)

        val file1 = tempFolder.newFile("old.png")
        val url1 = file1.toURI().toString()

        val activeKey = DraftKey.Conversation(Uuid.random())
        val inactiveOldKey = DraftKey.Conversation(Uuid.random())
        val newestKey = DraftKey.Conversation(Uuid.random())

        // activeKey 时间最早，但正处于活跃打开状态，不应被优先淘汰
        val activeOwner = Uuid.random()
        manager.attachAndRestore(activeKey, activeOwner)
        manager.update(activeKey, activeOwner, ChatDraft(text = "currently open"))

        clock = 2_000L
        val inactiveOwner = Uuid.random()
        manager.attachAndRestore(inactiveOldKey, inactiveOwner)
        manager.detach(
            inactiveOldKey,
            inactiveOwner,
            ChatDraft(text = "old background draft", attachments = listOf(UIMessagePart.Image(url1))),
        )

        clock = 3_000L
        val newestOwner = Uuid.random()
        manager.attachAndRestore(newestKey, newestOwner)
        manager.detach(newestKey, newestOwner, ChatDraft(text = "newest draft"))

        assertEquals("currently open", manager.getDraft(activeKey)?.text)
        assertNull(manager.getDraft(inactiveOldKey))
        assertEquals("newest draft", manager.getDraft(newestKey)?.text)
        assertEquals(setOf(url1), evictedUrls)
    }

    @Test
    fun `corrupted draft json file recovers gracefully without crashing`() = runTest {
        val storageFile = tempFolder.newFile("chat_drafts.json")
        storageFile.writeText("{ invalid json", Charsets.UTF_8)

        val warnings = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = ChatDraftManager(
            storageFile = storageFile,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            logWarning = { msg, _ -> warnings.add(msg) },
        )

        val key = DraftKey.NewChat(Uuid.random())
        assertNull(manager.getDraft(key))
        assertEquals(1, warnings.size)

        val owner = Uuid.random()
        manager.attachAndRestore(key, owner)
        manager.update(key, owner, ChatDraft(text = "fresh after recovery"))
        advanceTimeBy(300)
        runCurrent()

        val reloaded = createManager(storageFile = storageFile)
        assertEquals("fresh after recovery", reloaded.getDraft(key)?.text)
    }
}
