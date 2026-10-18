package com.mekari.project.core.fake

import com.mekari.project.core.Activity
import com.mekari.project.core.ActivityType
import com.mekari.project.core.Comment
import com.mekari.project.core.InboxNotification
import com.mekari.project.core.Mentions
import com.mekari.project.core.NotificationTaskRef
import com.mekari.project.core.NotificationType
import com.mekari.project.core.Priority
import com.mekari.project.core.Project
import com.mekari.project.core.ProjectMember
import com.mekari.project.core.ProjectPrivacy
import com.mekari.project.core.ProjectRole
import com.mekari.project.core.ProjectView
import com.mekari.project.core.Reorder
import com.mekari.project.core.Section
import com.mekari.project.core.Story
import com.mekari.project.core.TaskItem
import com.mekari.project.core.UserSummary
import com.mekari.project.core.Workspace
import com.mekari.project.core.WorkspaceRole
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Stable ids in [FakeSeed.demo] (the Mac's DemoIDs, same values), for previews and UI tests. */
object DemoIds {
    const val MEKARI = "10000000-0000-4000-8000-000000000001"
    const val ACME_PILOT = "10000000-0000-4000-8000-000000000002"

    const val ADMIN = "20000000-0000-4000-8000-000000000001"
    const val ALICE = "20000000-0000-4000-8000-000000000002"
    const val BOB = "20000000-0000-4000-8000-000000000003"
    const val GUEST = "20000000-0000-4000-8000-000000000004"

    const val ENGINEERING = "30000000-0000-4000-8000-000000000001"
    const val MARKETING = "30000000-0000-4000-8000-000000000002"
    const val PILOT_TEAM = "30000000-0000-4000-8000-000000000003"

    /** "Mobile App v2" (Mekari): 3 sections with tasks in each; alice is EDITOR. */
    const val BOARD_PROJECT = "40000000-0000-4000-8000-000000000001"

    /** "Website Redesign" (Mekari): alice is COMMENTER, so it's read-only for her. */
    const val READ_ONLY_PROJECT = "40000000-0000-4000-8000-000000000002"

    /** "Q4 Campaign" (Mekari, TEAM privacy, Marketing). */
    const val CAMPAIGN_PROJECT = "40000000-0000-4000-8000-000000000003"

    /** "Pilot Rollout" (Acme Pilot); the guest is an EDITOR there. */
    const val PILOT_PROJECT = "40000000-0000-4000-8000-000000000004"

    /** "Fix login redirect" in Mobile App v2: a comment that mentions bob, 2 subtasks, followers. */
    const val TASK_WITH_STORIES = "50000000-0000-4000-8000-000000000001"

    const val GUEST_EMAIL = "guest@partner.test"
    const val PASSWORD = "password123"
}

/** The data a [FakeApi] starts with. */
data class FakeSeed(
    val users: List<UserRecord>,
    /** `myRole` and `memberCount` are filled per viewer by the fake. */
    val workspaces: List<Workspace>,
    val memberships: List<Membership>,
    val teams: List<TeamRecord>,
    val projects: List<ProjectRecord>,
    /** Counts (subtasks, comments) and `project` refs are recomputed by the fake. */
    val tasks: List<TaskItem>,
    val followers: Map<String, List<String>>,
    val stories: Map<String, List<Story>>,
    val notifications: List<NotificationRecord>
) {
    data class UserRecord(val summary: UserSummary, val password: String, val isActive: Boolean, val createdAt: Instant)
    data class Membership(
        val workspaceId: String,
        val userId: String,
        val role: WorkspaceRole,
        val isActive: Boolean = true
    )
    data class TeamRecord(val id: String, val workspaceId: String, val name: String, val memberIds: List<String>)

    /** `project.myRole` is filled per viewer by the fake. */
    data class ProjectRecord(val project: Project, val sections: List<Section>, val members: List<ProjectMember>)
    data class NotificationRecord(val userId: String, val notification: InboxNotification)

    companion object {
        val empty =
            FakeSeed(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyMap(), emptyMap(), emptyList()
            )

        /** The demo data (the BE seed in miniature), with due dates relative to [today]; tests pin it. */
        fun demo(today: LocalDate = LocalDate.now()): FakeSeed = DemoBuilder(today).build()
    }
}

/** Builds [FakeSeed.demo]. Mirrors the Mac's FakeSeed.demo task for task. */
private class DemoBuilder(private val today: LocalDate) {
    /** Fixed timestamps keep previews stable. */
    private val epoch = Instant.ofEpochSecond(1_790_000_000)
    private var counter = 0
    private val tasks = mutableListOf<TaskItem>()
    private val followers = mutableMapOf<String, List<String>>()

    private fun at(minutes: Int): Instant = epoch.plusSeconds(minutes * 60L)

    private fun nextId(prefix: String): String {
        counter += 1
        return "$prefix-0000-4000-8000-" + counter.toString().padStart(12, '0')
    }

    fun build(): FakeSeed {
        val admin = UserSummary(DemoIds.ADMIN, "Ada Admin", "admin@example.com", "#7a6ff0")
        val alice = UserSummary(DemoIds.ALICE, "Alice Johnson", "alice@example.com", "#e8384f")
        val bob = UserSummary(DemoIds.BOB, "Bob Smith", "bob@example.com", "#20aaea")
        val guest = UserSummary(DemoIds.GUEST, "Gita Guest", DemoIds.GUEST_EMAIL, "#aa62e3")
        val users = listOf(admin, alice, bob, guest).map { FakeSeed.UserRecord(it, DemoIds.PASSWORD, true, at(0)) }

        val workspaces = listOf(
            Workspace(DemoIds.MEKARI, "Mekari", true, "example.com", "#4186e0", WorkspaceRole.MEMBER, 0, at(0)),
            Workspace(DemoIds.ACME_PILOT, "Acme Pilot", false, null, "#f06a6a", WorkspaceRole.MEMBER, 0, at(10))
        )
        val memberships = listOf(
            FakeSeed.Membership(DemoIds.MEKARI, DemoIds.ADMIN, WorkspaceRole.ADMIN),
            FakeSeed.Membership(DemoIds.MEKARI, DemoIds.ALICE, WorkspaceRole.MEMBER),
            FakeSeed.Membership(DemoIds.MEKARI, DemoIds.BOB, WorkspaceRole.MEMBER),
            FakeSeed.Membership(DemoIds.ACME_PILOT, DemoIds.ALICE, WorkspaceRole.ADMIN),
            FakeSeed.Membership(DemoIds.ACME_PILOT, DemoIds.BOB, WorkspaceRole.MEMBER),
            FakeSeed.Membership(DemoIds.ACME_PILOT, DemoIds.GUEST, WorkspaceRole.GUEST)
        )
        val teams = listOf(
            FakeSeed.TeamRecord(
                DemoIds.ENGINEERING,
                DemoIds.MEKARI,
                "Engineering",
                listOf(DemoIds.ADMIN, DemoIds.ALICE, DemoIds.BOB)
            ),
            FakeSeed.TeamRecord(DemoIds.MARKETING, DemoIds.MEKARI, "Marketing", listOf(DemoIds.BOB)),
            FakeSeed.TeamRecord(
                DemoIds.PILOT_TEAM,
                DemoIds.ACME_PILOT,
                "Pilot Team",
                listOf(DemoIds.ALICE, DemoIds.BOB)
            )
        )

        fun project(
            id: String,
            ws: String,
            team: String,
            name: String,
            color: String,
            privacy: ProjectPrivacy,
            view: ProjectView,
            owner: UserSummary
        ) = Project(id, ws, team, name, null, color, privacy, false, view, owner, null, at(20), at(20))

        fun sections(projectId: String, names: List<String>) =
            names.mapIndexed { i, name -> Section(nextId("60000000"), projectId, name, (i + 1) * Reorder.STEP) }

        val mobile = sections(DemoIds.BOARD_PROJECT, listOf("To do", "In progress", "Done"))
        val web = sections(DemoIds.READ_ONLY_PROJECT, listOf("Backlog", "Design", "Build"))
        val campaign = sections(DemoIds.CAMPAIGN_PROJECT, listOf("Ideas", "Scheduled"))
        val pilot = sections(DemoIds.PILOT_PROJECT, listOf("To do", "In progress", "Done"))

        val projects = listOf(
            FakeSeed.ProjectRecord(
                project(
                    DemoIds.BOARD_PROJECT,
                    DemoIds.MEKARI,
                    DemoIds.ENGINEERING,
                    "Mobile App v2",
                    "#e0257f",
                    ProjectPrivacy.PUBLIC,
                    ProjectView.BOARD,
                    admin
                ),
                mobile,
                listOf(
                    ProjectMember(admin, ProjectRole.OWNER),
                    ProjectMember(alice, ProjectRole.EDITOR),
                    ProjectMember(bob, ProjectRole.EDITOR)
                )
            ),
            FakeSeed.ProjectRecord(
                project(
                    DemoIds.READ_ONLY_PROJECT,
                    DemoIds.MEKARI,
                    DemoIds.ENGINEERING,
                    "Website Redesign",
                    "#4186e0",
                    ProjectPrivacy.PRIVATE,
                    ProjectView.LIST,
                    bob
                ),
                web,
                listOf(ProjectMember(bob, ProjectRole.OWNER), ProjectMember(alice, ProjectRole.COMMENTER))
            ),
            FakeSeed.ProjectRecord(
                project(
                    DemoIds.CAMPAIGN_PROJECT,
                    DemoIds.MEKARI,
                    DemoIds.MARKETING,
                    "Q4 Campaign",
                    "#f1bd6c",
                    ProjectPrivacy.TEAM,
                    ProjectView.LIST,
                    bob
                ),
                campaign,
                listOf(ProjectMember(bob, ProjectRole.OWNER))
            ),
            FakeSeed.ProjectRecord(
                project(
                    DemoIds.PILOT_PROJECT,
                    DemoIds.ACME_PILOT,
                    DemoIds.PILOT_TEAM,
                    "Pilot Rollout",
                    "#f06a6a",
                    ProjectPrivacy.TEAM,
                    ProjectView.BOARD,
                    alice
                ),
                pilot,
                listOf(ProjectMember(alice, ProjectRole.OWNER), ProjectMember(guest, ProjectRole.EDITOR))
            )
        )

        val mk = DemoIds.MEKARI
        val m = DemoIds.BOARD_PROJECT
        // Mobile App v2: every My Tasks bucket for alice.
        val login = add(
            mk, "Fix login redirect", project = m, section = mobile[1], assignee = alice, creator = admin, due = -2,
            priority = Priority.HIGH, id = DemoIds.TASK_WITH_STORIES,
            description = "After signing in, users land on **/** instead of the page they came from."
        )
        add(
            mk,
            "Reproduce on Safari",
            project = m,
            parent = login,
            assignee = alice,
            creator = alice,
            due = -3,
            done = true
        )
        add(
            mk,
            "Keep the return URL through SSO",
            project = m,
            parent = login,
            assignee = bob,
            creator = alice,
            due = 1
        )
        add(
            mk,
            "Push notification settings",
            project = m,
            section = mobile[0],
            assignee = alice,
            creator = admin,
            due = 0,
            priority = Priority.MEDIUM
        )
        add(
            mk,
            "Offline mode for task list",
            project = m,
            section = mobile[0],
            assignee = alice,
            creator = bob,
            due = 3
        )
        add(
            mk,
            "Dark mode QA pass",
            project = m,
            section = mobile[0],
            assignee = bob,
            creator = admin,
            due = 5,
            priority = Priority.LOW
        )
        add(mk, "App Store screenshots", project = m, section = mobile[1], assignee = alice, creator = admin, due = 12)
        add(
            mk,
            "Crash on empty inbox",
            project = m,
            section = mobile[1],
            assignee = bob,
            creator = alice,
            due = -1,
            priority = Priority.HIGH
        )
        add(
            mk,
            "Upgrade analytics SDK",
            project = m,
            section = mobile[2],
            assignee = alice,
            creator = bob,
            due = -5,
            done = true
        )
        add(
            mk,
            "Release 2.0 beta",
            project = m,
            section = mobile[2],
            assignee = admin,
            creator = admin,
            due = -10,
            done = true
        )
        add(mk, "Tablet layout", project = m, section = mobile[0], creator = admin)

        val w = DemoIds.READ_ONLY_PROJECT
        add(mk, "New pricing page", project = w, section = web[0], assignee = bob, creator = bob, due = 9)
        add(
            mk,
            "Homepage hero copy",
            project = w,
            section = web[1],
            assignee = alice,
            creator = bob,
            due = 2,
            priority = Priority.MEDIUM
        )
        add(mk, "Accessibility audit", project = w, section = web[2], assignee = bob, creator = bob, due = 20)
        add(mk, "Footer links", project = w, section = web[2], creator = bob)

        val c = DemoIds.CAMPAIGN_PROJECT
        add(mk, "Webinar landing page", project = c, section = campaign[0], assignee = bob, creator = bob, due = 6)
        add(mk, "Newsletter draft", project = c, section = campaign[1], assignee = bob, creator = bob, due = 1)

        // Personal tasks.
        add(mk, "Prepare 1:1 notes", assignee = alice, creator = alice, due = 0)
        add(mk, "Expense report", assignee = alice, creator = alice)
        add(mk, "Book team offsite venue", assignee = alice, creator = admin, due = 30, priority = Priority.LOW)
        add(mk, "Renew laptop warranty", assignee = bob, creator = bob, due = 14)

        // Acme Pilot.
        val ap = DemoIds.ACME_PILOT
        val p = DemoIds.PILOT_PROJECT
        add(
            ap, "Kick-off call with Acme", project = p, section = pilot[2], assignee = alice, creator = alice, due = -6,
            priority = Priority.HIGH, done = true
        )
        add(
            ap,
            "Provision pilot accounts",
            project = p,
            section = pilot[1],
            assignee = bob,
            creator = alice,
            due = -1,
            priority = Priority.HIGH
        )
        add(
            ap,
            "Import Acme's existing projects",
            project = p,
            section = pilot[0],
            assignee = bob,
            creator = alice,
            due = 4,
            priority = Priority.MEDIUM
        )
        add(ap, "Train Acme team leads", project = p, section = pilot[0], assignee = alice, creator = alice, due = 8)
        add(ap, "Collect pilot feedback", project = p, section = pilot[0], assignee = guest, creator = alice, due = 15)
        add(ap, "Share SSO metadata", project = p, section = pilot[1], assignee = guest, creator = alice, due = 2)
        add(ap, "Pilot success criteria", project = p, section = pilot[0], creator = alice)
        add(ap, "Weekly pilot status email", assignee = alice, creator = alice, due = 1)

        followers[login] = listOf(DemoIds.ADMIN, DemoIds.ALICE, DemoIds.BOB)

        val stories: Map<String, List<Story>> = mapOf(
            login to listOf(
                Activity(nextId("70000000"), admin, ActivityType.CREATED, createdAt = at(40)),
                Activity(
                    nextId("70000000"),
                    admin,
                    ActivityType.ASSIGNED,
                    data = buildJsonObject { put("assigneeId", JsonPrimitive(alice.id)) },
                    createdAt = at(41)
                ),
                Comment(
                    nextId("80000000"),
                    admin,
                    "Seen on staging too. ${Mentions.token(bob)} can you check the SSO callback?",
                    at(42)
                ),
                Comment(nextId("80000000"), bob, "On it, the `returnTo` param is dropped.", at(50))
            )
        )

        fun notification(
            id: String,
            ws: String,
            type: NotificationType,
            read: Boolean,
            actor: UserSummary,
            taskId: String,
            minute: Int
        ): InboxNotification {
            val task = tasks.first { it.id == taskId }
            return InboxNotification(
                id,
                ws,
                type,
                read,
                at(minute),
                actor,
                NotificationTaskRef(task.id, task.title, task.projectId)
            )
        }
        val pilotTask = tasks.first { it.title == "Provision pilot accounts" }.id
        val notifications = listOf(
            FakeSeed.NotificationRecord(
                DemoIds.ALICE,
                notification(nextId("90000000"), mk, NotificationType.ASSIGNED, true, admin, login, 41)
            ),
            FakeSeed.NotificationRecord(
                DemoIds.ALICE,
                notification(nextId("90000000"), mk, NotificationType.COMMENTED, false, bob, login, 50)
            ),
            FakeSeed.NotificationRecord(
                DemoIds.BOB,
                notification(nextId("90000000"), mk, NotificationType.MENTIONED, false, admin, login, 42)
            ),
            FakeSeed.NotificationRecord(
                DemoIds.ALICE,
                notification(nextId("90000000"), ap, NotificationType.COMMENTED, false, bob, pilotTask, 60)
            )
        )

        return FakeSeed(
            users, workspaces, memberships, teams, projects, tasks.toList(), followers.toMap(), stories, notifications
        )
    }

    private fun add(
        ws: String,
        title: String,
        project: String? = null,
        section: Section? = null,
        parent: String? = null,
        assignee: UserSummary? = null,
        creator: UserSummary,
        due: Int? = null,
        priority: Priority = Priority.NONE,
        done: Boolean = false,
        id: String? = null,
        description: String? = null
    ): String {
        val taskId = id ?: nextId("50000000")
        val position =
            (tasks.count { it.sectionId == section?.id && it.parentId == parent && it.projectId == project } + 1) *
                Reorder.STEP
        tasks += TaskItem(
            id = taskId, workspaceId = ws, projectId = project, sectionId = section?.id, parentId = parent,
            title = title,
            description = description, assignee = assignee, creator = creator,
            dueDate = due?.let {
                today.plusDays(it.toLong())
            },
            priority = priority, completed = done, completedAt = if (done) at(30) else null, position = position,
            createdAt = at(25 + tasks.size), updatedAt = at(25 + tasks.size)
        )
        followers[taskId] = (listOf(creator.id) + listOfNotNull(assignee?.id)).toSortedSet().toList()
        return taskId
    }
}
