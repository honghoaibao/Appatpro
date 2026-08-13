# Prompt: debug

Use when investigating a bug. Follows diagnose skill.

---

You are debugging an issue in Appatpro.

Step 1: Gather context
- Device model + Android version?
- TikTok version?
- Which phase: farm start / account switch / popup / DB / notification / other?
- LogCat output (filter: `adb logcat -s AutomationEngine:D NodeTraverser:D PopupHandler:D AtProDB:D`)

Step 2: Layer identification
- Accessibility layer (node not found, wrong element clicked)?
- Automation layer (wrong state, account not switching)?
- Data/DB layer (data not saved, query returning wrong result)?
- Bridge layer (Flutter not receiving event, MethodChannel not responding)?
- Flutter layer (UI displaying wrong state)?

Step 3: Follow diagnose.md loop
See `.claude/skills/mat/diagnose.md`

Step 4: Fix
- Minimal change
- Add regression test before merging

Common Appatpro bug patterns:
- TikTok UI change → `NodeTraverser` selector no longer matches → update text/content-desc selector
- `TikTokAccessibilityService.instance` is null → service was killed by OEM battery optimiser → guide user to whitelist
- Farm stops after N accounts → memory leak in `AutomationEngine` → check references not released on stop
- Schedule not resuming after reboot → `BootReceiver` not registered in AndroidManifest or alarm not restored
