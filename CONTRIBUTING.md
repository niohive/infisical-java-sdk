# Contributing

Thanks for helping. This project is deliberately small: it does one job, with no runtime dependencies in the core. Please read this before opening a pull request.

## Before you start

- **Open an issue first** for anything bigger than a small fix, and say who needs the change today. Features without a current user aren't added.
- **Security problems** go through [SECURITY.md](SECURITY.md), never through public issues.
- Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Setting up

- Gradle runs on JDK 17 or newer (21 recommended).
- The full test matrix also needs JDKs 8, 11, 17, 21 and 25 installed where Gradle can find them. Gradle never downloads JDKs itself (`org.gradle.java.installations.auto-download=false`). Point it at your JDKs with `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`.

```sh
./gradlew build          # compile, test on JDK 17, check the published POMs, build the javadoc
./gradlew testAllJdks    # run the tests on every supported JDK
```

## How changes are made

- **Tests first.** Write a failing test, make it pass with the least code, then refactor. Every behaviour has a test, and a test must be able to fail.
- **Keep the core on Java 8.** It's compiled with `--release 8`, and its tests run on a real JDK 8.
- **Never leak secrets.** No secret, client ID, client secret or token may reach an exception message, `toString()` or a log line. Tests use marker values to check this.
- **Small, focused commits**, in [Conventional Commits](https://www.conventionalcommits.org/) style: `feat:`, `fix:`, `test:`, `build:`, `docs:`, `ci:`.
- **Branches:** create a feature branch from `develop` and open the pull request against `develop`. Releases go from `develop` to `monster`, the default branch, where CI runs.
- Update `CHANGELOG.md` under `Unreleased` for any user-visible change.

## Dependencies

- `core` never gets a runtime dependency. `verifyPublishedDependencies` fails the build if one appears.
- A new test or build dependency needs a good reason in the pull request.
- Dependencies are locked, and their SHA-256 checksums are verified. After changing one, regenerate both:

  ```sh
  ./gradlew --write-verification-metadata sha256 resolveAndLockAll --write-locks build
  ```

  Then review the diff of `gradle/verification-metadata.xml` and the `gradle.lockfile` files: only the artifacts you expected should change. Dependabot can't update these files, so a maintainer does this step for its pull requests too.
- GitHub Actions are pinned to full commit SHAs. When updating one, check that the SHA belongs to the release tag in the action's official repository.
