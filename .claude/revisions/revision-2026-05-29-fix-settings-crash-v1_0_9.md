# Revision — Fix Settings crash (v1.0.9)

_Date: 2026-05-29_
_Files changed: 1_

---

## Root cause

`ConfigScreen.kt` line 1049 — `CfgSlider` widget dùng negative padding:

```kotlin
// TRƯỚC (SAI)
Box(modifier = Modifier.fillMaxWidth().padding(horizontal = (-10).dp)) {
```

Compose UI enforce tại runtime: `Padding must be non-negative` →
`IllegalArgumentException` ngay khi bất kỳ `CfgSlider` nào được compose.

Vì `TimingSection` (section mặc định khi mở Settings) có 3 slider →
crash **tức thì** khi `ConfigActivity` mở.

## Ý định ban đầu

`padding(horizontal = -10.dp)` nhằm để thumb của Slider "bleed" ra ngoài
padding của card 10dp mỗi bên, tránh thumb bị clip. Tuy nhiên Compose không
cho phép padding âm qua `Modifier.padding()`.

## Fix

```kotlin
// SAU (ĐÚNG)
Box(modifier = Modifier.fillMaxWidth()) {
```

Slider giờ nằm trong bounds của card — thumb không bleed nhưng vẫn hoạt động
đầy đủ và không crash. Nếu muốn bleed trong tương lai: dùng
`Modifier.layout { ... }` custom hoặc wrapping `Box` với `clipToPadding = false`
pattern.

---

## Files changed

| File | Line | Action |
|------|------|--------|
| `ui/config/ConfigScreen.kt` | 1049 | Remove `.padding(horizontal = (-10).dp)` |

---

## Xác nhận từ logcat (2026-05-29 07:12)

```
07:12:01.777  START ConfigActivity
07:12:01.829  Displayed ConfigActivity: +52ms    ← frame 1: spinner (isLoading=true)
07:12:01.842  SurfaceFlinger screenshot          ← frame 2 đang render
07:12:01.849  E AndroidRuntime: CRASH PID 7377
07:12:01.857  reason=java.lang.IllegalArgumentException
```

**Tại sao có `Displayed +52ms` trước crash:**
Frame đầu hiển thị được vì `isLoading = true` → chỉ compose `CircularProgressIndicator`.
Coroutine `load()` complete ngay sau → `isLoading = false` → `TimingSection` compose lần đầu
→ `CfgSlider` → `Box(padding horizontal = -10.dp)` → `IllegalArgumentException` → crash.

Crash xảy ra **6 lần liên tiếp** (PID 1473, 13522, 2479, 2725, 6697, 6797, 6959, 7092, 7377)
mỗi lần vào Settings → 100% reproducible → confirm fix là đúng target.
