# Mekari Project for Android

Native Android (8.0+) client for Mekari Project, the company's internal work-management app.

- Spec: [`docs/SPEC-ANDROID.md`](docs/SPEC-ANDROID.md)
- Backend API contract: `aviandri/asana-clone-be` → `docs/SPEC.md`
- Behaviour reference: the Mac app, `aviandri/asana-clone-mac`

Status: under construction (scaffold).

## Layout
| Module | What | Depends on |
|---|---|---|
| `:core` | models, API client, token store interface. Pure Kotlin/JVM, no Android imports | coroutines, kotlinx.serialization, OkHttp |
| `:feature` | state holders / view models, TaskCache, workspace switching, completion feedback. Pure Kotlin/JVM | `:core`, coroutines |
| `:app` | Compose UI (Material 3), Android ViewModel adapters, DataStore, Keystore, WorkManager, notifications | `:feature` |

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml): Gradle 8.11.1 (wrapper),
AGP 8.7, Kotlin 2.1 + the Compose compiler plugin, JDK 17, minSdk 26, compile/target SDK 35. New
libraries need orchestrator approval.

Conventions:
- UI tests find views through `TestTags` (exposed as resource ids for UiAutomator / device QA).
- `LaunchFlags.FAKE_API` (a boolean intent extra, `com.mekari.project.UI_TEST_FAKE_API`) runs the
  app against the fake API, the Android form of the Mac's `-UITestFakeAPI`:
  ```sh
  adb shell am start -n com.mekari.project/.MainActivity --ez com.mekari.project.UI_TEST_FAKE_API true
  ```
- Contract tests are JVM tests named `*ContractTest` that skip unless `MP_BASE_URL` (server URL,
  without `/api/v1`) points at a seeded backend.

## Building
Needs JDK 17 and the Android SDK (platform 35, build-tools 35.0.0); Android Studio Ladybug or newer
works too.
```sh
./gradlew test                    # JVM unit tests + Robolectric Compose tests
./gradlew lint ktlintCheck        # ktlintFormat fixes style
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest   # needs an emulator or device
MP_BASE_URL=http://127.0.0.1:3000 ./gradlew :core:test --tests '*ContractTest' --rerun
```

On the shared Linux dev box (no emulator, no KVM): `source /home/claude/projects/asana-clone/android-env.sh`,
then run Gradle through `heavy.sh timeout <s> ./gradlew --no-daemon …`. `gradle.properties` caps
Gradle at 2 GB. Robolectric covers UI tests locally; CI runs the emulator.

## CI
| Job | Runs |
|---|---|
| `unit` | `./gradlew test` (`:core`, `:feature`, Robolectric `:app` tests) |
| `lint` | `./gradlew lint ktlintCheck`; reports uploaded |
| `contract` | BE `main` (Postgres service, migrate, full seed) + the `*ContractTest` tests, failing if they were skipped |
| `instrumented` | emulator (API 34 x86_64, google_apis, KVM): `connectedDebugAndroidTest` |
| `debug-apk` | `:app:assembleDebug`; on pushes to `main` uploads `MekariProject-android-debug-<sha>` |
