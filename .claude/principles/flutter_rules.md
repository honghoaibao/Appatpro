# Flutter Rules

Flutter is **legacy bridge layer**. These rules prevent it from growing.

## Hard limits

- Do not add new Flutter packages without a written justification in `.claude/decisions/`
- Do not move any business logic from Kotlin to Dart
- Do not create new Flutter-only features. If a feature needs UI, it goes in a native screen first
- Do not add new MethodChannel methods without documenting them in `FlutterBridge.kt` with a comment

## Current Flutter surface (do not expand)

```
lib/screens/         — display screens
lib/services/        — bridge wrappers only
lib/widgets/         — illustrations only
```

## What is allowed

- Bug fixes in existing Flutter screens
- UI polish (colour, layout) in existing screens
- Adding display-only fields that come from existing EventChannel events
- Performance fixes in Riverpod providers (e.g. preventing unnecessary rebuilds)

## Migration direction

When a screen is ready to be replaced with native Android:
1. Build the native Compose/View equivalent in a new `ui/` module
2. Keep the Flutter screen as fallback during testing
3. Remove the Flutter screen only after the native version is validated on ≥2 target devices
4. Record the migration in `.claude/revisions/`

## Dependency pins — do not touch without testing

```yaml
flutter_riverpod: ^2.4.9
go_router: ^12.1.3
share_plus: ^7.2.2      # v9 does not exist
flutter_lints: ^3.0.1   # v4 needs Dart 3.3+
```
