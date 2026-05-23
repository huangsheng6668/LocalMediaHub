# LocalMediaHub CEO Review

Date: 2026-04-16

Branch: `master`

Mode: `SELECTIVE_EXPANSION`

Status: `issues_open`

Source plan: `docs/superpowers/plans/office-hours-project-optimization-2026-04-16.md`

## Point of view

The current optimization plan is directionally right, but it is still thinking like a cleanup sprint.

This product should not aspire to be "a nicer file browser over LAN."

It should be the fastest way for one person to enjoy the chaotic pile of media already sitting on their Windows machine, from a phone, anywhere in the house, with zero fear and zero setup tax.

That is a real product.

Without that framing, you will keep polishing symptoms.

## 1. What the current plan gets right

1. It correctly puts trust first.
   Filesystem exposure, broken search semantics, flaky reconnect, and discovery weirdness all hit the user's confidence directly.

2. It correctly delays big-scale work.
   The app does not need distributed systems cosplay. Not yet.

3. It correctly identifies that the current UI polish is downstream of product clarity.
   Good instinct.

## 2. Where the current plan is still too small

1. It fixes the current browse loop, but does not change the shape of the browse loop.
   Today the app is still mostly "pick a drive, pick a folder, keep drilling." Functional. Not sticky.

2. It treats tags as a performance/scaling concern, not a product primitive.
   In a media product, tags are not just metadata. They are the start of saved collections.

3. It treats reconnect as plumbing, not as the opening scene of the product.
   If the app always opens cold on `ConnectionScreen.kt`, you are forcing the user to re-earn convenience every session.

4. It keeps the PC mental model too exposed.
   Raw drive letters and path strings leak implementation into the user experience. Users tolerate that in a debugging tool. They do not love it in a media app.

## 3. The 10-star version

The 10-star version is:

- Open app.
- It already knows the last server.
- It already knows the libraries the user cares about.
- It lands on a real home surface, not a blank transport screen.
- The user sees `Continue Watching`, `Recent`, `Favorites`, `Collections`, and maybe `Photos`.
- Search is instant and scoped to human concepts, not raw paths.
- The user never wonders, "is this app going to expose my whole disk?"

That is the version that people actually keep using.

## 4. Recommended scope expansions

These are recommendations, not automatic decisions.

### 4A. Add a first-class Library concept

Recommendation: add to scope.

Right now `server/internal/config/config.go`, `server/internal/server/handler/folders.go`, and the Android browse flow are still centered on roots, drives, and paths.

That is implementation detail.

The product should promote named libraries as the top-level unit:

- Movies
- Photos
- Anime
- Kids
- Downloads to sort later

Even if each library is just a wrapper around one or more filesystem roots, the user should think in libraries, not in `D:\` and `E:\`.

Why this matters:

- Fixes the trust story.
- Makes onboarding clearer.
- Gives search a sane scope.
- Creates a clean future home screen.

### 4B. Add a Home surface, not just a browse screen

Recommendation: add to scope.

`BrowseScreen.kt` is currently the product.

That is too thin.

The app needs a lightweight home layer above raw browsing:

- Last connected server
- Continue watching
- Recently viewed
- Favorites
- Libraries
- Tag-based collections

Not a giant content feed. Just enough to avoid making every session start from zero.

Why this matters:

- Changes the emotional feel of the product immediately.
- Makes reconnect work visible.
- Turns tags and favorites into something the user can return to.

### 4C. Promote tags into Collections

Recommendation: add to scope if you want one differentiator, otherwise defer.

The current plan sees tags mostly as a scaling/API shape issue in `BrowseViewModel.kt` and `server/internal/service/tags.go`.

That is underselling them.

For a personal media app, tags should become user-facing collections:

- Watch later
- Family
- Wallpapers
- Reference
- Best clips

This is the wedge where the app stops being just "remote file access."

### 4D. Introduce a metadata store upgrade path

Recommendation: defer implementation, keep in design scope.

Do not build the SQLite migration in the first sprint.

But do acknowledge that `tags.json` plus global scans will become the bottleneck for:

- fast scoped search
- recent items
- continue watching
- per-library metadata
- collection views

You do not need to ship the database now.

You do need to stop pretending the current storage shape is the end state.

## 5. What should stay out of scope

1. Multi-user accounts.
   Wrong level. This is not your bottleneck.

2. Remote internet streaming.
   Adds auth, NAT, security, and operational pain. Too early.

3. Fancy admin web UI.
   You do not even have the actual app flow nailed yet.

4. Infinite performance work for hypothetical giant libraries.
   Fix the experience first. Then scale the hot path that remains.

## 6. Revised plan

### Phase 1, still first

Keep the current reliability sprint.

- Fix root browse contract.
- Fix search semantics.
- Fix allowed-root truth.
- Fix reconnect and discovery.
- Add tests.

No argument there.

### Phase 2, expanded

Do not jump straight from reliability to visual polish.

Add product structure first:

- Library model
- Home surface
- Last server + last location restore
- Continue watching / recent items
- Tag collections

This is the phase where the app becomes a product.

### Phase 3

Then do the deeper architecture and scaling work:

- metadata store evolution
- scoped indexing
- cheaper tag/collection fetches
- thumbnail/search pipeline improvements

## 7. Specific critical gaps

1. **CRITICAL GAP**: the current plan fixes trust bugs but does not remove path-first product thinking.

2. **CRITICAL GAP**: there is no explicit home experience in the roadmap, even though reconnect and return visits are core to the product.

3. **WARNING**: tags are treated as implementation cleanup instead of the beginning of saved collections.

4. **WARNING**: the plan still gives too much weight to `/admin` ideas relative to the actual mobile user journey.

## 8. CEO verdict

The plan is good enough to prevent obvious damage.

It is not yet ambitious enough to create delight.

My recommendation is not "blow up scope."

It is: hold the reliability sprint exactly as planned, then cherry-pick two expansions immediately after it:

1. Library abstraction
2. Home surface with recent/resume/favorites

If you do just those two, this stops feeling like a remote filesystem client and starts feeling like a real consumer product.

That is the line.

## 9. Next move

Best next step:

1. Implement Phase 1 from the office-hours plan.
2. Then run `/plan-eng-review` on the revised scope that includes Libraries + Home.
3. After that, run `/plan-design-review`, because the expanded scope now clearly has UI consequences.
