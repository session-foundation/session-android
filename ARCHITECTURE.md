# Session Android — Architecture Overview

Session is a private messenger built on a decentralized network of service nodes (the Session Network). Messages and config are stored encrypted on the network rather than a central server; the app interacts with a subset of nodes called a *swarm*.

## Module Structure

```
session-android/
├── app/                  # Main application
├── common/               # Shared Android library (minimal, largely superseded)
├── content-descriptions/ # Accessibility IDs for automated testing
└── build-logic/          # Custom Gradle plugins
```

External dependency of note:
- **libsession-util-android** — native (JNI) library providing the config system and its cryptographic primitives; can be included as a local subproject or pulled from Maven

## Key Architectural Highlights

### 1. Config System — Cross-Device Sync

User state is kept consistent across devices through a set of *config objects* backed by the native `libsession-util` library. Each config type corresponds to a specific domain:

| Config | Content |
|---|---|
| User Profile | Display name, avatar |
| Contacts | Contact list and metadata |
| Convo Info Volatile | Per-conversation metadata (e.g. last-read timestamp) |
| User Groups | Membership in groups and communities |
| Group Info / Members / Keys | Per-group admin configs |

`ConfigFactory` owns all config objects and protects them with a `ReentrantReadWriteLock`. Writes are persisted to an encrypted local `ConfigDatabase`, then flushed to the user's swarm by `ConfigUploader`. `ConfigUploader` observes a `userConfigsChanged()` Flow, debounces changes (1 s), and pushes updates over the onion network. On the receiving end, `ConfigToDatabaseSync` reconciles config state back into the relational databases (threads, drafts, group tables, etc.).

The net effect: any config change — a renamed contact, a read receipt, a new group — propagates to all of a user's linked devices without a central server ever seeing the plaintext.

### 2. Reactive Architecture — Flows All the Way Down

The mental model for building any feature is: **observe some inputs as Flows, transform them, produce state or trigger side effects**. This applies uniformly from background services to ViewModels to the UI layer.

**Databases emit changes upward.** When a write happens, the database posts to a `MutableSharedFlow`. Repositories subscribe to one or more of these streams — along with config update notifications and preference changes — and expose the result as a single reactive data source. `DefaultConversationRepository.observeConversationList()` is a good example: it merges change notifications from `ThreadDatabase`, `MmsSmsDatabase`, `RecipientDatabase`, `CommunityDatabase`, and `ConfigFactory`, debounces the result to avoid redundant work, then re-queries the database on each emission to return a fresh list.

**ViewModels compose repository flows into UI state.** `HomeViewModel` combines the conversation list flow with typing-status and a hidden-message-requests preference into a single `StateFlow<Data?>` that the screen collects. Persistent UI state is always `StateFlow`; one-shot events (navigation commands, toasts) use `SharedFlow` with `replay = 0` so they fire exactly once.

**Compose screens just collect.** State arrives via `collectAsState()` and drives recomposition automatically. One-shot events are consumed in a `LaunchedEffect`. Screens have no explicit refresh logic.

