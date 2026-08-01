# Task 8 — Android Reader Settings: Page-Turn Style Chips

## Initial implementation (commit 99474ea)

Added 3 page-turn FilterChips (NONE / COVER / SIMULATION / DRAG) to
`ReaderSettingsSheetContent`, gated by `enabled = (readingMode == CHAPTER)`
so they disable in SCROLL mode. Added 3 Robolectric Compose tests covering
render-in-chapter-mode, chip-click-fires-onchange, and disabled-in-scroll-mode.

Result: 14/15 green; `page_turn_chip_click_fires_onchange` failed.

## Fix round 1 — failing test `page_turn_chip_click_fires_onchange`

### Reproduction

Re-ran the full class **3 times** (once normal, once `--rerun-tasks`,
once more normal). Failure was **deterministic** (not a flake) every run.

### Actual failure text (from test-results XML)

```
java.lang.AssertionError: expected:<COVER> but was:<null>
  at ReaderSettingsSheetTest.page_turn_chip_click_fires_onchange(ReaderSettingsSheetTest.kt:307)
```

i.e. `captured == null` — the chip's `onClick` lambda never executed.
The diagnostic run confirmed:
- `assertIsEnabled()` on the COVER chip PASSED (chip is enabled, default
  `readingMode = CHAPTER` => `isChapter = true`).
- After `performClick()` + `waitForIdle()`, `captured` was still `null`.

So the prior "Compose timing issue" diagnosis was wrong: `waitForIdle()`
before the assert did NOT fix it. The click was simply not delivered.

### Root cause

`ReaderSettingsSheetContent` wraps its whole body in a `verticalScroll`
Column. The font-family and theme chips (the 3 passing sibling tests) sit
near the TOP of the content and are inside the initial scroll viewport, so
`performClick()` (which under Robolectric injects input events that only
reach nodes in the visible viewport) delivers the click and fires onClick.

The page-turn chips render near the BOTTOM of the content, below the fold.
`performClick()`'s injected input event never reaches the off-screen
FilterChip, so onClick never fires — but `assertIsEnabled()` still passes
because it queries the semantics tree (not input dispatch). This is a known
Robolectric + Compose limitation for scrollable containers.

### Fix applied

Switched ONLY the failing test from `performClick()` to
`performSemanticsAction(SemanticsActions.OnClick)`. This invokes the chip's
onClick lambda directly through the semantics node, bypassing input/viewport
dispatch entirely, and exercises the exact same production code path a real
tap would. `SemanticsActions` / `performSemanticsAction` were already
imported in the file (used by the letter-spacing slider test).

The 3 passing sibling tests were left untouched (their `performClick()` works
because their chips are on-screen).

No production code changed — the production `onClick` lambda was always
correct.

### Final result

```
<testsuite tests="15" skipped="0" failures="0" errors="0"
           timestamp="2026-08-01T14:48:49.431Z" time="10.226">
```

15/15 green, deterministic across reruns.
