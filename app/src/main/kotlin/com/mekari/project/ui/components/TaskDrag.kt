package com.mekari.project.ui.components

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import com.mekari.project.core.TaskItem

/** The clip label task drags carry (a first filter; any app could copy it, see [ActiveTaskDrag]). */
const val TASK_DRAG_LABEL = "com.mekari.project.task"

/**
 * The task this process is dragging right now. Another app's global drag can carry our label, so a drop
 * counts only if its id is the one we started: foreign drops are ignored.
 */
object ActiveTaskDrag {
    @Volatile var taskId: String? = null
}

/**
 * Which drops are moves (the Mac's DropRules): only this project's tasks, only for editors, and never
 * "before itself". An unknown id (not in the cache) is refused.
 */
object DropRules {
    fun accepts(task: TaskItem?, projectId: String, canEdit: Boolean, before: String?): Boolean =
        canEdit && task != null && task.projectId == projectId && task.id != before
}

/** Long-press to drag this task (List rows and Board cards). Off when read-only. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.taskDragSource(taskId: String, enabled: Boolean): Modifier {
    if (!enabled) return this
    return dragAndDropSource {
        detectTapGestures(onLongPress = {
            ActiveTaskDrag.taskId = taskId
            startTransfer(
                DragAndDropTransferData(
                    ClipData(TASK_DRAG_LABEL, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), ClipData.Item(taskId))
                )
            )
        })
    }
}

/** The dragged task id, if this is OUR drag: our label, and the id this process started dragging. */
fun DragAndDropEvent.taskId(): String? {
    val event = toAndroidDragEvent()
    if (event.clipDescription?.label != TASK_DRAG_LABEL) return null
    val id = event.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return null
    return id.takeIf { it == ActiveTaskDrag.taskId }
}

/**
 * A drop zone for task drags. [onTask] decides (DropRules) and moves; [onHover] drives the highlight.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.taskDropTarget(onHover: (Boolean) -> Unit, onTask: (String) -> Boolean): Modifier {
    val target = remember(onTask, onHover) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                onHover(false)
                return event.taskId()?.let(onTask) ?: false
            }

            override fun onEntered(event: DragAndDropEvent) = onHover(true)

            override fun onExited(event: DragAndDropEvent) = onHover(false)

            override fun onEnded(event: DragAndDropEvent) {
                onHover(false)
                ActiveTaskDrag.taskId = null
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                event.toAndroidDragEvent().clipDescription?.label == TASK_DRAG_LABEL
        },
        target = target
    )
}
