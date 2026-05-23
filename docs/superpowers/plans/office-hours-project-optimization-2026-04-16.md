# LocalMediaHub Office Hours: Whole-Project Optimization Pass

Date: 2026-04-16

Status: DONE_WITH_CONCERNS

## What this product actually is

This is not "a media server" in the Plex sense.

It is a zero-friction LAN bridge between a Windows PC full of messy local media and an Android phone in your hand.

The winning loop is simple:

1. Open the app.
2. Reconnect instantly.
3. Land in the right library scope.
4. Browse without confusion.
5. Tap media, it plays immediately.

If any step in that loop is flaky, users do not care that tags exist or that the player has gesture controls. They bounce.

That is the whole game.

## Assumptions

- Primary user is the owner of the PC and phone, on the same LAN.
- Primary content is large local image and video collections on Windows disks.
- The product promise is convenience and trust, not "remote access from anywhere."

## Premise Challenge

Do not optimize every subsystem evenly.

Right now the highest-leverage work is not adding more features. It is aligning product promise, UI flow, and filesystem behavior so the app feels trustworthy.

The biggest gap is that several parts of the codebase say one thing and do another:

- Search UI sends a current path, but the server searches the whole cache.
- README says system roots must be configured or nothing is exposed, but config fallback auto-detects all drives.
- Planning docs claim an `/admin` web UI exists, but the shipped server only exposes JSON admin APIs and a placeholder `server/frontend`.

That kind of mismatch is what makes a project feel "almost there" forever.

## What I found

### 1. The product has a trust problem, not just a performance problem

- `server/internal/config/config.go:58` falls back from `system.allowed_roots` to `scan.GetRoots()`.
- `server/internal/config/config.go:33` auto-detects all Windows drives.
- `README.md:157` says if `system.allowed_roots` is not configured, nothing is exposed.

This means the product story says "explicitly restricted," while the implementation says "show everything we can find." For a filesystem app, that is a dangerous mismatch.

### 2. Root browsing in media-root mode looks broken in the current flow

- `server/internal/server/handler/folders.go:24` sets root folder `RelativePath` to the path separator.
- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt:238` browses using `folder.relativePath` instead of `folder.path`.

So the non-system root view appears to hand Android a symbolic separator instead of the actual configured root path. Even if this happens to work in one environment, it is fragile and the data contract is wrong.

### 3. Search is shipping the wrong user experience

- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:542` sends the current path into search.
- `android/app/src/main/java/com/juziss/localmediahub/network/MediaApi.kt:79` exposes a `path` query parameter.
- `server/internal/server/handler/search.go:17` ignores the path and searches the entire cached library.
- `server/internal/server/handler/search.go:26` always returns `Folders: []models.Folder{}`.

So the client behaves like "search in this directory," while the server behaves like "search everything, folders never match." Users feel this as inconsistency, not as an API bug.

### 4. Android first-run and reconnect flow is still heavier than it should be

- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt:90` has `tryAutoConnect()`.
- There is no call site for it in the current UI flow.
- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt:68` still lands every session on a manual connect screen.

If the user already saved a server, the app should attempt reconnect automatically and only fall back to manual entry on failure.

### 5. Auto-discovery is expensive and may never resolve to "not found"

- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt:106` enters `DiscoveryState.Scanning`.
- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt:197` uses a shared mutable `found` flag.
- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt:199` loops across `1..255`.
- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt:203` launches one coroutine per host probe.

This is brute force. It is not bounded, and the failure path never cleanly transitions to a final "not found" state. On weaker networks that feels like a hanging button.

### 6. Tags are functional, but the scaling story is still global and eager

- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt:65` loads tags on screen start.
- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:423` immediately calls `loadAllFileTags()`.
- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:496` fetches all file-tag mappings.
- `server/internal/service/tags.go:198` serves all tag associations from a single JSON-backed service.

This is fine for a small personal library. It is the wrong shape if the library gets large. Every browse session should not need the whole world's tag map.

### 7. UI polish exists, but the visual system still feels like placeholder Material

- `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt:15` and `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt:28` still use the default purple Material-style palette.
- `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt:43` enables dynamic color by default.
- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt:93` uses generic "Enter your PC server address" copy.

The app works like a personal utility, but it still looks like a starter app. That hurts perceived quality more than people think.

