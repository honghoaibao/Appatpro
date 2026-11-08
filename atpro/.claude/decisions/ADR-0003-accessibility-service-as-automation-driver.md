# ADR-0003: AccessibilityService as the sole automation driver

**Date**: 2026-05-21
**Status**: Accepted

## Context

Automating TikTok on Android without root access has limited options: ADB (requires USB/network connection), UI Automator (requires test runner), or AccessibilityService (always-on, no connection needed).

## Decision

`TikTokAccessibilityService` is the **only** mechanism for driving TikTok UI. No ADB shell, no root-level APIs, no UI Automator from production code.

## Rationale

- AccessibilityService runs persistently without USB/network connection
- Supported on all Android versions in target range (10–14)
- No additional permissions beyond `BIND_ACCESSIBILITY_SERVICE`
- User-space only — compatible with standard APK distribution

## Consequences

- All UI interaction goes through `NodeTraverser` and `AccessibilityNodeInfo`
- TikTok UI changes may break node selectors — `PopupHandler` and `AutomationEngine` must use resilient selectors
- Cannot use `performAction` on off-screen nodes — scroll first if needed
- `TikTokAccessibilityService.instance` is a nullable singleton — all callers must handle null (service not enabled)

## Alternatives considered

- **ADB over USB**: rejected — requires user to keep device connected
- **ADB over Wi-Fi (TCP)**: rejected — requires developer options, unstable on target devices
- **Root-level injection**: rejected — incompatible with standard APK distribution

## Rollback

Not applicable — no alternative without root access.
