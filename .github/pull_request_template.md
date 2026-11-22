## What and why

<!-- What does this change, and which issue does it address? -->

## Checklist

- [ ] Tests were written first, and each one fails without the change
- [ ] `./gradlew build testAllJdks` passes
- [ ] No new runtime dependency in `infisical-env-core`
- [ ] Any new test or build dependency is explained, and its verification metadata and lockfile changes were reviewed
- [ ] No secrets, client IDs, client secrets or tokens can reach messages, `toString()` or logs
- [ ] `CHANGELOG.md` is updated under `Unreleased` (if users will notice the change)
