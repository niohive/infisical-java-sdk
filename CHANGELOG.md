# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-11-22

First public release, published to GitHub Packages.

### Added

- `core`: loads a folder's secrets, including imports, from Infisical with Universal Auth. Java 8+, no runtime dependencies.
- `spring-sdk`: an `EnvironmentPostProcessor` that adds the secrets to the Spring Boot 4 `Environment`, after process environment variables and before `application.yaml`.

[Unreleased]: https://github.com/niohive/infisical-java-sdk/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/niohive/infisical-java-sdk/releases/tag/v1.0.0
