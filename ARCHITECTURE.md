# Session Android - Architecture Overview

Session is a private messenger built on the Session Network. Most messaging and config data is stored encrypted on decentralized service nodes rather than a central application server. The Android client talks to a subset of nodes called a swarm, usually through onion-routed requests.

Not every feature is fully decentralized, though. Messaging, configs, and swarm state live on the Session Network; some auxiliary features such as Pro, app-version metadata, and push registration use dedicated server-backed APIs rather than swarm storage. Under the default executor, those server requests are still onion-routed even though the authority behind them is centralized.

## Glossary

- `Snode`: a Session Network service node.
- `Swarm`: the subset of snodes responsible for a given account or group.
- `Config`: an encrypted, native `libsession-util` object used for cross-device state sync.
- `Closed group`: an encrypted group backed by swarm storage and group config objects.
- `Community` / `open group`: a server-hosted public chat, polled separately from swarm DMs.
- `Auth-aware component`: a long-running coroutine-based service that only exists while a user is logged in.

## 1. Repository and Module Layout

```text
session-android/
|- app/                  Main Android application
|- common/               Shared Android library; minimal and largely superseded
|- content-descriptions/ Accessibility identifiers for UI automation
`- build-logic/          Custom Gradle plugins and build conventions
```

Notable external dependency:

- `libsession-util-android`: native/JNI library that provides the config system, protocol helpers, and cryptographic primitives. It can be consumed from Maven or included as a local build via the `session.libsession_util.project.path` system property.

The `app` module contains almost all runtime behavior. Most architecture discussions below refer to code in `app/src/main/java` plus flavor-specific code under `app/src/<flavor>`.

## 2. Build, Flavors, and Runtime Configuration

The app has two major axes of build configuration:

- Build types: `debug`, `release`, `releaseWithDebugMenu`, `qa`, `automaticQa`
- Distribution flavors: `play`, `fdroid`, `website`, and optionally `huawei`

### Source-set overlays

The main source set is augmented by shared overlays:

- `src/firebaseCommon`: code shared by Firebase-enabled variants
- `src/nonPlayCommon`: code shared by non-Play variants
- `src/nonDebug`: code shared by non-debug build types

In practice:

- `play` and `fdroid` include Firebase push code
- `huawei` swaps in Huawei push code
- `website` uses no-op push wiring
- non-Play variants disable Play-specific behavior through build config

### Important build-time switches

`app/build.gradle.kts` defines several runtime-relevant values:

- `DEFAULT_ENVIRONMENT`: mainnet vs devnet default
- `CHECK_VERSION`: whether app-version enforcement/checking is enabled
- `PLAY_STORE_DISABLED`: gates store-specific behavior
- `DEVICE`: identifies Android vs Huawei device environment
- `PUSH_KEY_SUFFIX`: provider-specific push-key suffix
- `PRO_BACKEND_DEV`: currently the injected Pro backend configuration object

Manifest placeholders are also used to vary:

- app name
- content-provider authority postfix
- network security config

### Secure local configuration

The app has several local secure-state stores:

- `DatabaseSecretProvider` generates the SQLCipher key and seals it with Android Keystore
- `LoginStateRepository` stores the logged-in account state as Keystore-sealed JSON
- regular user preferences live in `PreferenceStorage` / shared preferences and are used for purely local settings such as UI and notification preferences

This distinction matters during handover:

- account identity and database access are securely persisted
- app preferences are local-only and not part of cross-device config sync
- config objects are the cross-device source of truth for shared user state

## 3. Process Startup and Runtime Lifecycle

The Android process entry point is `ApplicationContext`, the `@HiltAndroidApp` application class.

At process start, `ApplicationContext.onCreate()` performs process-wide initialization:

1. initialize SQLCipher support
2. configure the messaging module service bindings
3. install Conscrypt
4. initialize logging and crash handling
5. register a `ProcessLifecycleOwner` observer
6. initialize WebRTC and blob-provider support
7. instantiate startup components

That startup sequence is intentionally split into two lifetimes.

### Process-lifetime services: `OnAppStartupComponent`

`OnAppStartupComponents` is created once per process and starts services that should exist regardless of login state. Examples include:

- database migration bootstrap
- notification-channel migration/creation
- open-group poller orchestration
- subscription-provider coordination
- auth-aware component bootstrap
- logging helpers and current-activity tracking

These services generally register observers and then stay resident for the lifetime of the process.

### Session-lifetime services: `AuthAwareComponent`

`AuthAwareComponentsHandler` watches `LoginStateRepository.loggedInState`. When a user logs in, it launches each `AuthAwareComponent` in its own supervised coroutine. When the user logs out, it calls `onLoggedOut()` and cancels the active work.

This is the main pattern used for long-running background services that should only exist while a session is active, for example:

- config upload
- config-to-database reconciliation
- DM polling
- push registration
- notifications
- Pro state management
- expiring-message handling

The usual building block is `LoginStateRepository.flowWithLoggedInState { ... }`, which turns any reactive pipeline into a session-scoped flow without every consumer having to understand auth directly.

### Foreground/background state

Foreground state is observed separately via `AppVisibilityManager`, which wraps `ProcessLifecycleOwner`. This is used by background polling and other subsystems that need to behave differently when the app moves between foreground and background.

## 4. Core Architectural Patterns

Several patterns repeat across the entire app.

### Flow-first reactivity

The dominant mental model is:

1. observe state changes as `Flow`
2. combine and transform them
3. emit UI state or trigger side effects

This applies equally to repositories, ViewModels, and long-running background services.

Examples:

- repositories merge database notifications, config updates, and preference changes
- `DefaultConversationRepository.observeConversationList()` is a representative example: it merges several database and config signals, debounces them, and then re-queries to emit a fresh list
- ViewModels combine repository outputs into `StateFlow`
- `HomeViewModel` is a representative consumer that combines conversation, typing, and preference state into one screen model
- one-shot UI events use `SharedFlow` with no replay
- auth-aware services collect flows until their coroutine is cancelled

The advantage is consistency: most features are built as reactive pipelines rather than imperative refresh logic.

### Native-backed config plus relational projection

Shared user state is not authored directly in SQLite tables. It is authored in config objects backed by `libsession-util`, then projected into local SQLCipher tables for queryability and UI rendering.

### Dependency injection with scoped coroutines

Hilt provides app singletons, workers, and manager-scoped coroutine services. A dedicated `@ManagerScope` coroutine scope is injected into many runtime managers so they can expose long-lived reactive state without depending on activities or fragments.

## 5. Config System and Cross-Device Sync

The config system is one of the most important pieces of the app. It is how Session keeps user state consistent across devices without a central plaintext server.

### What lives in config

The main user config domains are:

| Config | Content |
|---|---|
| `User Profile` | Display name, avatar, Pro-related profile flags |
| `Contacts` | Contacts and metadata such as block state |
| `Convo Info Volatile` | Per-conversation metadata such as last-read timestamps |
| `User Groups` | Membership in groups and communities |
| `Group Info / Members / Keys` | Closed-group admin state |

### Ownership model

`ConfigFactory` is the Kotlin-side owner of all native config objects.

It is responsible for:

- lazily initializing per-account and per-group config instances
- guarding access with `ReentrantReadWriteLock`
- persisting serialized dumps to `ConfigDatabase`
- emitting `configUpdateNotifications`

There are two important write patterns:

- `withMutableUserConfigs { ... }` and `withMutableGroupConfigs { ... }` for normal mutation
- "dangerous" accessors for lower-level flows that need manual lifecycle control

### Source-of-truth boundaries

For handover purposes, the cleanest model is:

- config objects are the source of truth for cross-device user state
- SQLCipher tables are local projections optimized for queries, joins, and UI rendering
- shared preferences store local-only application settings

If something should sync across devices, it usually belongs in config first and only then in relational storage.

### Upload path

`ConfigUploader` is an `AuthAwareComponent` that watches config changes and path availability.

For user configs it reacts to:

- a newly available onion path
- local config mutations that did not come from a merge

For group configs it separately reacts to:

- path availability
- group config update notifications

Important details:

- uploads are debounced
- retries use uniform retry helpers
- group config upload is admin-only
- group keys are pushed before group info/members because later configs depend on them

### Reconciliation path back into the database

`ConfigToDatabaseSync` listens to config and conversation flows and keeps the relational model in sync. It is responsible for tasks such as:

- ensuring threads exist for all config-backed conversations
- applying last-read values from `Convo Info Volatile`
- pruning DB rows and recipient settings when conversations disappear from config
- cleaning up community/group-specific local state when configs remove them

This means the database is not a peer source of truth; it is a materialized local view derived from config plus message history.

### Direct merge paths

Not all config changes arrive through periodic sync. Some incoming push payloads can carry closed-group config updates directly. In those cases, `PushReceiver` can merge incoming group config messages into `ConfigFactory` immediately before the normal reactive reconciliation path updates SQLite.

## 6. Networking

There are two distinct networking worlds in the app.

### 6.1 Session Network stack

Messaging, config push/pull, swarm access, and ONS resolution use the Session Network stack.

The core request flow is:

```text
Business API
  -> AutoRetryApiExecutor
  -> BatchApiExecutor
  -> SnodeApiExecutorImpl / SwarmApiExecutorImpl
  -> OnionSessionApiExecutor
  -> OkHttpApiExecutor
