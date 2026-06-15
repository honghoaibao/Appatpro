# Revision — Launcher icon update (v1.0.9)

_Date: 2026-05-29_
_Files changed: 16 + 1_

---

## Vấn đề

`res/drawable/icon_app.png` (logo AT PRO gradient) đang được `AppLogo()` dùng đúng
trong Dashboard header. Tuy nhiên các mipmap launcher icon (home screen) vẫn là
bộ icon cũ (tròn xanh nhạt) — chưa đồng bộ với branding mới.

---

## Thay đổi

Resize `icon_app.png` (2000×2000px, LANCZOS) → thay thế toàn bộ mipmap:

| Density | Folder | `ic_launcher` | `ic_launcher_round` | `ic_launcher_foreground` |
|---------|--------|---------------|---------------------|--------------------------|
| mdpi    | mipmap-mdpi    | 48×48   | 48×48   | 72×72   |
| hdpi    | mipmap-hdpi    | 72×72   | 72×72   | 108×108 |
| xhdpi   | mipmap-xhdpi   | 96×96   | 96×96   | 144×144 |
| xxhdpi  | mipmap-xxhdpi  | 144×144 | 144×144 | 216×216 |
| xxxhdpi | mipmap-xxxhdpi | 192×192 | 192×192 | 288×288 |

`ic_launcher_foreground` scale 1.5× để fill vùng 108dp adaptive icon grid đúng spec.

`ic_launcher_background` trong `colors.xml`: `#87CEEB` → `#015C92` (match màu nền logo).

---

## Files changed

15× mipmap PNG + 1× `values/colors.xml`
