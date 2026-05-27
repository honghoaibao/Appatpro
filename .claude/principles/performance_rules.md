# Performance Rules

## Farming loop

- `videoWatchTimeMin` / `videoWatchTimeMax` define the per-video wait. Do not tighten below 3s — risks TikTok detection
- Randomise all interaction delays within ±20% of configured values
- `delayAfterLike` and `delayAfterFollow` are safety gaps — never remove them
- If farming a large account list (>20), check memory every 5 accounts. Recycle bitmaps and node references

## Memory

- `AutomationEngine` holds references to `LocalRepository` and `PopupHandler` — they are per-session. Nullify on farm stop
- `OverlayFarmMonitor` holds a `WindowManager` reference — always call `hide()` on farm end
- `LanWebSocketServer` holds open sockets — always call `stop()` on shutdown

## Database

- Room queries on `Dispatchers.IO` only. Never on main thread
- Do not load full session history into memory. Use paged queries (`PagingSource`) when list > 100 items
- Vacuum Room DB periodically (monthly) — farm runs accumulate log rows quickly

## Flutter (legacy)

- Riverpod providers should return `AsyncValue` — avoid blocking `ref.watch` on slow IO
- Use `const` constructors everywhere possible in widget trees
- `app_illustrations.dart` SVGs — cache after first render, do not re-parse on every rebuild