```

Responsibilities by layer:

- Business APIs: typed operations such as store/retrieve/delete message; these are typically signed with the account's Ed25519 key before leaving the app
- `AutoRetryApiExecutor`: exponential-backoff retry of retryable failures
- `BatchApiExecutor`: coalesces batch-compatible requests into `/batch`, typically within a short batching window to reduce round-trips
- `SnodeApiExecutorImpl`: converts typed request objects into JSON-RPC snode calls
- `SwarmApiExecutorImpl`: adds swarm targeting and 421-handling when a node is no longer in the target swarm
- `OnionSessionApiExecutor`: chooses a three-hop onion path from `PathManager`, encrypts the request, and sends it through the guard node
- `OkHttpApiExecutor`: performs the final HTTPS call and enforces connection limits

Additional implementation details worth knowing:

- a `DirectSessionApiExecutor` exists for non-onion/test scenarios
- seed-node bootstrapping uses a separate OkHttp instance with certificate pinning
- the default OkHttp executor limits concurrent outbound connections with a semaphore
- retry decisions are not hard-coded in one place; each layer has its own error manager and bubbles retry intent upward

Error handling is layered as well. Each level classifies failures and communicates retry intent upward as `ErrorWithFailureDecision`, so retry policy stays centralized.

### 6.2 Dedicated Server APIs

Some features use a separate server-API stack rather than swarm storage. The most important example is Pro. These APIs are still typed and injected, and under the default executor they are normally sent through the onion transport as `HttpServerRequest`s, but the backend authority and data model are server-backed rather than decentralized across the user's swarm.

This distinction should be made explicit in handover discussions because debugging "networking" in Session can mean either:

- onion-routed decentralized swarm/snode traffic, or
- onion-routed or direct server-backed API traffic, depending on executor choice

## 7. Message Ingress, Processing, and Egress

The message pipeline is another handover-critical area.

### Incoming message sources

Messages can enter through several paths:

- DM swarm polling
- closed-group polling
- open-group/community polling
- decrypted push notifications

The transport differs, but the app tries to converge on shared parsing and processing stages as quickly as possible.

### Parsing

`MessageParser` turns encrypted payloads into typed domain messages. It handles:

- envelope decoding for 1:1, closed-group, and community messages
- signature timestamp validation
- block-state checks
- self-send validation
- duplicate timestamp detection
- Pro metadata extraction

The parser outputs:

- a typed `Message`
- the decoded protobuf content
- optional decoded Pro metadata

### Processing

`ReceivedMessageProcessor` is the main coordinator for applying parsed messages.

Its responsibilities include:

- serializing work per thread using a per-thread lock
- creating threads when allowed
- dispatching to message-specific handlers
- updating thread read state after processing
- applying community reaction updates
- coordinating group updates, visible messages, read receipts, typing indicators, call messages, and unsend logic

The main design point is that parsing is not persistence; the processor is where transport data becomes app state.

### Outgoing path

Outgoing work is handled through `JobQueue`, a persistent in-app job system.

Key characteristics:

- jobs are persisted before execution
- dispatch is split across `rx`, `tx`, `media`, and `openGroup` lanes
- open-group work is further partitioned by community address
- failures retry with exponential backoff
- pending jobs can be resumed from the database after restart

This queue is the main reliability mechanism for send/upload/download work and should be treated as part of core message delivery architecture, not as an implementation detail.

## 8. Pollers and Background Sync

The app has multiple poller families because different conversation types have different transport semantics.

### DM poller

`PollerManager` is an `AuthAwareComponent` that owns the logged-in user's 1:1 poller instance. It exposes:

- whether polling is currently active
- a manual `pollOnce()` API used by background workers and recovery flows

### Closed-group pollers

`GroupPollerManager` is a process-lifetime startup component that derives the active set of closed-group pollers from:

- login presence
- network availability
- `User Groups` config state

It creates and tears down individual `GroupPoller` instances automatically when group membership or `shouldPoll` state changes.

### Open-group/community pollers

`OpenGroupPollerManager` is also a startup component. It watches the user's community config and maintains one `OpenGroupPoller` per community server base URL, not per room. A single server poller can therefore service multiple communities.

### Background fallback polling

Push is not the only wake-up mechanism. `BackgroundPollManager` schedules `BackgroundPollWorker` whenever the user is logged in, and re-schedules it when the app moves to the background.

`BackgroundPollWorker` can trigger manual polls for:

- 1:1 DMs
- closed groups
- open groups

This worker is the fallback path that keeps the app progressing when the process is backgrounded or push delivery is unavailable/delayed.

### Why there are multiple systems

There is no single "poller" in Session Android. The architecture intentionally separates:

- DM swarm polling
- closed-group polling
- open-group server polling
- periodic background wake-ups

When debugging message-delivery issues, identifying which of these paths is responsible is usually the first step.

## 9. Notifications

Notifications span app startup, push registration, message ingest, privacy policy, and background polling.

### Channels

`NotificationChannelManager` is a startup component that creates and migrates notification channels. The current categories are:

- one-to-one messages
- group messages
- community messages
- calls
- lock status

This manager also handles locale-driven channel recreation so user-visible channel names stay translated.

### Push provider wiring by flavor

Notification transport is flavor-specific:

- `play` / `fdroid`: Firebase services and token fetchers
- `huawei`: Huawei push services and token fetchers
- `website`: no-op push module

This flavor split is important enough to be documented because notification behavior is not uniform across distributions.

### Push registration flow

`PushRegistrationHandler` is an auth-aware reactive service that watches:

- group config changes
- push-enabled preference state
- platform push token availability

It computes the desired registration set and stores it in `PushRegistrationDatabase`, then enqueues `PushRegistrationWorker` to synchronize actual backend registrations.

Desired registrations include:

- the current user account
- any closed group whose config indicates it should be polled

### Push receipt and decryption

`PushReceiver` is the common entry point used by platform-specific push services.

It:

- unwraps/decrypts push payloads using the logged-in notification key
- distinguishes between DM, group-message, group-config, and revoked-group namespaces
- performs duplicate detection
- routes payloads into `MessageParser` and `ReceivedMessageProcessor`
- falls back to a generic notification when payload data is absent but a wake-up is still needed

### Display policy

`NotificationProcessor` is an auth-aware reactive notification service. It watches the user's privacy preference and delegates to different handlers:

- show name and content
- show name only
- show neither name nor content

Current notification semantics are intentionally reactive:

- a message is "new" if `dateSent > thread.lastSeen`
- dismissing a notification does not mark the thread as read
- advancing `lastSeen` causes stale notifications to disappear automatically

### Read-state side effects

`MarkReadProcessor` watches thread `lastSeen` and message additions and reacts to them by:

- sending read receipts when enabled
- starting disappearing-message timers for after-read expiration modes

This is important because notification state, thread read state, read receipts, and disappearing-message timers are coupled through reactive database observation rather than direct imperative calls from screens.

## 10. Pro Architecture

Session Pro is a separate architecture slice layered on top of the rest of the app.

### High-level model

Pro combines:

- a conventional backend for entitlement/proof/revocation APIs
- platform subscription providers
- local storage in SQLCipher
- mirrored config state in the user's profile config

### Subscription providers

`SubscriptionCoordinator` selects the active subscription provider at startup.

Today the main concrete implementation is `PlayStoreSubscriptionManager` in the Play flavor. Other variants can use `NoOpSubscriptionManager` or future provider implementations.

The billing layer is therefore flavor-dependent, unlike message transport.

### Entitlement refresh

`FetchProDetailsWorker` talks to the Pro backend and updates local state. It:

- fetches entitlement details from the backend
- stores the raw result in `ProDatabase`
- mirrors expiry or removal state into `userProfile` config
- schedules proof generation when appropriate

### Proof generation

`ProProofGenerationWorker` generates a rotating private key, requests a proof from the Pro backend, and stores the resulting proof plus rotating key in the user's profile config.

This matters because Pro is not only a local entitlement check; some Pro state is propagated through config and later attached to messages/profile rendering.

### Revocation polling

`RevocationListPollingWorker` keeps a local revocation list in sync with the backend. It appends future polling work based on the server-provided retry interval and prunes expired revocations from local storage.

### Runtime status aggregation

`ProStatusManager` is the long-lived runtime aggregator. It combines:

- profile-config badge visibility
- locally cached Pro details
- debug overrides
- post-Pro launch state

It exposes a `StateFlow` consumed by UI and other services and is also responsible for arranging revocation polling while Pro is active/post-launch.

### Message and profile effects

Pro is not only a settings page concern. It also affects:

- which profile features are shown in recipient data
- whether inbound message Pro metadata is accepted and surfaced
- feature bitsets stored in message tables for later UI/analytics usage

When debugging Pro issues, it is therefore important to check both:

- backend/worker state
- config/profile propagation into normal message and recipient models

## 11. Persistence and Migrations

Most persistent app data lives in a single SQLCipher-encrypted SQLite database opened by `SQLCipherOpenHelper`.

### Database security

- the SQLCipher key is generated by `DatabaseSecretProvider`
- the key is sealed with Android Keystore
- login state is stored separately and also sealed with Android Keystore

### Schema ownership

`SQLCipherOpenHelper` owns:

- schema creation
- migration execution
- version constants

`DatabaseMigrationManager` owns the helper lifecycle and is what the DI graph exposes as the process-wide database entry point.

### Major persistent areas

| Area | Purpose |
|---|---|
| `ThreadDatabase` | Conversation threads |
| `SmsDatabase` / `MmsDatabase` / `MmsSmsDatabase` | Message storage and querying |
| `GroupDatabase` / `GroupMemberDatabase` | Group and community membership state |
| `LokiAPIDatabase` | Swarm, path, server, and polling state |
| `LokiMessageDatabase` | Extra message mapping/hash state |
| `ConfigDatabase` | Serialized config dumps |
| `SearchDatabase` | FTS search index |
| `RecipientSettingsDatabase` | Per-recipient local settings |
| `PushRegistrationDatabase` | Desired/actual push registration state |
| `ReceivedMessageHashDatabase` | Duplicate detection for incoming payloads |
| `pro_state` / `pro_revocations` | Pro entitlement and revocation state |
| `SessionJobDatabase` | Persisted background job state |

Not everything is in SQLCipher:

- UI and local behavior preferences remain in shared preferences
- login state uses a dedicated secure preference store

## 12. UI Architecture

The UI is mid-migration from legacy XML to Jetpack Compose.

### Migration strategy

- new screens are generally written in Compose
- legacy XML screens are retained until rewritten
- the conversation screen is the most complex Compose migration surface
- the home screen remains hybrid

Representative pieces:

- `FullComposeActivity`: base for fully Compose-driven screens
- `ConversationActivityV3`: Compose-driven conversation experience
- `HomeActivity`: hybrid entry point

### Design system

Compose theming is built around three composition locals:

```kotlin
val LocalColors     = staticCompositionLocalOf<ThemeColors> { ClassicDark() }
val LocalType       = staticCompositionLocalOf { sessionTypography }
val LocalDimensions = staticCompositionLocalOf { Dimensions() }
```

The design system provides:

- semantic color palettes with multiple theme families
- a custom typography scale
- shared spacing, icon, and shape constants
- shared components such as buttons, text fields, avatars, sheets, tab rows, and app bars

`SessionMaterialTheme` installs both the custom tokens and Material 3. Most Compose entry points use `setThemedContent { ... }` so screens can remain thin.

## 13. Dependency Injection

Hilt is the application-wide DI framework.

Important modules include:

- `AppModule`: app-scoped coroutine and serializer utilities
- `DatabaseModule`: SQLCipher helper and database singletons
- `NetworkModule`: OkHttp and networking dependencies
- `DeviceModule`: device/environment wiring
- `NotificationModule`: notification manager and push-processing semaphore
- `ProModule`: Pro backend configuration binding

The most important architectural convention here is not just "use Hilt", but "inject long-lived managers plus a manager-scoped coroutine scope and let them expose reactive state."

## 14. Other Important Runtime Components

Some subsystems do not justify their own section but are still important during handover:

- `ExpiringMessageManager`: disappearing-message lifecycle
- `AvatarUploadManager`: avatar upload/sync behavior
- `CallMessageProcessor` and WebRTC bridge: call signaling/runtime integration
- group admin-state synchronizers and cleanup handlers
- `VersionDataFetcher`: app-version data refresh
- disguise/app-lock support

If a bug seems to "happen in the background" but does not fit config, polling, notifications, or Pro, it often lives in one of these auth-aware managers.

## 15. End-to-End Flow Summaries

### App startup and login

```text
ApplicationContext.onCreate()
  -> process-wide initialization
  -> OnAppStartupComponents
      -> startup observers/managers
      -> AuthAwareComponentsHandler starts observing login state

