# Module: com.atpro.<name>

**Status**: Android-first ✅ / Bridge ⚠️ / Flutter-legacy ⚠️
**Created**: YYYY-MM-DD

## Responsibility
One sentence: what this module owns.

## What it does NOT own
Explicit exclusions.

## Public interface

```kotlin
class/object <Name> {
    fun publicMethod(param: Type): ReturnType
}
```

## Dependencies
```
imports:
  - com.atpro.data    — reason
  - com.atpro.db      — reason

must NOT import:
  - com.atpro.bridge  — reason (unless this IS the bridge)
```

## Room tables used
- `table_name` — read / write / read+write

## Tests
- `<NameTest>.kt` in `src/test/`
- Coverage: describe what is tested
