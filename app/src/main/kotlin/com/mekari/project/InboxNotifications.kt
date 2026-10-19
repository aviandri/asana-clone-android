package com.mekari.project

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mekari.project.core.ApiError
import com.mekari.project.core.MekariApi
import com.mekari.project.feature.LocalNotice
import com.mekari.project.feature.NotificationDiff
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local notifications for new inbox items (SPEC-ANDROID §3: polling, no push service in v1). */
object InboxNotifications {
    const val CHANNEL = "inbox"
    private const val WORK = "inbox-check"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "Inbox", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Assignments, comments and mentions"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Every 15 minutes (the platform minimum), only with a network, while signed in with notifications on. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<InboxCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun canPost(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    /** The permission, or notifications turned off for the app in system settings: either way nothing shows. */
    fun blocked(context: Context): Boolean =
        !canPost(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** One notification per new item; a tap opens its task (mekariproject://task/<id>) via MainActivity. */
    fun post(context: Context, notices: List<LocalNotice>) {
        if (!canPost(context)) return
        val manager = NotificationManagerCompat.from(context)
        for (notice in notices) {
            val open = Intent(Intent.ACTION_VIEW, notice.taskId?.let { Uri.parse("mekariproject://task/$it") })
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(
                context,
                notice.id.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Mekari Project")
                .setContentText(notice.title)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(notice.id.hashCode(), notification)
            } catch (_: SecurityException) {
                // The permission was revoked between the check and now.
            }
        }
    }
}

/**
 * The background check: in the foreground the live poller and the badge cover it, so it skips. Otherwise
 * it reads the stored session, asks for the current workspace's unread items and posts the new ones.
 */
class InboxCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        if (!container.prefs.value.notificationsEnabled) {
            SessionLog.inbox("inbox check: skipped (notifications off in settings)")
            return Result.success()
        }
        val foreground = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (foreground) {
            SessionLog.inbox("inbox check: skipped (app in foreground)")
            return Result.success()
        }
        val started = System.currentTimeMillis()
        val check = container.checkInbox()
        val blocked = check.notices.isNotEmpty() && InboxNotifications.blocked(applicationContext)
        if (check.notices.isNotEmpty() && !blocked) InboxNotifications.post(applicationContext, check.notices)
        SessionLog.inbox("inbox check: ${check.describe(blocked)} in ${System.currentTimeMillis() - started} ms")
        return Result.success()
    }
}

/**
 * One background check's outcome, for the log (QA A6): no-server, no-token, no-current-workspace,
 * http-<status>, exception-<kind>, baseline-only, or <n>-new with the [notices] to post.
 */
internal class InboxCheck(val outcome: String, val notices: List<LocalNotice> = emptyList()) {
    fun describe(blocked: Boolean): String = when {
        notices.isEmpty() -> outcome
        blocked -> "$outcome/notifications-blocked"
        else -> "$outcome/posted"
    }
}

/** The check itself, apart from Android for tests: [api] is null without a server. */
internal suspend fun runInboxCheck(
    api: MekariApi?,
    hasToken: Boolean,
    seen: suspend (userId: String, workspaceId: String) -> Set<String>?,
    setSeen: suspend (userId: String, workspaceId: String, ids: Set<String>) -> Unit
): InboxCheck {
    api ?: return InboxCheck("no-server")
    if (!hasToken) return InboxCheck("no-token")
    return try {
        val me = api.me()
        val workspace = me.currentWorkspace ?: return InboxCheck("no-current-workspace")
        api.setWorkspace(workspace.id)
        val page = api.notifications(unreadOnly = true)
        val previous = seen(me.user.id, workspace.id)
        val (notices, ids) = diffInbox(previous, page)
        setSeen(me.user.id, workspace.id, ids)
        if (previous == null) InboxCheck("baseline-only") else InboxCheck("${notices.size}-new", notices)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        InboxCheck(e.status?.let { "http-$it" } ?: "exception-${e.code.raw.lowercase()}")
    } catch (e: Exception) {
        InboxCheck("exception-${e.javaClass.simpleName}")
    }
}

/** The worker's inbox diff, kept apart for tests: nothing on the very first check (no baseline yet). */
internal fun diffInbox(
    previousIds: Set<String>?,
    page: com.mekari.project.core.InboxPage
): Pair<List<LocalNotice>, Set<String>> =
    NotificationDiff.newUnread(previousIds, page) to page.data.filter { !it.read }.map { it.id }.toSet()
