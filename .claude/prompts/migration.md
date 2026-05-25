# Prompt: migration

Use when migrating a Flutter screen to native Android, or migrating a data schema.

---

## Flutter → Native Android migration

You are migrating a Flutter screen to a native Android screen in Appatpro.

Steps:
1. Identify the screen and its Riverpod providers
2. Map provider data sources → Room DAOs or `LocalRepository` methods
3. Map MethodChannel calls → direct Kotlin function calls
4. Build native Compose screen in `android/app/src/main/kotlin/com/atpro/ui/<feature>/`
5. Keep Flutter screen intact and functional during migration
6. Add a feature flag or build variant to toggle between Flutter and native
7. Test native screen on ≥2 target devices (Xiaomi + Samsung recommended)
8. Remove Flutter screen only after native is validated
9. Update `architecture.md` and add revision note

Do NOT:
- Remove the Flutter screen before the native replacement is tested
- Move business logic from Kotlin into the new Compose screen
- Use a third-party Compose library not already in the project

---

## Room schema migration

You are migrating the Room database schema.

Steps:
1. Increment `DATABASE_VERSION` in `AtProDatabase.kt`
2. Write a `Migration(from, to)` object with the exact SQL
3. Add it to `addMigrations(...)` in the database builder
4. Write a migration test using `MigrationTestHelper`
5. Never use `fallbackToDestructiveMigration()` in production

Migration SQL rules:
- `ALTER TABLE` for adding columns (SQLite supports only `ADD COLUMN`)
- New table → `CREATE TABLE IF NOT EXISTS`
- Column rename / deletion → requires full table recreation (copy → drop → rename)

Document in ADR if the schema change reflects an architectural decision.