**Background services follow the same pattern via `AuthAwareComponent`.** A background service implements one entry point — `suspend fun doWhileLoggedIn(state)` — and inside it collects whatever flows it needs, running until the coroutine is cancelled. For example, `AdminStateSync` collects `configFactory.userConfigsChanged()` and re-syncs admin promotion state on every emission. `AuthAwareComponentsHandler` launches all services in a `supervisorScope` on login (so a crash in one doesn't affect others) and cancels the scope on logout — the services themselves carry no auth logic.

Authentication scope is handled centrally via `LoginStateRepository.flowWithLoggedInState { }`, a factory that returns an empty flow when logged out, so any flow can be trivially scoped to a session without knowing about auth itself.

### 3. Networking — Onion Routing over the Session Network

All network traffic is routed through an onion-encrypted path of three service nodes, providing sender anonymity:

```
App → Snode A (encrypted) → Snode B (encrypted) → Snode C → Destination
```

The networking stack is built as a chain of `ApiExecutor<Req, Res>` decorators, each layer adding one concern and delegating the rest inward:

**Business-logic APIs** (`StoreMessageApi`, `RetrieveMessageApi`, `DeleteMessageApi`, …) sit at the top. They extend `AbstractSnodeApi`, which handles signing: the request parameters are signed with the account's Ed25519 key before anything hits the network.

**`AutoRetryApiExecutor`** wraps the next layer and retries up to three times with exponential backoff whenever an inner layer signals a retryable failure via `ErrorWithFailureDecision`.

**`BatchApiExecutor`** sits inside the retry wrapper and groups requests that share the same batch key within a 100 ms window, collapsing them into a single `/batch` JSON-RPC call and splitting the response back out. This reduces round-trips when several operations fire at once.

**`SnodeApiExecutorImpl`** translates a typed `SnodeApi` request into a `SessionApiRequest.SnodeJsonRPC` and hands it to the session executor layer below.

**`SwarmApiExecutorImpl`** (used for user-data operations) adds swarm awareness on top of the snode executor: it picks a target snode via `SwarmSnodeSelector` and handles HTTP 421 responses (snode no longer in swarm) by retrying against a different node.

**`OnionSessionApiExecutor`** is the default binding for `SessionApiExecutor`. It takes whatever request arrives, selects a three-hop path from `PathManager`, encrypts the payload with `OnionRequestEncryption`, and sends it to the guard node as a single HTTPS request. The response is decrypted before being returned upward. A `DirectSessionApiExecutor` exists as an alternative for testing without onion routing.

**`OkHttpApiExecutor`** at the bottom converts the abstract `HttpRequest` into an OkHttp call, enforces a semaphore of 20 concurrent connections, and returns a plain `HttpResponse`. Seed nodes (used for bootstrapping) get a separate instance with certificate pinning.

Each layer has its own error manager that decides whether an error should be retried or propagated — `OnionSessionApiErrorManager` handles path/guard failures, `SnodeApiErrorManager` handles clock-skew (HTTP 406) and bad-snode (HTTP 502) responses, and `SwarmApiExecutorImpl` handles 421s. All decisions are communicated upward as `ErrorWithFailureDecision` so `AutoRetryApiExecutor` can act on them uniformly.

`PollerManager` / `GroupPollerManager` use the same executor stack to continuously fetch messages from the user's swarm; they are started and stopped by the `AuthAwareComponent` lifecycle. Outgoing messages are queued through a `JobQueue` for reliable delivery. ONS (Oxen Name Service) resolution maps human-readable names to Session IDs via the same stack.

### 4. Database Setup

All persistent relational data lives in a single SQLCipher-encrypted SQLite database opened by `SQLCipherOpenHelper`. The encryption key is stored in the Android Keystore. Schema migrations live inside `SQLCipherOpenHelper` itself via the standard `onUpgrade` mechanism.

Major tables / database objects:

| Database | Purpose |
|---|---|
| `ThreadDatabase` | Conversation threads |
| `SmsDatabase` / `MmsDatabase` | Messages (all conversations use the MMS path internally) |
| `GroupDatabase` / `GroupMemberDatabase` | Group state |
| `ReactionDatabase` | Message reactions |
| `LokiAPIDatabase` | Network-layer state (last message hashes, swarm info) |
| `ConfigDatabase` | Serialised config object dumps |
| `SearchDatabase` | FTS index for message search |
| `RecipientSettingsDatabase` | Per-contact preferences |

Config data gets its own `ConfigDatabase` so that the native config objects can be round-tripped independently of the message store.

### 5. Jetpack Compose Migration

The codebase is mid-migration from XML layouts to Jetpack Compose. The strategy is incremental: new screens are built in Compose while legacy screens remain in XML until they are rewritten.

- **`FullComposeActivity`** — base class for screens that are entirely Compose
- **`ConversationActivityV3`** — the conversation screen (the most complex in the app) is the leading edge of the migration; it uses Navigation Compose internally (`ConversationV3NavHost`)
- Settings, onboarding, and several utility screens have already been migrated
- The home screen (`HomeActivity`) remains hybrid for now

Material3 is the design system. The Compose BOM tracks a recent stable release.

### 6. Compose Design System — Theming and Common Components

All UI tokens and shared widgets live under `app/…/ui/theme/` and `app/…/ui/components/`.

**Three composition locals** distribute design tokens to any composable without prop drilling:

```kotlin
val LocalColors     = staticCompositionLocalOf<ThemeColors> { ClassicDark() }
val LocalType       = staticCompositionLocalOf { sessionTypography }
val LocalDimensions = staticCompositionLocalOf { Dimensions() }
```

**`ThemeColors`** is an interface with semantic colour slots (`background`, `backgroundSecondary`, `text`, `textSecondary`, `accent`, `danger`, `borders`, bubble colours, etc.). Four concrete implementations (`ClassicDark`, `ClassicLight`, `OceanDark`, `OceanLight`) each accept an accent colour parameter, yielding 24 possible theme combinations. `toMaterialColors()` bridges these into Material 3's `ColorScheme`. The active theme is read from `SharedPreferences` and cached; `invalidateComposeThemeColors()` clears the cache when the user changes settings.

**`SessionTypography`** is a `data class` with 15+ named styles: body scales (`xl` 18sp → `fine` 9sp) and headings (`h1` 36sp → `h9` 14sp). `.bold()` and `.monospace()` are extension functions for one-off variants.

**`Dimensions`** covers spacing (`tinySpacing` 2dp → `xlargeSpacing` 64dp), icon sizes, shape radii, and component-specific constants (`appBarHeight`, `minButtonWidth`, `messageCornerRadius`, etc.).

**`SessionMaterialTheme`** (in `Themes.kt`) installs all three locals and wraps Material 3's `MaterialTheme`. Every Compose screen enters through a thin extension:

```kotlin
fun ComposeView.setThemedContent(content: @Composable () -> Unit) = setContent {
    SessionMaterialTheme { content() }
}
```

**Common components** (`ui/components/`) build on top of the locals:

- **Buttons** — a layered system of `ButtonStyle` (size: XLarge/Large/Slim/Borderless) × `ButtonType` (appearance: Fill/Outline/AccentFill/DangerFill/…), composed into named convenience functions (`FillButton`, `AccentOutlineButton`, `DangerFillButtonRect`, etc.)
- **App bars** — `BasicAppBar`, `BackAppBar`, `ActionAppBar`, all centred via Material 3's `CenterAlignedTopAppBar`
- **`SessionOutlinedTextField`** — theme-aware text input with error-state colours
- **`BaseAvatar`** — single and grid layouts, circular/rectangular clipping, badge overlays, async loading via Coil 3
- **Others** — `SessionSwitch`, `SessionTabRow`, `DropDown`, `BottomSheets`, `TypingIndicator`, `QrImage`, `MiniPlayer`, `BlurredImage`

A `SessionColorsParameterProvider` supplies all four theme variants to `@Preview` functions, so every component can be previewed across themes without running the app.

### 7. Dependency Injection — Hilt

Hilt (Dagger 2) is used throughout. Key modules:

- **`AppModule`** — global `CoroutineScope` (`@ManagerScope`), JSON serializer
- **`DatabaseModule`** — all database singletons, SQLCipher setup
- **`NetworkModule`** — `OkHttpClient`
- **`DeviceModule`** — device identifier

`OnAppStartupComponents` lists services initialised at app start before any UI is shown (migration runner, auth handler, logger, etc.).

## Data Flow Summary

```
User action / incoming message
        │
        ▼
  JobQueue / Poller
        │
        ▼
  Snode APIs (over onion network)
        │
        ├──► ConfigFactory ──► ConfigUploader ──► Swarm (push)
        │         │
        │         └──► ConfigToDatabaseSync ──► SQLCipher DB
        │
        └──► Message handlers ──► SmsDatabase / MmsDatabase
                                        │
                                        ▼
                              ViewModel / StateFlow
                                        │
                                        ▼
                              Compose UI / Legacy XML UI
```