### 8. Sorting and browse affordances need tighter product judgment

- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt:175`
- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt:194`

Both folder sort and file sort menus iterate over every `SortOrder`, even though size sorting does not apply to folders. That is small, but it creates UI noise in the most-used screen.

### 9. The project docs overstate shipped admin capabilities

- `plan.md:189`
- `plan.md:192`
- `plan.md:262`
- `server/internal/server/server.go:95`
- `server/frontend/src/main.js:8`

The docs describe a real `/admin` web page. The server currently exposes admin JSON endpoints, and the frontend bundle is a placeholder text node. That mismatch will waste time every time someone comes back to this codebase.

### 10. Back-end path validation should be consolidated before more endpoints are added

- `server/internal/service/streaming.go:75`
- `server/internal/service/thumbnail.go:112`
- `server/internal/server/middleware/cors.go:10`
- `server/internal/server/server.go:35`
- `server/internal/server/handler/admin.go:24`

There is duplicated path-validation logic, permissive CORS, relative file storage paths for tags/config, and lifecycle-sensitive file locations. None of these are dramatic alone. Together they say the server needs one hardening pass before more surface area gets added.

## Recommendation

Pick a lane for the next sprint:

### Lane A, recommended: Reliability-first hardening

Goal: make the core loop boring and trustworthy.

Do this first if you want the app to feel finished.

Ship:

- Fix root-folder navigation contract so configured roots browse correctly.
- Make search truly path-aware, or change the UI to explicitly say "search all media."
- Auto-connect on launch when saved server info exists.
- Replace discovery flood-scan with bounded concurrency and a real `NotFound` terminal state.
- Lock filesystem exposure to explicit allowed roots and update docs to match truth.

### Lane B: UX-first polish

Goal: make it feel premium in the hand.

Do this second.

Ship:

- Replace the default purple/dynamic theme with an intentional media-browser identity.
- Redesign the connection screen around "last connected server", one-tap reconnect, and smarter failure guidance.
- Tighten browse actions so sorts and filters only show options that matter in context.
- Add empty states and loading states that explain what the app is doing.

### Lane C: Scale-first library mode

Goal: prepare for very large collections.

Do this after A unless you already have huge libraries.

Ship:

- Move tags from one JSON blob toward indexed storage.
- Stop preloading all file-tag associations on every browse session.
- Add search indexing or directory-scoped search paths instead of whole-library scan per query.
- Introduce directory-level caching metadata and invalidation, not just full scan cache.

## Suggested roadmap

### Phase 1, one short hardening sprint

- Fix browse-root contract.
- Fix search scope contract.
- Fix default exposure contract.
- Fix discovery terminal states.
- Add tests around path validation, search scoping, and connection fallback.

### Phase 2, one UX sprint

- Auto-reconnect.
- Better connection copy and status hierarchy.
- Real visual identity.
- Simplify sort and filter surfaces.

### Phase 3, one scale sprint

- Lazy tag loading.
- Indexed tag lookup.
- Search acceleration.
- Better storage ownership for `.data`, `config.yaml`, and cache directories.

## Concrete implementation targets

### Android

- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt`
- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt`
- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt`
- `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt`
- `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt`

### Go server

- `server/internal/config/config.go`
- `server/internal/server/handler/folders.go`
- `server/internal/server/handler/search.go`
- `server/internal/service/path.go`
- `server/internal/service/streaming.go`
- `server/internal/service/thumbnail.go`
- `server/internal/server/handler/admin.go`
- `server/internal/server/server.go`

### Docs

- `README.md`
- `plan.md`

## What I would do next

I would not start with design polish.

I would do a compact "trust and flow" batch first:

1. Fix media-root browsing.
2. Make search behavior honest.
3. Auto-reconnect saved servers.
4. Bound discovery and give it a real failure state.
5. Lock down allowed-root behavior and rewrite docs to match.

After that, the UI work will actually compound instead of decorating confusion.

## Verification notes

- Go package test sweep completed successfully with local `GOCACHE`.
- Android `assembleDebug` could not complete in this environment because Gradle needed to download `gradle-8.9-bin.zip` and network access is restricted.

## Assignment

Next real-world action: run a single reliability sprint, not a broad refactor.

If you want me to continue from this doc, the best next step is to implement Phase 1 end to end in code.
