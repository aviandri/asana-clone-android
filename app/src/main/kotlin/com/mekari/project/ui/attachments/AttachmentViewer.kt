package com.mekari.project.ui.attachments

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mekari.project.TestTags
import com.mekari.project.core.Attachment
import com.mekari.project.core.ThumbnailSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Text previews read at most this much, so a huge log can't stall the screen (as on the web). */
private const val TEXT_PREVIEW_BYTES = 1024 * 1024

/**
 * The in-app preview of a FILE attachment (SPEC-ANDROID §8): images (pinch-zoom, swipe through the task's
 * images), PDF (PdfRenderer, a page pager), video/audio (Media3 from the downloaded file only) and plain text.
 * Anything else shows its type badge with Open with and Save. Files download first, with progress and Cancel
 * (Q4). Delete is offered when allowed (Q1: the viewer and the Attachments grid are where deletes happen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentViewer(
    attachment: Attachment,
    taskAttachments: List<Attachment>,
    actions: AttachmentActions,
    canDelete: Boolean,
    onShow: (Attachment) -> Unit,
    onClose: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val kind = AttachmentFormat.previewKind(attachment)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag(TestTags.ATTACHMENT_VIEWER),
            topBar = {
                TopAppBar(
                    title = { Text(attachment.displayName, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close preview") }
                    },
                    actions = {
                        IconButton(onClick = { actions.share(attachment) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        if (canDelete) {
                            IconButton(onClick = {
                                confirming = true
                            }, modifier = Modifier.testTag(TestTags.VIEWER_DELETE)) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                        Box {
                            IconButton(onClick = {
                                menu = true
                            }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Open with…") }, onClick = {
                                    menu = false
                                    actions.openWith(attachment)
                                })
                                DropdownMenuItem(text = { Text("Save to device") }, onClick = {
                                    menu = false
                                    actions.saveToDevice(attachment)
                                })
                                if (actions.browserUrl != null) {
                                    DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                                        menu = false
                                        actions.openInBrowser()
                                    })
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (kind) {
                    PreviewKind.IMAGE -> ImagePager(attachment, taskAttachments, actions, onShow)
                    PreviewKind.PDF -> WithFile(attachment, actions) { PdfPages(it) }
                    PreviewKind.VIDEO, PreviewKind.AUDIO -> WithFile(attachment, actions) { MediaPlayer(it) }
                    PreviewKind.TEXT -> WithFile(attachment, actions) { TextPreview(it, attachment.size) }
                    null -> NoPreview(attachment, actions)
                }
            }
        }
    }
    if (confirming) {
        ConfirmDelete(attachment.displayName, onConfirm = {
            confirming = false
            actions.delete(attachment)
            onClose()
        }, onDismiss = { confirming = false })
    }
}

/** Downloads (cached copy at once) and shows [content] with the local file; progress + Cancel meanwhile. */
@Composable
private fun WithFile(a: Attachment, actions: AttachmentActions, content: @Composable (File) -> Unit) {
    val downloads by actions.files.state.collectAsStateWithLifecycle()
    var attempt by remember(a.id) { mutableStateOf(0) }
    var file by remember(a.id) { mutableStateOf<Result<File>?>(null) }
    LaunchedEffect(a.id, attempt) {
        file = null
        file =
            actions.files.file(a, revalidate = false)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException())
    }
    val loaded = file
    when {
        loaded == null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val progress = downloads.downloads[a.id]
            if (progress != null) {
                DownloadBar(progress.fraction, a.displayName, Modifier.fillMaxWidth()) { actions.cancelDownload(a) }
            } else {
                CircularProgressIndicator()
            }
        }
        loaded.isSuccess -> content(loaded.getOrThrow())
        else -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Couldn't load this file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { attempt++ }) { Text("Try again") }
        }
    }
}

/** The task's images, swipeable, each pinch-zoomable. The large thumbnail (≤ 1600 px) is shown; images the
 *  server couldn't thumbnail (BMP, A4) load from the downloaded file. */
