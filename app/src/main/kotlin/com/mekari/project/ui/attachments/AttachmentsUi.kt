package com.mekari.project.ui.attachments

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mekari.project.TestTags
import com.mekari.project.core.Attachment
import com.mekari.project.core.AttachmentKind
import com.mekari.project.core.LinkPreviewStatus
import com.mekari.project.core.TaskItem
import com.mekari.project.core.ThumbnailSize
import com.mekari.project.core.UploadSource
import com.mekari.project.core.WebLinks
import com.mekari.project.feature.AttachmentFiles
import com.mekari.project.feature.AttachmentUpload
import com.mekari.project.feature.CommentComposer
import com.mekari.project.feature.LinkResult
import com.mekari.project.feature.TaskDetailStore
import com.mekari.project.ui.Browser
import com.mekari.project.ui.LocalAppScope
import com.mekari.project.ui.screens.LocalSnackbarHost
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the attachment UI does (SPEC-ANDROID §8 Actions). Files are downloaded through the workspace's
 * [AttachmentFiles] (cached, keyed by ETag) before they're opened with another app, shared or saved; a failed
 * download shows as a snackbar. Deleting and refreshing go through the store; chips go through the composer.
 */
class AttachmentActions(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val notify: (String) -> Unit,
    val store: TaskDetailStore,
    val files: AttachmentFiles,
    private val server: String?,
    private val task: () -> TaskItem?,
    private val workspaceId: String,
    private val saveAs: (Attachment) -> Unit,
    val preview: (Attachment) -> Unit
) {
    /** The task on the web, in this workspace (the file itself needs the app's session). */
    val browserUrl: String? get() = server?.let { s -> task()?.let { WebLinks.task(it, s, workspaceId) } }

    fun openInBrowser() {
        browserUrl?.let { Browser.open(context, it) }
    }

    fun openWith(a: Attachment) = withFile(a) { file ->
        if (!AttachmentIntents.openWith(context, file, a.mimeType, a.displayName)) notify("No app can open this file")
    }

    fun share(a: Attachment) = withFile(a) { file ->
        if (!AttachmentIntents.share(context, file, a.mimeType, a.displayName)) notify("No app can share this file")
    }

    fun saveToDevice(a: Attachment) = saveAs(a)

    /**
     * After the SAF "Save to device" picker: copy the downloaded file into the chosen document. If that fails
     * (or the download does), the empty document SAF already created is removed.
     */
    fun writeTo(a: Attachment, target: Uri) {
        appScope.launch {
            val file = files.file(a)
            val ok = file != null &&
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(target)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                            ?: error("no stream")
                    }.isSuccess
                }
            if (!ok) {
                withContext(Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
                }
            }
            notify(if (ok) "Saved ${a.displayName}" else "Couldn't save ${a.displayName}")
        }
    }

    /** A LINK: ACTION_VIEW (App Links route Figma, YouTube, Drive… to their apps). Only http/https. */
    fun openLink(a: Attachment) {
        val url = a.linkUrl ?: return
        if (!AttachmentIntents.openLink(context, url)) Browser.open(context, url)
    }

    fun openLinkInBrowser(a: Attachment) {
        a.linkUrl?.let { Browser.open(context, it) }
    }

    fun copyLink(a: Attachment) {
        val url = a.linkUrl ?: return
        AttachmentIntents.copyLink(context, url)
        notify("Link copied")
    }

    fun refreshPreview(a: Attachment) {
        appScope.launch { store.refreshLinkPreview(a.id) }
    }

    fun delete(a: Attachment) {
        appScope.launch { store.deleteAttachment(a.id) }
    }

    fun cancelDownload(a: Attachment) {
        appScope.launch { files.cancelDownload(a.id) }
    }

    private fun withFile(a: Attachment, use: suspend (File) -> Unit) {
        appScope.launch {
            val file = files.file(a) ?: return@launch // failed (snackbar from lastError) or cancelled
            use(file)
        }
    }
}

