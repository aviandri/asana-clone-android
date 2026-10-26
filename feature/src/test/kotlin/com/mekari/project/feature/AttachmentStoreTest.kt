package com.mekari.project.feature

import com.mekari.project.core.ApiError
import com.mekari.project.core.Attachment
import com.mekari.project.core.AttachmentKind
import com.mekari.project.core.AttachmentLimits
import com.mekari.project.core.Comment
import com.mekari.project.core.FileUploadSource
import com.mekari.project.core.ThumbnailSize
import com.mekari.project.core.TransferProgress
import com.mekari.project.core.fake.DemoIds
import com.mekari.project.core.fake.FakeApi
import com.mekari.project.core.fake.FakeEndpoint
import com.mekari.project.core.fake.FakeSeed
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Composer drafts, session-scoped task uploads, downloads and the wipe rules (SPEC-ANDROID §8). */
class AttachmentStoreTest {
    private lateinit var scratch: File

    @Before
    fun setUp() {
        scratch = Files.createTempDirectory("att-store").toFile()
    }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    private fun file(name: String, text: String = "hello", owned: Boolean = false): FileUploadSource =
        FileUploadSource(File(scratch, name).apply { writeText(text) }, owned = owned)

    private fun sparse(size: Long, name: String = "big.bin"): FileUploadSource {
        val f = File(scratch, name)
        RandomAccessFile(f, "rw").use { it.setLength(size) }
        return FileUploadSource(f)
    }

    private fun calls(fake: FakeApi, endpoint: FakeEndpoint) = fake.calls.count { it.endpoint == endpoint }

    private suspend fun TestScope.openTask(
        fake: FakeApi = demoFake(),
        id: String = DemoIds.TASK_WITH_ATTACHMENTS
    ): Pair<AppSession, TaskDetailStore> {
        val session = startedSession(fake)
        session.scope.value!!.poller.stop()
        val store = session.scope.value!!.taskDetail(id)
        store.load()
        return session to store
    }

    private val TaskDetailStore.attachments get() = state.value.attachments
    private val TaskDetailStore.uploads get() = state.value.uploads
    private val CommentComposer.chips get() = state.value.attachments

    // Task detail

    @Test
    fun loadsAttachmentsNewestFirstWithCommentOnes() = runTest {
        val (_, store) = openTask()
        assertEquals(
            listOf(
                DemoIds.ATTACHMENT_COMMENT_IMAGE,
                DemoIds.ATTACHMENT_LINK,
                DemoIds.ATTACHMENT_TEXT,
                DemoIds.ATTACHMENT_PDF,
                DemoIds.ATTACHMENT_PNG
            ),
            store.attachments.map { it.id }
        )
        assertEquals(5, store.state.value.task?.attachmentCount)
        val comment = store.state.value.stories.filterIsInstance<Comment>().first { it.attachments.isNotEmpty() }
        assertEquals(listOf(DemoIds.ATTACHMENT_COMMENT_IMAGE), comment.attachments.map { it.id })
        assertTrue(store.state.value.canAttach)
    }

    @Test
    fun deletePermissions() = runTest {
        val (_, store) = openTask()
        assertTrue(store.canDelete(store.attachments.first { it.id == DemoIds.ATTACHMENT_PDF })) // alice uploaded it
        // admin uploaded the PNG; alice is an EDITOR there
        assertFalse(store.canDelete(store.attachments.first { it.id == DemoIds.ATTACHMENT_PNG }))
    }

    @Test
    fun aTaskLevelUploadLandsAndBumpsTheCount() = runTest {
        val (_, store) = openTask()
        store.attach(file("screenshot.png"))
        runCurrent()
        assertEquals("screenshot.png", store.attachments.first().name)
        assertFalse(store.attachments.first().draft)
        assertEquals(6, store.state.value.task?.attachmentCount)
        assertEquals("finished task uploads leave the list", emptyList<AttachmentUpload>(), store.uploads)
    }

