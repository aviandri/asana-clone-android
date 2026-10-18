# Mekari Project for Android (v1), SPEC

A native Android client for **Mekari Project**, the company's internal Asana clone. The backend
contract is **canonical in `aviandri/asana-clone-be` → `docs/SPEC.md`** (§2 conventions, §5 shapes,
§6 endpoints, §10–§12 incl. workspace clarifications W1–W8). The **feature and behaviour reference
is the Mac app**, `aviandri/asana-clone-mac` → `docs/SPEC-MAC.md` plus `docs/ARCHITECTURE.md`. Android
does what the Mac app does, in Android's own idiom. If this file and the BE spec disagree, the BE spec
wins. Ask the orchestrator before diverging from either.

## 1. Scope (v1 = same as Mac v1)

| In v1 | Not in v1 (Open in browser) |
|---|---|
| Sign in / out, server URL | Accept invite, create account |
| Workspace switcher | Workspace settings, admin, invites |
| My Tasks (Overdue / Today / Upcoming / Later / No date) | Goals / OKRs, Reporting |
| Projects: List and Board | Calendar view, teams, project settings |
| Task detail: title, description (markdown), assignee, due date, priority, complete, subtasks, followers, comments + activity, @mentions | Profile editing |
| Inbox: mark read / all read, unread badge | |
| Search | |
| Quick add (FAB + app shortcut) | |
| Completion feedback + occasional petal celebration (Mac 0.1.2 behaviour) | |
| Notifications for new inbox items (polling) | |
| Deep links `mekariproject://task/<uuid>`, `…/project/<uuid>` | |

## 2. Platform & stack

- **Kotlin 2.1.x, Jetpack Compose (Material 3), JDK 17.** AGP 8.7.x, Gradle 8.11.x (wrapper committed).
- **minSdk 26** (Android 8.0), **compileSdk / targetSdk 35**.
- Single `:app` module plus pure-Kotlin modules:
  - `:core` (models, API client, token store interface): no Android imports, unit-tested on the JVM;
  - `:feature` (state holders / view models, the TaskCache, workspace switching, completion feedback): depends on `:core` and kotlinx-coroutines only, no Android imports;
  - `:app`: Compose UI, Android ViewModel adapters, DataStore, Keystore, WorkManager, notifications.
  Same split as the Mac Kit / Features / App.
- Libraries:
  - OkHttp 4.12 (no Retrofit: `LiveApi` sends every request through one function, as the Mac client does) + kotlinx.serialization 1.7, with kotlinx-coroutines 1.9 for async;
  - AndroidX Navigation Compose, Lifecycle (ViewModel, `collectAsStateWithLifecycle`, and `lifecycle-process` for `ProcessLifecycleOwner`: the inbox poller runs only in the foreground), DataStore (prefs), WorkManager 2.10, Core SplashScreen.
  - No DI framework: a manual `AppContainer`.
  - Test-only: JUnit 4, kotlinx-coroutines-test, Turbine, Robolectric, and OkHttp MockWebServer 4.12 (`:core` LiveApi tests).
  - Anything else needs orchestrator approval.
- App id **`com.mekari.project`**, name **Mekari Project**. Brand: accent `#E0257F`. The adaptive icon is the magenta gradient tile (`#B0136B → #E0257F → #FF5FA2`) from the web favicon. Light/dark from the system; dynamic colour off, so brand colours are used.

## 3. Networking & auth (mirrors SPEC-MAC §4)

- Base URL = server URL + `/api/v1`. JSON camelCase; ISO-8601 timestamps with and without fractions. `dueDate`/`startDate` are a `LocalDate` value (`java.time.LocalDate`, minSdk 26 has it), never a UTC-midnight instant.
- **Session:** OkHttp gets **no CookieJar**. Read `sid` from the login response's `Set-Cookie` (handle multiple and folded headers, and the comma in `Expires`). Store it **encrypted with an Android Keystore AES-GCM key**, in DataStore. Send `Cookie: sid=…` yourself. A 401 clears the token and signs out **only if the request carried the token that is still stored** (the stale-request race found in the Mac review).
- **Workspace:** one current workspace per app. Send `X-Workspace-Id` on everything except `/auth/*`, `/health` and `/workspaces*`. Switches are **serialized, the newest wins, superseded ones are skipped before their POST**. A 404 from a *scoped collection* endpoint while the header is set triggers lost-access recovery: refetch `/auth/me` without the header and adopt the result. Never let the header and the shown workspace disagree.
- Errors decode the BE envelope into `ApiError(code: open enum, message, fieldIssues)`. Cancellation is NOT an error; never surface it.
- **Open enums everywhere** (roles, privacy, views, priority, notification/activity/story kinds): an unknown value must not fail a list decode. Unknown story kinds are skipped.
- Polling: inbox every 60 s while in the foreground. In the background, a WorkManager periodic job (15 min, the platform minimum, network-constrained) checks for new unread items and posts notifications. No push service in v1.

## 4. State rules (port of the Mac Features; re-read the Mac ARCHITECTURE.md)

