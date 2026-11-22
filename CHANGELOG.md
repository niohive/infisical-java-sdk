# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `infisical-env-core`: loads a folder's secrets, including imports, from Infisical with Universal Auth. Java 8+, no runtime dependencies.
- `infisical-env-spring-boot4`: an `EnvironmentPostProcessor` that adds the secrets to the Spring Boot 4 `Environment`, after process environment variables and before `application.yaml`.