    @Test
    fun over100MBFailsAtOnceWithoutARequest() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        store.attach(sparse(AttachmentLimits.MAX_UPLOAD_BYTES + 1))
        val state = store.uploads.single().state as AttachmentUpload.State.Failed
        assertEquals(ApiError.Code.PAYLOAD_TOO_LARGE, state.error.code)
        assertNull(state.error.status)
        assertEquals(0, calls(fake, FakeEndpoint.UPLOAD_ATTACHMENT))
        assertTrue(store.cancelUpload(store.uploads.single().id))
        assertEquals(emptyList<AttachmentUpload>(), store.uploads)
    }

    @Test
    fun taskUploadsSurviveLeavingTheTaskAndReappear() = runTest {
        val fake = demoFake()
        val (session, store) = openTask(fake)
        fake.hold(FakeEndpoint.UPLOAD_ATTACHMENT)
        store.attach(file("later.txt"))
        runCurrent()
        assertEquals(1, fake.parkedCount(FakeEndpoint.UPLOAD_ATTACHMENT))
        // Another workspace and back: the scope (and its stores) are replaced, the upload isn't.
        session.switchWorkspace(DemoIds.ACME_PILOT)
        assertEquals(1, session.uploads.uploads(DemoIds.TASK_WITH_ATTACHMENTS).size)
        session.switchWorkspace(DemoIds.MEKARI)
        val reopened = session.scope.value!!.taskDetail(DemoIds.TASK_WITH_ATTACHMENTS)
        assertTrue(reopened !== store)
        reopened.load()
        assertEquals(listOf("later.txt"), reopened.uploads.map { it.name })
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT, all = true)
        runCurrent()
        assertTrue(reopened.attachments.any { it.name == "later.txt" })
        assertEquals(emptyList<AttachmentUpload>(), reopened.uploads)
    }

    @Test
    fun aFailedUploadKeepsTheServerMessageAndRetries() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        fake.failNext(FakeEndpoint.UPLOAD_ATTACHMENT, ApiError(ApiError.Code.INSUFFICIENT_STORAGE, 507, "Out of space"))
        store.attach(file("x.txt"))
        runCurrent()
        val failed = store.uploads.single().state as AttachmentUpload.State.Failed
        assertEquals(ApiError.Code.INSUFFICIENT_STORAGE, failed.error.code)
        assertEquals("Out of space", failed.error.message)
        store.retryUpload(store.uploads.single().id)
        runCurrent()
        assertTrue(store.uploads.isEmpty())
        assertTrue(store.attachments.any { it.name == "x.txt" })
    }

    @Test
    fun a429IsRetriedOnceButNotAfterTheWholeBody() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        fake.failNext(FakeEndpoint.UPLOAD_ATTACHMENT, ApiError(ApiError.Code.RATE_LIMITED, 429, "Slow down"))
        store.attach(file("retry.txt"))
        runCurrent()
        assertTrue(store.attachments.any { it.name == "retry.txt" })
        assertEquals(2, calls(fake, FakeEndpoint.UPLOAD_ATTACHMENT))

        fake.failNextUploadAfterBody(ApiError(ApiError.Code.RATE_LIMITED, 429, "Draft quota"))
        store.composer.attach(file("quota.txt"))
        runCurrent()
        assertEquals("a fully sent body isn't re-sent", 3, calls(fake, FakeEndpoint.UPLOAD_ATTACHMENT))
        assertTrue(store.composer.chips.single().isFailed)
        assertTrue("not stuck in finishing", store.composer.remove(store.composer.chips.single().id))
    }

    @Test
    fun addLinkReportsForThePopoverAndAddsOnSuccess() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val bad = store.addLink("javascript:alert(1)", null) as LinkResult.Failed
        assertEquals(ApiError.Code.VALIDATION, bad.error.code)
        assertNotNull(bad.error.issue("url"))
        assertEquals("checked locally first", 0, calls(fake, FakeEndpoint.ADD_LINK))
        assertEquals(LinkResult.Added, store.addLink("https://github.com/aviandri", "Repo"))
        assertEquals("Repo", store.attachments.first().title)
    }

    @Test
    fun linksWaitForAnUploadSlot() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        fake.hold(FakeEndpoint.UPLOAD_ATTACHMENT)
        repeat(3) { store.attach(file("u$it.txt")) }
        runCurrent()
        assertEquals(3, fake.parkedCount(FakeEndpoint.UPLOAD_ATTACHMENT))
        val linking = async { store.addLink("https://example.com/spec", null) }
        runCurrent()
        assertEquals("the BE counts links in the 3 (A12)", 0, calls(fake, FakeEndpoint.ADD_LINK))
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT)
        runCurrent()
        assertEquals(LinkResult.Added, linking.await())
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT, all = true)
        runCurrent()
    }

    @Test
    fun commentersCantAttachToTheTask() = runTest {
        val fake = demoFake()
        val session = startedSession(fake)
        session.scope.value!!.projects.load()
        val readOnly = fake.currentTasks().first { it.projectId == DemoIds.READ_ONLY_PROJECT }
        val store = session.scope.value!!.taskDetail(readOnly.id)
        store.load()
        assertFalse(store.state.value.canAttach)
    }

    @Test
    fun deleteIsOptimisticAndRestoresOnFailure() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        fake.hold(FakeEndpoint.DELETE_ATTACHMENT)
        val deleting = launch { store.deleteAttachment(DemoIds.ATTACHMENT_PDF) }
        runCurrent()
        assertFalse("gone at once", store.attachments.any { it.id == DemoIds.ATTACHMENT_PDF })
        fake.failNext(FakeEndpoint.DELETE_ATTACHMENT, ApiError(ApiError.Code.FORBIDDEN, 403, "No"))
        fake.release(FakeEndpoint.DELETE_ATTACHMENT, all = true)
        deleting.join()
        assertTrue("back after the failure", store.attachments.any { it.id == DemoIds.ATTACHMENT_PDF })
        assertEquals(ApiError.Code.FORBIDDEN, store.state.value.lastError?.code)
    }

    @Test
    fun deletingTheLastAttachmentOfAnEmptyCommentRemovesTheComment() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        assertTrue(store.composer.attach(file("only.png")))
        runCurrent()
        assertTrue(store.composer.chips.single().isFinished)
        assertTrue("attachments alone can be sent", store.composer.canSend(""))
        assertTrue(store.composer.send(""))
        val posted = store.state.value.stories.last() as Comment
        assertEquals("", posted.body)
        val attachmentId = posted.attachments.single().id
        assertTrue("comment attachments are in the task list too", store.attachments.any { it.id == attachmentId })
        store.deleteAttachment(attachmentId)
        assertFalse(store.state.value.stories.any { it.key == posted.key })
        assertFalse(
            "the server dropped it too",
            fake.stories(DemoIds.TASK_WITH_ATTACHMENTS).any {
                it.key == posted.key
            }
        )
    }

    @Test
    fun deletingACommentDropsItsAttachmentsFromTheTask() = runTest {
        // bob's comment carries the seeded image; an admin may delete any comment.
        val session = startedSession(FakeApi(FakeSeed.demo(TestTime.today), signedInAs = "admin@example.com"))
        val store = session.scope.value!!.taskDetail(DemoIds.TASK_WITH_ATTACHMENTS)
        store.load()
        val commentId = store.attachments.first { it.id == DemoIds.ATTACHMENT_COMMENT_IMAGE }.commentId!!
        store.deleteComment(commentId)
        assertFalse(store.attachments.any { it.id == DemoIds.ATTACHMENT_COMMENT_IMAGE })
        assertEquals(4, store.state.value.task?.attachmentCount)
    }

    @Test
    fun anEmptyEditKeepsACommentThatHasAttachments() = runTest {
        val (_, store) = openTask()
        store.composer.attach(file("f.txt"))
        runCurrent()
        store.composer.send("with a file")
        val comment = store.state.value.stories.last() as Comment
        store.editComment(comment.id, "  ")
        assertEquals("A9: the body may go empty", "", (store.state.value.stories.last() as Comment).body)
        assertNull(store.state.value.lastError)
    }

    @Test
    fun aFailedPreCheckIsDroppedWithItsWorkspace() = runTest {
        val (session, store) = openTask()
        store.attach(sparse(AttachmentLimits.MAX_UPLOAD_BYTES + 1))
        assertEquals(1, session.uploads.state.value.size)
        session.uploads.cancelWorkspace(DemoIds.MEKARI)
        assertEquals(emptyList<AttachmentUpload>(), session.uploads.state.value)
    }

    // Composer

    @Test
    fun theComposerPostsDraftIdsAndClearsOnlyWhatItSent() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("a.txt"))
        runCurrent()
        val draft = composer.chips.single().attachment!!
        assertTrue(draft.draft)
        assertTrue("uploaded with ?draft=true", fake.currentAttachments().single { it.id == draft.id }.draft)
        fake.hold(FakeEndpoint.ADD_COMMENT)
        val sending = async { composer.send("See attached") }
        runCurrent()
        assertEquals(setOf(composer.chips.single().id), composer.state.value.sendingIds)
        assertFalse("a chip being posted can't be removed", composer.remove(composer.chips.single().id))
        composer.attach(file("b.txt")) // added mid-send: stays for the next comment
        fake.release(FakeEndpoint.ADD_COMMENT, all = true)
        assertTrue(sending.await())
        runCurrent()
        assertEquals(listOf("b.txt"), composer.chips.map { it.name })
        val comment = store.state.value.stories.last() as Comment
        assertEquals(listOf(draft.id), comment.attachments.map { it.id })
        assertEquals("See attached", comment.body)
    }

    @Test
    fun sendWaitsForUploadsButNotForFailedChips() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        fake.hold(FakeEndpoint.UPLOAD_ATTACHMENT)
        composer.attach(file("slow.txt"))
        runCurrent()
        assertFalse("Send is disabled while uploads run", composer.canSend("hi"))
        composer.attach(sparse(AttachmentLimits.MAX_UPLOAD_BYTES + 1))
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT, all = true)
        runCurrent()
        assertTrue(composer.chips[0].isFinished)
        assertTrue(composer.chips[1].isFailed)
        assertTrue("a failed chip doesn't block", composer.canSend("hi"))
        composer.send("hi")
        assertEquals("failed chips go with a successful send", emptyList<AttachmentUpload>(), composer.chips)
    }

    @Test
    fun removeAndDiscardDeleteTheServerDrafts() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("one.txt"))
        composer.attach(file("two.txt"))
        runCurrent()
        val ids = composer.chips.mapNotNull { it.attachment?.id }
        assertEquals(2, ids.size)
        assertTrue(composer.remove(composer.chips[0].id))
        composer.discard()
        assertEquals(emptyList<AttachmentUpload>(), composer.chips)
        assertTrue(fake.currentAttachments().none { it.id in ids })
    }

    @Test
    fun discardDuringASendLeavesThePostedDraftsAlone() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("posted.txt"))
        runCurrent()
        val draftId = composer.chips.single().attachment!!.id
        fake.hold(FakeEndpoint.ADD_COMMENT)
        val sending = async { composer.send("") }
        runCurrent()
        composer.discard() // the user leaves the task mid-send
        assertEquals("a chip being posted isn't deleted", 0, calls(fake, FakeEndpoint.DELETE_ATTACHMENT))
        fake.release(FakeEndpoint.ADD_COMMENT, all = true)
        assertTrue(sending.await())
        assertEquals(listOf(draftId), (store.state.value.stories.last() as Comment).attachments.map { it.id })
    }

    @Test
    fun afterAFailedSendDiscardedDraftsGoOnlyIfTheServerDidntTakeThem() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        suspend fun discardedMidSend(name: String, error: ApiError, commitFirst: Boolean): String {
            composer.attach(file(name))
            runCurrent()
            val draftId = composer.chips.single().attachment!!.id
            fake.hold(FakeEndpoint.ADD_COMMENT)
            val sending = async { composer.send("") }
            runCurrent()
            composer.discard()
            if (commitFirst) fake.addCommentDirectly(DemoIds.TASK_WITH_ATTACHMENTS, listOf(draftId))
            fake.failNext(FakeEndpoint.ADD_COMMENT, error)
            fake.release(FakeEndpoint.ADD_COMMENT, all = true)
            assertFalse(sending.await())
            return draftId
        }
        val lost = discardedMidSend("lost.txt", ApiError.transport(), commitFirst = false)
        assertTrue("not taken: deleted", fake.currentAttachments().none { it.id == lost })
        val kept = discardedMidSend("kept.txt", ApiError.transport(), commitFirst = true)
        assertTrue("taken, answer lost: kept", fake.currentAttachments().any { it.id == kept })
        val kept502 =
            discardedMidSend("kept502.txt", ApiError(ApiError.Code("HTTP 502"), 502, "Bad gateway"), commitFirst = true)
        assertTrue("a 5xx isn't a definite rejection", fake.currentAttachments().any { it.id == kept502 })
    }

    @Test
    fun aSendWhoseAnswerWasLostNeverDeletesWhatLanded() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("landed.txt"))
        runCurrent()
        val draftId = composer.chips.single().attachment!!.id
        fake.hold(FakeEndpoint.ADD_COMMENT)
        val sending = async { composer.send("") }
        runCurrent()
        // The BE commits, then the answer is lost (a 502 from the proxy).
        fake.addCommentDirectly(DemoIds.TASK_WITH_ATTACHMENTS, listOf(draftId))
        fake.failNext(FakeEndpoint.ADD_COMMENT, ApiError(ApiError.Code("HTTP 502"), 502, "Bad gateway"))
        fake.release(FakeEndpoint.ADD_COMMENT, all = true)
        assertFalse(sending.await())
        assertTrue("the chip stays (the outcome is unknown)", composer.chips.single().isFinished)
        assertTrue(composer.remove(composer.chips.single().id))
        assertTrue(
            "it was posted: never deleted",
            fake.currentAttachments().any {
                it.id == draftId &&
                    it.commentId != null
            }
        )
        assertTrue(
            "the stories reload and show it",
            store.state.value.stories.filterIsInstance<Comment>().any { c -> c.attachments.any { it.id == draftId } }
        )
    }

    @Test
    fun reSendingAfterALostAnswerDoesntPostTwice() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("once.txt"))
        runCurrent()
        val draftId = composer.chips.single().attachment!!.id
        fake.hold(FakeEndpoint.ADD_COMMENT)
        val sending = async { composer.send("Here") }
        runCurrent()
        fake.addCommentDirectly(DemoIds.TASK_WITH_ATTACHMENTS, listOf(draftId), body = "Here")
        fake.failNext(FakeEndpoint.ADD_COMMENT, ApiError.transport())
        fake.release(FakeEndpoint.ADD_COMMENT, all = true)
        assertFalse(sending.await())
        val calls = calls(fake, FakeEndpoint.ADD_COMMENT)
        assertTrue("it had landed: reported as sent", composer.send("Here"))
        assertEquals("not posted again", calls, calls(fake, FakeEndpoint.ADD_COMMENT))
        assertEquals(emptyList<AttachmentUpload>(), composer.chips)
        assertEquals(1, fake.stories(DemoIds.TASK_WITH_ATTACHMENTS).count { it is Comment && it.body == "Here" })
    }

    @Test
    fun aCancelledSendLeavesItsChipsUncertain() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("cancelled.txt"))
        runCurrent()
        val draftId = composer.chips.single().attachment!!.id
        fake.hold(FakeEndpoint.ADD_COMMENT)
        val sending = launch { composer.send("") }
        runCurrent()
        fake.addCommentDirectly(DemoIds.TASK_WITH_ATTACHMENTS, listOf(draftId)) // the BE commits
        sending.cancel() // and the caller goes away before the answer
        runCurrent()
        fake.release(FakeEndpoint.ADD_COMMENT, all = true)
        runCurrent()
        assertTrue(composer.state.value.sendingIds.isEmpty())
        composer.discard()
        assertTrue(
            "a cancelled send is no rejection: what landed is never deleted",
            fake.currentAttachments().any { it.id == draftId }
        )
    }

    @Test
    fun aSweptDraftDoesntCountAsLandedSoTheTextIsPosted() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("swept.txt"))
        runCurrent()
        val draftId = composer.chips.single().attachment!!.id
        fake.failNext(FakeEndpoint.ADD_COMMENT, ApiError.transport()) // the send really failed
        assertFalse(composer.send("Really sent?"))
        fake.deleteAttachment(draftId) // later the draft is swept (or deleted elsewhere)
        assertTrue(composer.send("Really sent?")) // the user tries again with the same text
        assertEquals("the gone chip left", emptyList<AttachmentUpload>(), composer.chips)
        assertEquals(
            "a 404 isn't 'landed': the text is posted, not dropped",
            "Really sent?",
            (store.state.value.stories.last() as Comment).body
        )
    }

    @Test
    fun aLostAnswerThatDidntLandStillDeletesOnRemoveAndDiscard() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        composer.attach(file("a.txt"))
        composer.attach(file("b.txt"))
        runCurrent()
        val ids = composer.chips.map { it.attachment!!.id }
        fake.failNext(FakeEndpoint.ADD_COMMENT, ApiError.transport())
        assertFalse(composer.send(""))
        assertTrue(composer.remove(composer.chips.first().id))
        composer.discard()
        assertTrue("still drafts after asking: deleted", fake.currentAttachments().none { it.id in ids })
    }

    @Test
    fun twentyChipsAtMostAndFailedOnesDontCount() = runTest {
        val (_, store) = openTask()
        val composer = store.composer
        assertTrue(composer.attach(sparse(AttachmentLimits.MAX_UPLOAD_BYTES + 1, "huge.bin")))
        repeat(20) { assertTrue("chip $it", composer.attach(file("f$it.txt"))) }
        assertFalse(composer.state.value.hasRoomForAnother)
        assertFalse(composer.attach(file("f20.txt")))
        assertEquals(LinkResult.NoRoom, composer.attachLink("https://example.com", null))
        assertEquals(21, composer.chips.size)
        runCurrent()
    }

    @Test
    fun draftLinksFailWithoutLeavingAChip() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        val composer = store.composer
        fake.failNext(
            FakeEndpoint.ADD_LINK,
            ApiError(
                ApiError.Code.VALIDATION,
                400,
                "Bad link",
                listOf(com.mekari.project.core.FieldIssue(listOf("url"), "Bad link"))
            )
        )
        val failed = composer.attachLink("https://example.com/x", null) as LinkResult.Failed
        assertEquals("Bad link", failed.error.issue("url")?.message)
        assertEquals(emptyList<AttachmentUpload>(), composer.chips)
        assertEquals(LinkResult.Added, composer.attachLink("https://example.com/y", "Spec"))
        assertEquals(true, composer.chips.single().attachment?.draft)
        assertEquals("Spec", composer.chips.single().name)
    }

    @Test
    fun ownedSourcesAreDeletedOnceTheUploadLeavesItsList() = runTest {
        val (_, store) = openTask()
        val photo = file("camera.jpg", owned = true)
        store.composer.attach(photo)
        runCurrent()
        assertTrue("kept until the chip goes (retry needs it)", photo.file.exists())
        store.composer.send("")
        assertFalse(photo.file.exists())
        val taskLevel = file("camera2.jpg", owned = true)
        store.attach(taskLevel)
        runCurrent()
        assertFalse(taskLevel.file.exists())
        val notOurs = file("picked.pdf")
        store.attach(notOurs)
        runCurrent()
        assertTrue("delete() is a no-op unless owned", notOurs.file.exists())
    }

    @Test
    fun atMostThreeUploadsRunAtOnce() = runTest {
        val fake = demoFake()
        val (_, store) = openTask(fake)
        fake.hold(FakeEndpoint.UPLOAD_ATTACHMENT)
        repeat(2) { store.attach(file("t$it.txt")) }
        repeat(2) { store.composer.attach(file("c$it.txt")) }
        runCurrent()
        assertEquals(
            "task uploads and composer chips share the 3 slots",
            3,
            fake.parkedCount(FakeEndpoint.UPLOAD_ATTACHMENT)
        )
        assertEquals(1, (store.uploads + store.composer.chips).count { it.state == AttachmentUpload.State.Queued })
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT)
        runCurrent()
        assertEquals("the fourth starts", 3, fake.parkedCount(FakeEndpoint.UPLOAD_ATTACHMENT))
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT, all = true)
        runCurrent()
        assertTrue(store.uploads.isEmpty() && store.composer.chips.all { it.isFinished })
    }

    @Test
    fun lateProgressNeverOverwritesTheOutcome() {
        val link = AttachmentUpload.Source.Link("https://x.y", null)
        val attachment = Attachment(
            id = "a",
            workspaceId = "w",
            taskId = "t",
            kind = AttachmentKind.LINK,
            uploader = alice,
            createdAt = Instant.EPOCH
        )
        val finished =
            AttachmentUpload(taskId = "t", source = link, state = AttachmentUpload.State.Finished(attachment))
        assertFalse(finished.accepts(AttachmentUpload.State.Finishing))
        assertFalse(finished.accepts(AttachmentUpload.State.Uploading(TransferProgress(1, 2))))
        val finishing = finished.copy(state = AttachmentUpload.State.Finishing)
        assertFalse(finishing.accepts(AttachmentUpload.State.Uploading(TransferProgress(1, 2))))
        assertTrue(finishing.accepts(AttachmentUpload.State.Finished(attachment)))
        assertTrue("finishing still counts as active (Send stays disabled)", finishing.isActive)
    }

    // Downloads & wipe rules

    @Test
    fun downloadsRevalidateOnlyWhenAsked() = runTest {
        val fake = demoFake()
        val (session, store) = openTask(fake)
        val files = session.scope.value!!.attachmentFiles
        val pdf = store.attachments.first { it.id == DemoIds.ATTACHMENT_PDF }
        val first = files.file(pdf)
        assertNotNull(first)
        assertEquals(emptyMap<String, TransferProgress>(), files.state.value.downloads)
        assertEquals(first, files.file(pdf, revalidate = false))
        assertEquals("revalidate = false uses the cached copy", 1, calls(fake, FakeEndpoint.DOWNLOAD))
    }

    @Test
    fun imageDataServesThumbnailsAndIsQuietWhenThereIsNone() = runTest {
        val (session, store) = openTask()
        val files = session.scope.value!!.attachmentFiles
        val png = store.attachments.first { it.id == DemoIds.ATTACHMENT_PNG }
        assertTrue(FakeApi.PNG_BYTES.contentEquals(files.imageData(png.thumbnailPath(ThumbnailSize.SMALL)!!)))
        val pdf = store.attachments.first { it.id == DemoIds.ATTACHMENT_PDF }
        assertNull(files.imageData("/api/v1/attachments/${pdf.id}/thumbnail?size=small"))
        assertNull("not an attachment path", files.imageData("/api/v1/auth/me"))
        assertNull("a missing thumbnail isn't an error", files.state.value.lastError)
        session.switchWorkspace(DemoIds.ACME_PILOT)
        try {
            files.imageData(png.thumbnailPath(ThumbnailSize.SMALL)!!)
            org.junit.Assert.fail("a closed scope refuses it")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // As a cancellation, like every request from a closed scope (Coil treats it as a cancelled load).
        }
    }

    @Test
    fun imageDataInFlightWhenTheScopeClosesNeverDelivers() = runTest {
        val fake = demoFake()
        val (session, store) = openTask(fake)
        val files = session.scope.value!!.attachmentFiles
        val png = store.attachments.first { it.id == DemoIds.ATTACHMENT_PNG }
        fake.hold(FakeEndpoint.ATTACHMENT_DATA)
        val loading = async { runCatching { files.imageData(png.thumbnailPath(ThumbnailSize.SMALL)!!) } }
        runCurrent()
        session.signOut()
        fake.release(FakeEndpoint.ATTACHMENT_DATA, all = true)
        val result = loading.await()
        assertTrue(
            "old-session bytes never reach Coil: ${result.getOrNull()}",
            result.exceptionOrNull() is kotlinx.coroutines.CancellationException
        )
    }

    @Test
    fun signOutCancelsUploadsAndEmptiesTheCache() = runTest {
        val fake = demoFake()
        val (session, store) = openTask(fake)
        val directory = session.attachmentCache.directory
        session.scope.value!!.attachmentFiles.file(store.attachments.first { it.id == DemoIds.ATTACHMENT_PDF })
        assertTrue(File(directory, DemoIds.ATTACHMENT_PDF).isDirectory)
        fake.hold(FakeEndpoint.UPLOAD_ATTACHMENT)
        store.attach(file("x.txt"))
        runCurrent()
        session.signOut()
        assertEquals(emptyList<AttachmentUpload>(), session.uploads.state.value)
        assertFalse("the cache is empty after sign-out", directory.exists())
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT, all = true)
    }

    @Test
    fun losingTheWorkspaceWipesTheCacheAndItsUploads() = runTest {
        val fake = demoFake()
        var wiped = 0
        val (session, store) = run {
            val s = AppSession(
                com.mekari.project.core.InMemoryServerSettings(AppSession.DEMO_SERVER),
                com.mekari.project.core.InMemoryTokenStore(),
                { _, _ -> fake },
                InMemoryFeaturePrefs(),
                backgroundScope,
                TestTime.clock,
                testCache()
            ) { wiped += 1 }
            s.start()
            s.scope.value!!.poller.stop()
            val d = s.scope.value!!.taskDetail(DemoIds.TASK_WITH_ATTACHMENTS)
            d.load()
            s to d
        }
        session.scope.value!!.attachmentFiles.file(store.attachments.first { it.id == DemoIds.ATTACHMENT_PDF })
        fake.hold(FakeEndpoint.UPLOAD_ATTACHMENT)
        store.attach(file("x.txt"))
        runCurrent()
        val before = wiped
        fake.removeMembership(DemoIds.ALICE, DemoIds.MEKARI)
        session.scope.value!!.myTasks.refresh() // 404 → lost access → recovery
        runCurrent()
        assertEquals(DemoIds.ACME_PILOT, session.state.value.current?.id)
        assertEquals(emptyList<AttachmentUpload>(), session.uploads.state.value)
        assertFalse(session.attachmentCache.directory.exists())
        assertEquals("the app clears Coil too", before + 1, wiped)
        fake.release(FakeEndpoint.UPLOAD_ATTACHMENT, all = true)
    }

    @Test
    fun uiTestSessionsPassTheCacheAndWipeHookThrough() = runTest {
        var wiped = 0
        val cache = testCache()
        val session = AppSession.uiTest(signedIn = false, appScope = backgroundScope, attachmentCache = cache) {
            wiped +=
                1
        }
        session.start()
        assertTrue(session.attachmentCache === cache)
        assertEquals("starting signed out wipes and tells the app", 1, wiped)
    }

    @Test
    fun startingSignedOutWipesTheCache() = runTest {
        val cache = testCache()
        File(cache.directory, "leftover").mkdirs()
        val session = AppSession(
            com.mekari.project.core.InMemoryServerSettings(),
            com.mekari.project.core.InMemoryTokenStore(),
            { _, _ -> demoFake() },
            InMemoryFeaturePrefs(),
            backgroundScope,
            TestTime.clock,
            cache
        )
        session.start()
        assertEquals(Auth.SignedOut, session.state.value.auth)
        assertFalse(cache.directory.exists())
    }
}
