# Coding Rules

## Kotlin (Android)

- Prefer `data class` for models, `object` for singletons, `sealed class` for state/result
- Use `Result<T>` or sealed class for error propagation — do not throw across module boundaries
- Coroutines: use `viewModelScope` or explicit `CoroutineScope` with `SupervisorJob`. Never `GlobalScope`
- Flow: prefer `StateFlow` for state, `SharedFlow` for events
- Null safety: avoid `!!`. Use `?: return`, `?: throw`, or handle explicitly
- No `lateinit var` unless the lifecycle genuinely requires it. Prefer constructor injection or lazy
- Companion object constants: `const val` for primitives, top-level `val` for objects
- Logging: use `Log.d(TAG, ...)` in debug only. Remove `Log.e` with stack traces before release
- Comments: in Vietnamese is fine. English for public API docs

## Dart / Flutter (legacy)

- No business logic in screens. Screens call bridge, display state
- Providers (Riverpod): one provider per concern. No god providers
- No `setState` for shared state — use Riverpod
- Keep widget trees shallow. Extract widgets at ~3 levels deep

## General

- Clarity over cleverness
- Name things after what they **do**, not what they **are** (e.g. `farmOneAccount`, not `accountProcessor`)
- Tests for all new Android logic (JUnit4 + Mockito or Robolectric for integration)
- No dead code committed