/** The screen's attachment actions, with the SAF "Save to device" launcher registered here. */
@Composable
fun rememberAttachmentActions(
    store: TaskDetailStore,
    files: AttachmentFiles,
    server: String?,
    task: TaskItem?,
    workspaceId: String,
    preview: (Attachment) -> Unit
): AttachmentActions {
    val context = LocalContext.current
    val appScope = LocalAppScope.current
    val snackbar = LocalSnackbarHost.current
    val latestTask = rememberUpdatedTask(task)
    // The attachment being saved survives a rotation inside the SAF picker (by id; looked up again on return).
    var savingId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = remember { arrayOfNulls<androidx.activity.compose.ManagedActivityResultLauncher<String, Uri?>>(1) }
    val actions = remember(store, files, server, workspaceId) {
        AttachmentActions(
            context = context,
            appScope = appScope,
            notify = { message -> appScope.launch { snackbar?.showSnackbar(message) } },
            store = store,
            files = files,
            server = server,
            task = { latestTask.value },
            workspaceId = workspaceId,
            saveAs = { a ->
                savingId = a.id
                launcher[0]?.launch(a.name ?: "file")
            },
            preview = preview
        )
    }
    launcher[0] = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val id = savingId
        savingId = null
        if (uri == null) return@rememberLauncherForActivityResult
        val a = id?.let { saved -> store.state.value.attachments.firstOrNull { it.id == saved } }
        if (a != null) {
            actions.writeTo(a, uri)
        } else {
            // Gone meanwhile: don't leave the empty document SAF already made.
            appScope.launch(Dispatchers.IO) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
        }
    }
    return actions
}

@Composable
private fun rememberUpdatedTask(task: TaskItem?): androidx.compose.runtime.State<TaskItem?> {
    val holder = remember { mutableStateOf(task) }
    holder.value = task
    return holder
}

/**
 * The task's attachments (BE §13 Clients): task-level and comment ones, newest first, with Add for editors,
 * uploads in progress and a grid of tiles. Not lazy: it sits in one item of the detail list.
 */