User logs in
  -> LoginStateRepository updates
  -> AuthAwareComponentsHandler launches auth-aware services
      -> config upload/sync
      -> pollers
      -> notifications
      -> Pro services
      -> other session-lifetime managers
```

### Config synchronization

```text
Local feature changes config
  -> ConfigFactory persists dump to ConfigDatabase
  -> ConfigFactory emits update notification
  -> ConfigUploader pushes to swarm when path is available
  -> other devices merge config messages
  -> ConfigToDatabaseSync reconciles config into local SQLCipher tables
```

### Message delivery

```text
Incoming push or poller result
  -> MessageParser
  -> ReceivedMessageProcessor
  -> SQLCipher message/thread tables
  -> repositories emit flows
  -> ViewModels update StateFlow
  -> Compose / XML UI re-renders
  -> NotificationProcessor updates notifications as needed
```

### Outgoing delivery

```text
User action
  -> JobQueue persists and dispatches send/upload work
  -> network APIs send via swarm or server API
  -> local DB updates
  -> reactive flows refresh UI
```

## 16. Practical Handover Notes

For a new team, the highest-value mental model is:

1. shared state usually starts in config, not SQLite
2. most runtime behavior is implemented as long-lived reactive managers
3. login state controls a large set of background services
4. notifications and polling are tightly coupled
5. Pro is a separate server-backed subsystem that still feeds back into core profile/message state

When changing behavior, identify which layer owns it first:

- build/flavor wiring
- process startup
- auth-aware runtime manager
- config source of truth
- relational projection
- decentralized network stack
- conventional backend stack
- UI collection layer

That framing will usually narrow the relevant code faster than searching by feature name alone.
