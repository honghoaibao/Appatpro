# Architecture Rules

1. **Android core owns all behavior.** Flutter is display only.
2. **Feature-based modules.** One package per domain (automation, accessibility, scheduler, network…). Do not create util/ catch-alls.
3. **No circular dependencies.** If A imports B, B must not import A. Check `architecture.md` dependency rules before adding any import.
4. **FlutterBridge is the only bridge.** No Dart code may call Android APIs except via MethodChannel through `at_pro_bridge.dart`.
5. **Small public interfaces.** Expose the minimum needed. Internals stay internal (`private` / `internal`).
6. **No monolithic files.** Max ~300 lines per file. Split earlier if it grows.
7. **New Android modules get their own package.** Never add files to an unrelated package to save time.
8. **Room is the only persistence.** No SharedPreferences for structured data. No cloud DB.
9. **Foreground service for long work.** Any operation lasting >30s must run in `FarmForegroundService` or a dedicated foreground service. No background services on modern Android.
10. **Every module boundary must be documented** in `architecture.md` when added.