@Composable
fun AttachmentsSection(
    attachments: List<Attachment>,
    uploads: List<AttachmentUpload>,
    canAttach: Boolean,
    actions: AttachmentActions,
    onAdd: () -> Unit
) {
    if (!canAttach && attachments.isEmpty() && uploads.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(TestTags.ATTACHMENTS)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Attachments", style = MaterialTheme.typography.titleSmall)
            if (attachments.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${attachments.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            if (canAttach) {
                TextButton(onClick = onAdd, modifier = Modifier.testTag(TestTags.ATTACHMENTS_ADD)) {
                    Icon(AttachmentIcons.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
        }
        uploads.forEach { upload ->
            UploadRow(
                upload,
                onCancel = { actions.store.cancelUpload(upload.id) },
                onRetry = { actions.store.retryUpload(upload.id) }
            )
            Spacer(Modifier.height(6.dp))
        }
        if (attachments.isNotEmpty()) {
            AttachmentGrid(attachments, actions, canDelete = { actions.store.canDelete(it) }, minTile = 140)
        } else if (uploads.isEmpty()) {
            Text(
                "Add photos, files or links with Add.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            )
        }
    }
}

/** A posted comment's attachments, under its body (oldest first). No Delete here (Q1). */
@Composable
fun CommentAttachments(attachments: List<Attachment>, actions: AttachmentActions) {
    if (attachments.isEmpty()) return
    Box(Modifier.padding(top = 6.dp).widthIn(max = 420.dp).testTag(TestTags.COMMENT_ATTACHMENTS)) {
        AttachmentGrid(attachments, actions, canDelete = { false }, minTile = 120, compact = true)
    }
}

/** Equal-width tiles, as many columns as fit at [minTile] dp (at least 2). */
@Composable
private fun AttachmentGrid(
    attachments: List<Attachment>,
    actions: AttachmentActions,
    canDelete: (Attachment) -> Boolean,
    minTile: Int,
    compact: Boolean = false
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(2, (maxWidth.value / minTile).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a ->
                        Box(Modifier.weight(1f)) { AttachmentTile(a, actions, canDelete(a), compact) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun AttachmentTile(a: Attachment, actions: AttachmentActions, canDelete: Boolean, compact: Boolean = false) {
    when (a.kind) {
        AttachmentKind.FILE -> FileTile(a, actions, canDelete, compact)
        AttachmentKind.LINK -> LinkCard(a, actions, canDelete, compact)
        else -> Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .testTag(TestTags.attachmentTile(a.id)),
            contentAlignment = Alignment.Center
        ) {
            Text("Unsupported attachment", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A file: thumbnail or type badge, name and size. Tap previews; long-press or ⋮ for actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(a: Attachment, actions: AttachmentActions, canDelete: Boolean, compact: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val downloads by actions.files.state.collectAsStateWithLifecycle()
    val progress = downloads.downloads[a.id]
    val label = AttachmentFormat.label(a)
    Box {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .combinedClickable(onClickLabel = "Preview", onLongClickLabel = "More actions", onLongClick = {
                    menu =
                        true
                }) {
                    actions.preview(a)
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    role = Role.Button
                }
                .testTag(TestTags.attachmentTile(a.id))
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (compact) 1.8f else 1.5f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                TypeBadgeView(AttachmentFormat.typeBadge(a.mimeType, a.name))
                a.thumbnailPath(ThumbnailSize.SMALL)?.let { path ->
                    AttachmentThumbnail(path, actions.files, Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    a.name ?: "file",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    AttachmentFormat.bytes(a.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (progress != null) {
            DownloadBar(progress.fraction, a.displayName, Modifier.align(Alignment.TopCenter).padding(4.dp)) {
                actions.cancelDownload(a)
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(TestTags.attachmentMenu(a.id))) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More actions for ${a.displayName}",
                    modifier = Modifier.background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        CircleShape
                    ).padding(2.dp)
                )
            }
            AttachmentMenu(a, actions, canDelete, expanded = menu, onDismiss = { menu = false })
        }
    }
}

/** Download progress over a tile or the viewer, with Cancel (Q4). */
@Composable
fun DownloadBar(fraction: Float?, name: String, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 3.dp,
        modifier = modifier.testTag(TestTags.ATTACHMENT_DOWNLOAD)
    ) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
            } else {
                LinearProgressIndicator(Modifier.weight(1f))
            }
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel download of $name") }
        }
    }
}

@Composable
private fun TypeBadgeView(badge: TypeBadge) {
    Text(
        badge.label,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.background(badge.tone, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/** A server-path image through Coil (no loader provided, e.g. some tests: nothing, so the badge shows). */
@Composable
fun AttachmentThumbnail(
    path: String,
    files: AttachmentFiles,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val loader = LocalThumbnailLoader.current ?: return
    AsyncImage(
        model = AttachmentImage(path, files),
        contentDescription = null,
        imageLoader = loader,
        contentScale = contentScale,
        modifier = modifier
    )
}

/**
 * A link's card: og image (not when compact), provider badge or site icon, title, site, two lines of
 * description. A failed or pending preview falls back to the domain. Tap opens it (ACTION_VIEW).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkCard(a: Attachment, actions: AttachmentActions, canDelete: Boolean, compact: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val badge = ProviderBadge.of(a.provider)
    val title = a.displayName
    val site = a.siteName ?: badge?.name ?: a.host
    Box {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .combinedClickable(onClickLabel = "Open link", onLongClickLabel = "More actions", onLongClick = {
                    menu =
                        true
                }) {
                    actions.openLink(a)
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = AttachmentFormat.label(a)
                    role = Role.Button
                }
                .testTag(TestTags.attachmentTile(a.id))
        ) {
            if (!compact && a.hasImage && a.imagePath != null) {
                AttachmentThumbnail(a.imagePath!!, actions.files, Modifier.fillMaxWidth().aspectRatio(1.9f))
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        badge != null -> Text(
                            badge.initials,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.size(
                                24.dp
                            ).background(badge.tone, RoundedCornerShape(5.dp)).padding(top = 5.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        a.hasIcon && a.iconPath != null -> {
                            Icon(
                                AttachmentIcons.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            AttachmentThumbnail(
                                a.iconPath!!,
                                actions.files,
                                Modifier.size(24.dp).clip(RoundedCornerShape(5.dp))
                            )
                        }
                        else -> Icon(
                            AttachmentIcons.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(end = 28.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (site != null && site != title) {
                        Text(
                            site,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (a.previewStatus == LinkPreviewStatus.PENDING) {
                        Text(
                            "Fetching preview…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val description = a.description
                    if (!compact && !description.isNullOrEmpty()) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(TestTags.attachmentMenu(a.id))) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${a.displayName}")
            }
            AttachmentMenu(a, actions, canDelete, expanded = menu, onDismiss = { menu = false })
        }
    }
}

/** A tile's actions. Delete asks first. */
@Composable
fun AttachmentMenu(
    a: Attachment,
    actions: AttachmentActions,
    canDelete: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    val storeState by actions.store.state.collectAsStateWithLifecycle()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (a.kind == AttachmentKind.FILE) {
            MenuItem("Preview", onDismiss) { actions.preview(a) }
            MenuItem("Open with…", onDismiss, TestTags.ATTACHMENT_OPEN_WITH) { actions.openWith(a) }
            MenuItem("Share", onDismiss) { actions.share(a) }
            MenuItem("Save to device", onDismiss) { actions.saveToDevice(a) }
            if (actions.browserUrl != null) MenuItem("Open in browser", onDismiss) { actions.openInBrowser() }
        } else if (a.kind == AttachmentKind.LINK && a.linkUrl != null) {
            MenuItem("Open in browser", onDismiss) { actions.openLinkInBrowser(a) }
            MenuItem("Copy link", onDismiss) { actions.copyLink(a) }
            if (storeState.canAttach) {
                MenuItem("Refresh preview", onDismiss) {
                    actions.refreshPreview(a)
                }
            }
        }
        if (canDelete) MenuItem("Delete", onDismiss, TestTags.ATTACHMENT_DELETE) { confirming = true }
    }
    if (confirming) {
        ConfirmDelete(a.displayName, onConfirm = {
            confirming = false
            actions.delete(a)
        }, onDismiss = { confirming = false })
    }
}

/** A menu item that closes the menu, then acts. */
@Composable
private fun MenuItem(text: String, onDismiss: () -> Unit, tag: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = {
        onDismiss()
        onClick()
    }, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
fun ConfirmDelete(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “$name”?") },
        text = { Text("This can't be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(TestTags.ATTACHMENT_DELETE_CONFIRM)) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A task-level upload: waiting, progress + Cancel, "Finishing…", or the error with Retry and Dismiss. */
@Composable
private fun UploadRow(upload: AttachmentUpload, onCancel: () -> Unit, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(start = 10.dp)
            .testTag(TestTags.ATTACHMENT_UPLOAD),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    upload.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    AttachmentFormat.bytes(upload.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            when (val state = upload.state) {
                AttachmentUpload.State.Queued -> Text(
                    "Waiting to upload…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is AttachmentUpload.State.Uploading -> state.progress.fraction?.let { f ->
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                AttachmentUpload.State.Finishing -> LinearProgressIndicator(progress = {
                    0.99f
                }, modifier = Modifier.fillMaxWidth())
                is AttachmentUpload.State.Failed -> Text(
                    AttachmentFormat.errorMessage(state.error, upload.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                is AttachmentUpload.State.Finished -> Unit
            }
        }
        when (val state = upload.state) {
            AttachmentUpload.State.Queued, is AttachmentUpload.State.Uploading ->
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Cancel uploading ${upload.name}"
                    }
                ) {
                    Text("Cancel")
                }
            AttachmentUpload.State.Finishing -> Text(
                "Finishing…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            is AttachmentUpload.State.Failed -> {
                if (AttachmentFormat.isRetryable(state.error)) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.semantics { contentDescription = "Retry uploading ${upload.name}" }
                    ) { Text("Retry") }
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = "Dismiss ${upload.name}" }
                ) { Text("Dismiss") }
            }
            is AttachmentUpload.State.Finished -> Unit
        }
    }
}

/**
 * The composer's chips (BE §13): progress, "Finishing…", Retry for a failed file, × to cancel or remove
 * (through the composer: it deletes drafts, and refuses chips that are finishing or being posted).
 */
@Composable
fun ComposerChips(chips: List<AttachmentUpload>, sending: Set<String>, composer: CommentComposer) {
    if (chips.isEmpty()) return
    val appScope = LocalAppScope.current
    LazyRow(
        Modifier.fillMaxWidth().testTag(TestTags.COMPOSER_CHIPS),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(chips, key = { it.id }) { chip ->
            Chip(chip, removable = chip.id !in sending && !chip.isFinishing, onRemove = {
                appScope.launch { composer.remove(chip.id) }
            }, onRetry = { composer.retry(chip.id) })
        }
    }
}

@Composable
private fun Chip(chip: AttachmentUpload, removable: Boolean, onRemove: () -> Unit, onRetry: () -> Unit) {
    val isLink = chip.source is AttachmentUpload.Source.Link
    val title = chip.attachment?.displayName ?: chip.name
    val failed = chip.state as? AttachmentUpload.State.Failed
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.testTag(TestTags.composerChip(chip.id))
    ) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLink) {
                Icon(AttachmentIcons.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
            )
            Spacer(Modifier.width(6.dp))
            when (val state = chip.state) {
                AttachmentUpload.State.Queued -> Text("Waiting…", style = MaterialTheme.typography.labelSmall)
                is AttachmentUpload.State.Uploading -> state.progress.fraction?.let { f ->
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.width(40.dp))
                } ?: CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                AttachmentUpload.State.Finishing -> Text("Finishing…", style = MaterialTheme.typography.labelSmall)
                is AttachmentUpload.State.Finished -> if (state.attachment.kind == AttachmentKind.FILE) {
                    Text(
                        AttachmentFormat.bytes(state.attachment.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is AttachmentUpload.State.Failed -> {
                    Text(
                        AttachmentFormat.errorMessage(state.error, chip.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                    if (!isLink && AttachmentFormat.isRetryable(state.error)) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.semantics { contentDescription = "Retry uploading ${chip.name}" }
                        ) { Text("Retry") }
                    }
                }
            }
            val removeLabel = when {
                chip.isActive -> "Cancel uploading $title"
                failed != null -> "Dismiss $title"
                else -> "Remove $title"
            }
            IconButton(onClick = onRemove, enabled = removable) {
                Icon(Icons.Filled.Close, contentDescription = removeLabel, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Where picked files go: the task (task-level) or the comment composer (drafts). */
enum class AttachTarget { TASK, COMMENT }

/**
 * The Add sheet (SPEC-ANDROID §8): Photo or video (system Photo Picker), File (OpenMultipleDocuments),
 * Camera (TakePicture into a FileProvider temp file, no CAMERA permission) and Link. [onFiles] gets the
 * sources; [onLink] adds a link and returns its result for the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAttachmentSheet(
    onDismiss: () -> Unit,
    onFiles: (List<UploadSource>) -> Unit,
    onLink: suspend (url: String, title: String?) -> LinkResult
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val appScope = LocalAppScope.current
    // Name and size come from a ContentResolver query: off the main thread, then handed over on it.
    val picked: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            appScope.launch {
                onFiles(withContext(Dispatchers.IO) { uris.map { ContentUriSource.from(resolver, it) } })
            }
        }
    }
    var linking by rememberSaveable { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        picked(uris)
        onDismiss()
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picked(uris)
        onDismiss()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        if (file != null) {
            if (taken &&
                file.length() > 0
            ) {
                onFiles(listOf(AttachmentShare.cameraSource(file)))
            } else {
                AttachmentShare.discardCameraFile(file)
            }
        }
        onDismiss()
    }
    if (linking) {
        LinkDialog(onDismiss = {
            linking = false
            onDismiss()
        }, onAdd = onLink)
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(TestTags.ATTACH_SHEET)
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SheetOption("Photo or video", TestTags.ATTACH_PHOTO) {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            SheetOption("File", TestTags.ATTACH_FILE) { documents.launch(arrayOf("*/*")) }
            SheetOption("Camera", TestTags.ATTACH_CAMERA) {
                val file = AttachmentShare.newCameraFile(context)
                cameraPath = file.absolutePath
                runCatching { camera.launch(AttachmentShare.contentUri(context, file)) }.onFailure {
                    AttachmentShare.discardCameraFile(file)
                    cameraPath = null
                }
            }
            SheetOption("Link", TestTags.ATTACH_LINK) { linking = true }
        }
    }
}

@Composable
private fun SheetOption(text: String, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        modifier = Modifier.fillMaxWidth().heightIn(
            min = 56.dp
        ).clickable(role = Role.Button, onClick = onClick).testTag(tag)
    )
}

/** URL + optional title. The `url` problem (local check or the server's) shows under the field. */
@Composable
fun LinkDialog(onDismiss: () -> Unit, onAdd: suspend (url: String, title: String?) -> LinkResult) {
    val appScope = LocalAppScope.current
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add link") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://") },
                    label = { Text("Link") },
                    isError = problem != null,
                    supportingText = problem?.let { p ->
                        { Text(p, modifier = Modifier.testTag(TestTags.LINK_PROBLEM)) }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.LINK_URL)
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 300) title = it },
                    singleLine = true,
                    label = { Text("Title (optional)") },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.LINK_TITLE)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !pending,
                modifier = Modifier.testTag(TestTags.LINK_SUBMIT),
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isEmpty()) {
                        problem = "Enter a link."
                        return@TextButton
                    }
                    pending = true
                    problem = null
                    appScope.launch {
                        when (val result = onAdd(trimmed, title.trim().ifEmpty { null })) {
                            LinkResult.Added -> onDismiss()
                            LinkResult.NoRoom -> problem = AttachmentFormat.commentFullMessage
                            is LinkResult.Failed -> problem = AttachmentFormat.linkProblem(result.error)
                        }
                        pending = false
                    }
                }
            ) { Text(if (pending) "Adding…" else "Add link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The Activity behind a Compose context (for isChangingConfigurations). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Lives as long as a task's back-stack entry (an entry-scoped ViewModel): through rotation and while another
 * screen is on top. When the entry is popped, the task was left, so the composer's drafts are discarded
 * (through the composer, which deletes them server-side).
 */
class ComposerLease(private val composer: CommentComposer, private val scope: CoroutineScope) :
    androidx.lifecycle.ViewModel() {
    override fun onCleared() {
        scope.launch { composer.discard() }
    }
}
