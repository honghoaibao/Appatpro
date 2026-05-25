# ADR-0001: Flutter as thin bridge layer only

**Date**: 2026-05-21
**Status**: Accepted

## Context

Appatpro started with Flutter as the primary UI framework. The Android automation core (AccessibilityService, AutomationEngine) was added later as a native layer. As the project matured, it became clear that Flutter is redundant for the core functionality — all meaningful work happens in Kotlin.

## Decision

Flutter remains in the codebase **only** as a thin display layer. All business logic lives in Kotlin. `FlutterBridge` is the **only** communication boundary.

New features must be implemented in Kotlin first. Flutter screens may display the results.

## Rationale

- Accessibility automation **requires** native Android. Flutter cannot drive `AccessibilityNodeInfo`
- Room, Coroutines/Flow, WorkManager — all native. No Flutter equivalent needed
- Flutter adds build complexity and APK size without adding value at the core layer
- Long-term: Compose replaces Flutter screens incrementally

## Consequences

- Every new UI requirement → evaluate native Compose first
- Flutter dependency pins are frozen (see `flutter_rules.md`)
- `FlutterBridge` API surface must be documented and kept narrow

## Alternatives considered

- **Full Flutter**: rejected — AccessibilityService cannot be abstracted into Flutter
- **Full native removal now**: rejected — too risky, many screens already in Flutter

## Rollback

If Flutter is removed entirely: replace all `lib/screens/` with Compose equivalents, remove `FlutterBridge`, change `MainActivity` to extend `AppCompatActivity`, remove Flutter SDK from build.