- **TaskCache:** one shared copy per task. Optimistic edits are replayed on top of the confirmed server state. A failed edit reverts **only itself**. Ignore server copies older than the cached `updatedAt`. Tombstone deleted tasks. Show the cached row immediately in the detail screen ("Loading details…" only for the extra parts).
- **Moves:** `position` = (prev+next)/2, last+1000 when appending, 1000 for an empty section. Renumber the whole section (including hidden completed tasks) when gaps get too small. Retry once, then reload on failure.
- **Completion feedback:** the same rules as `CompletionFeedback`. Celebrate only after the PATCH succeeds, and re-judge at confirm time. Triggers: the last open task in a section, the last top-level task due today (subtasks don't count, overdue isn't today), or the last open task in the project. At most once per 30 s, app-wide. A **300 ms linger** before a completed row leaves a view that hides completed tasks.
- **canEdit:** seed it from the known project role. Read-only checkboxes swallow taps.

## 5. UI (Material 3, Android idiom; don't copy the Mac layout)

- Bottom navigation: **My Tasks · Projects · Inbox (badge) · Search**. The workspace switcher sits in the top app bar (colour tile + name, "Guest" chip for guests).
  - Tablets and foldables (≥ 840 dp): a NavigationRail plus a list-detail layout for the task detail.
- Project: a List/Board segmented toggle (starts on `defaultView`; CALENDAR → List).
  - List: sections with an inline "Add task", long-press drag to reorder or move between sections, "Move to section" in the overflow.
  - Board: horizontally scrolling columns, long-press drag between columns.
- **Inline add fields must never be recreated or have their text pushed while the user types during list updates.** Keep the field state outside the lazy items, give it stable keys, and clear it only after the insert settles. That's the Mac crash lesson: test typing while rows change.
- Task detail: a full screen (phone) or a pane (large screen). Fields edit in place and save on done or focus loss, never per keystroke. Comments composer with an @-mention picker (`GET /users?q=`, Guest badge from W8).
- **Completion:** checkbox fill plus a drawn check (~220 ms), a small spring pop, a strike sweep (~200 ms), a 300 ms linger, then the row collapses (~200 ms).
  - Haptic: `HapticFeedbackConstants.CONFIRM` (API 30+), else `CONTEXT_CLICK`.
  - Optional sound, **off** by default.
  - Petal burst (the web's 14 petals, brand magenta, ~1.2 s) in an overlay that never takes input.
  - Respect "Remove animations" (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`): crossfade only, no burst.
  - TalkBack: announce "Completed X", or "Couldn't complete X" on rollback.
- Quick add: a FAB on My Tasks and a launcher app shortcut. It creates a personal task assigned to me, with an optional due date.
- Settings screen: server (sign out to change), notifications on/off, sound on/off, celebrations on/off.
- Open in browser: Custom Tabs to the web app with `?workspace=<id>` (web paths as in SPEC-MAC §5).
- Accessibility: content descriptions on icon buttons, 48 dp touch targets, and test tags in a `TestTags` object (used by UI tests; also exposed via `testTagsAsResourceId` so UiAutomator and QA can find them).

## 6. Quality gates (CI = the source of truth; the dev box has no emulator)

- **unit:** `./gradlew test`. `:core`/`:feature` JVM tests (JUnit 4/5, kotlinx-coroutines-test, Turbine). `:app` Compose UI tests under **Robolectric** (JVM, no emulator).
- **lint:** `./gradlew lint` (warnings visible, errors fail) + `ktlint` or `detekt` (pick one, keep it light).
- **contract:** like the Mac `linux-contract` job: BE main + postgres + migrate + full seed, then JVM contract tests through the real client, gated on `MP_BASE_URL`.
- **instrumented:** `reactivecircus/android-emulator-runner` on ubuntu-latest (KVM), API 34 x86_64. `connectedDebugAndroidTest` with a fake-API launch flag. Must include a **typing-while-rows-change** test and a rapid-add test.
- **artifacts:**
  - every push to main uploads `MekariProject-android-debug-<sha>` (the debug APK);
  - `v*` tags build a **release APK signed with the release keystore** from GitHub secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; the user creates the keystore) and create a GitHub Release with the APK.
  - Version: `versionName` from the tag, `versionCode` = the run number.
- A device check on the user's phone and the Mac emulator, with the **exact CI artifact**, before any release is announced.

## 7. Workflow & roles

- Branches → PRs to `main`; the orchestrator reviews and merges. Stacked PRs use merge commits. Push via `git@github-personal:aviandri/asana-clone-android.git`.
- Heavy commands on the dev box go through `/home/claude/projects/asana-clone/heavy.sh timeout <s> <cmd>`; Gradle uses `--no-daemon` and `org.gradle.jvmargs=-Xmx2g` locally.
- PRs:
  1. **Scaffold & CI** (platform): Gradle wrapper, modules, version catalog, an app that launches, an adaptive icon, all CI jobs (instrumented may start with one test), the debug APK artifact, the JDK 17 + Android SDK toolchain on the Linux box.
  2. **core** (data engineer): models, API client, auth/token store, errors, LocalDate, a fake API with demo data, unit and contract tests.
  3. **feature** (data engineer): session, workspaces, My Tasks, project (moves), task detail, inbox + poller, search, quick add, completion feedback, TaskCache.
  4. **App shell** (UI engineer): theme, navigation, sign-in, workspace switcher, My Tasks, task detail, settings.
  5. **App complete** (UI engineer): projects (List/Board + drag), inbox, search, quick add + shortcut, completion animation + petals, notifications worker, deep links, open in browser.
  6. **Release** (platform): signed release APK on tags.
- Device QA is the Mac session (`msnnb-…`, over Remote Control): the arm64 emulator (`system-images;android-35;google_apis;arm64-v8a`) plus the user's phone over USB, using CI artifacts.