@Composable
private fun ImagePager(
    current: Attachment,
    all: List<Attachment>,
    actions: AttachmentActions,
    onShow: (Attachment) -> Unit
) {
    val images = all.filter { AttachmentFormat.previewKind(it) == PreviewKind.IMAGE }.ifEmpty { listOf(current) }
    val start = images.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { images.size }
    LaunchedEffect(pager.currentPage) {
        images.getOrNull(pager.currentPage)?.let {
            if (it.id !=
                current.id
            ) {
                onShow(it)
            }
        }
    }
    HorizontalPager(pager, Modifier.fillMaxSize().testTag(TestTags.VIEWER_IMAGES)) { page ->
        val image = images[page]
        ZoomableBox {
            val large = image.thumbnailPath(ThumbnailSize.LARGE)
            if (large != null) {
                AttachmentThumbnail(large, actions.files, Modifier.fillMaxSize(), ContentScale.Fit)
            } else {
                WithFile(image, actions) { file ->
                    val loader = LocalThumbnailLoader.current
                    if (loader != null) {
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            imageLoader = loader,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pinch to zoom (1×–5×) and pan while zoomed; resets per page. `canPan` (experimental) leaves one-finger drags
 * to the pager while the image isn't zoomed.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ZoomableBox(content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    Box(
        Modifier.fillMaxSize()
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** PDF pages through PdfRenderer (one page rendered at a time: the renderer isn't thread-safe). */
@Composable
private fun PdfPages(file: File) {
    val context = LocalContext.current
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) {
        Text("Couldn't open this PDF.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val lock = remember(renderer) { Mutex() }
    val width = context.resources.displayMetrics.widthPixels.coerceAtMost(2000)
    val pager = rememberPagerState { renderer.pageCount }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f).testTag(TestTags.VIEWER_PDF)) { index ->
            var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(index) {
                bitmap = withContext(Dispatchers.IO) {
                    lock.withLock {
                        runCatching {
                            renderer.openPage(index).use { page ->
                                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(AndroidColor.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }.getOrNull()
                    }
                }
            }
            ZoomableBox {
                bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.Fit)
                }
                    ?: CircularProgressIndicator()
            }
        }
        Text(
            "Page ${pager.currentPage + 1} of ${renderer.pageCount}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)
        )
    }
}

/**
 * Video or audio with Media3, from the downloaded file only (never the network URL). The player is released
 * when the screen stops and when the viewer goes away; it's made again on start.
 */
@Composable
private fun MediaPlayer(file: File) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(file, lifecycle) {
        fun create() {
            if (player != null) return
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
        }
        fun release() {
            player?.release()
            player = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> create()
                Lifecycle.Event.ON_STOP -> release()
                else -> Unit
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) create()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            release()
        }
    }
    AndroidView(
        factory = { PlayerView(it).apply { useController = true } },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Color.Black
        ).testTag(TestTags.VIEWER_MEDIA)
    )
}

/** The first 1 MB of a text file, decoded leniently (a cut may split a character), never as markup. */
@Composable
private fun TextPreview(file: File, size: Long?) {
    var text by remember(file) { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { input ->
                    val bytes = input.readNBytesCompat(TEXT_PREVIEW_BYTES + 1)
                    val cut = bytes.size > TEXT_PREVIEW_BYTES || (size ?: 0) > TEXT_PREVIEW_BYTES
                    String(bytes, 0, minOf(bytes.size, TEXT_PREVIEW_BYTES), Charsets.UTF_8) to cut
                }
            }.getOrElse { "" to false }
        }
    }
    val shown = text
    if (shown == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(TestTags.VIEWER_TEXT)) {
        if (shown.first.isEmpty()) {
            Text("This file is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(shown.first, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        if (shown.second) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Showing the first 1 MB. Save the file to see all of it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** No in-app preview: the type badge, and Open with / Save to device. */
@Composable
private fun NoPreview(a: Attachment, actions: AttachmentActions) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val badge = AttachmentFormat.typeBadge(a.mimeType, a.name)
        Text(
            badge.label,
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.background(
                badge.tone,
                MaterialTheme.shapes.small
            ).padding(horizontal = 12.dp, vertical = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("There's no preview for this type of file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(AttachmentFormat.bytes(a.size), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { actions.openWith(a) }, modifier = Modifier.fillMaxWidth()) { Text("Open with…") }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = {
            actions.saveToDevice(a)
        }, modifier = Modifier.fillMaxWidth()) { Text("Save to device") }
    }
}
