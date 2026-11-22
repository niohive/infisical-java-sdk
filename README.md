# infisical-env

Loads secrets from [Infisical](https://infisical.com) at startup, for any Java 8+ application, with an optional Spring Boot 4 adapter.

> **Pre-release.** Nothing is published to Maven Central yet.

`infisical-env` is an independent client. It is not affiliated with or endorsed by Infisical, Inc.

## Why

- **Zero runtime dependencies.** The core uses only the JDK: `HttpURLConnection` for HTTP, and a small built-in JSON reader. Nothing extra lands on your classpath, so there are no transitive vulnerabilities to track.
- **Java 8 to 25.** The core is Java 8 bytecode and is tested on JDK 8, 11, 17, 21 and 25.
- **Fail fast, never leak.** If Infisical is configured but broken, startup stops with a clear message. Secrets, the client ID and the client secret never appear in messages or logs.

## Artifacts

| Artifact | Use it for | Requires |
| --- | --- | --- |
| `com.niohive:infisical-env-core` | Plain Java, or any framework | Java 8+ |
| `com.niohive:infisical-env-spring-boot4` | Spring Boot 4 (includes the core) | Java 17+, Spring Boot 4.x |

Gradle:

```kotlin
implementation("com.niohive:infisical-env-core:1.0.0")
// or, for Spring Boot 4:
implementation("com.niohive:infisical-env-spring-boot4:1.0.0")
```

Maven:

```xml
<dependency>
  <groupId>com.niohive</groupId>
  <artifactId>infisical-env-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Configuration

Settings come from these keys, usually environment variables. They use [Universal Auth](https://infisical.com/docs/documentation/platform/identities/universal-auth) (a machine identity).

| Key | Required | Default |
| --- | --- | --- |
| `INFISICAL_CLIENT_ID` | yes; when unset, Infisical is skipped | |
| `INFISICAL_CLIENT_SECRET` | yes, when the client ID is set | |
| `INFISICAL_PROJECT` | yes, when the client ID is set (the project ID) | |
| `INFISICAL_ENVIRONMENT` | yes, when the client ID is set (for example `dev`) | |
| `INFISICAL_URL` | no | `https://app.infisical.com` |
| `INFISICAL_SECRET_PATH` | no | `/` |

- Values are trimmed, and a blank value counts as unset.
- When `INFISICAL_CLIENT_ID` is unset, nothing is loaded and no request is made. This keeps local development on plain environment variables simple.
- When the client ID is set but the secret, project or environment is missing, startup fails with an `IllegalStateException` that lists every missing key.
- `INFISICAL_URL` must be an `http` or `https` URL. Trailing slashes are removed.

Secrets are read once, at startup. Secrets imported into the folder are included; the folder's own secrets win over imported ones, and a later import wins over an earlier one. Secret references are expanded.

## Plain Java

```java
Map<String, String> secrets = InfisicalSettings.fromSystemEnvironment()
        .map(new InfisicalClient()::fetchSecrets)
        .orElse(Collections.emptyMap());
```

`InfisicalSettings.from(lookup)` reads the keys through any `Function<String, String>`, for example `properties::getProperty` or `map::get`. `fetchSecrets` returns an unmodifiable map and throws `InfisicalException` when Infisical can't be reached, rejects a request, or answers with something unreadable. `InfisicalClient` is thread-safe and keeps no state between calls.

## Spring Boot 4

Add `infisical-env-spring-boot4`. Nothing else is needed: it registers an `EnvironmentPostProcessor` that loads the secrets before the application context starts, so placeholders such as `${DB_PASSWORD}` in `application.yaml` resolve from Infisical.

Precedence: process environment variables > Infisical > `application.yaml`. The secrets are in a property source named `infisical`.

At startup it logs either `Infisical not configured (INFISICAL_CLIENT_ID unset); using environment variables only` or `Loaded N secrets from Infisical (InfisicalSettings[url=…, project=…, environment=…, secretPath=…])`. When the URL is plain `http://`, it also logs a warning.

## Spring Boot 2.x and 3.x

There's no adapter for these versions. Add `infisical-env-core` and this class, which works on Java 8 with Boot 2.4+ and 3.x:

```java
package com.example.config;

import com.niohive.infisical.env.InfisicalClient;
import com.niohive.infisical.env.InfisicalSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InfisicalEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Optional<InfisicalSettings> settings = InfisicalSettings.from(environment::getProperty);
        if (!settings.isPresent()) {
            return;
        }
        Map<String, Object> secrets = new HashMap<String, Object>(new InfisicalClient().fetchSecrets(settings.get()));
        MapPropertySource source = new MapPropertySource("infisical", secrets);
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
    }
}
```

```properties
# src/main/resources/META-INF/spring.factories
org.springframework.boot.env.EnvironmentPostProcessor=com.example.config.InfisicalEnvironmentPostProcessor
```

## When things go wrong

| Situation | Exception and message |
| --- | --- |
| Client ID set, other keys missing | `IllegalStateException`: `INFISICAL_CLIENT_ID is set but these are missing: …` |
| Bad `INFISICAL_URL` | `IllegalStateException`: `INFISICAL_URL must be an http or https URL …` |
| Infisical answers with an error | `InfisicalException`: `Infisical failed to log in (HTTP 401): <Infisical's message>` |
| Network failure or timeout | `InfisicalException`: `Couldn't reach Infisical to list secrets: <cause>` |
| Unusable response | `InfisicalException`: `Infisical returned an unreadable response to log in` |

Each request has a 15 second connect timeout, read timeout and overall deadline.

## Security

- Secrets, the client ID and the client secret never appear in `toString()`, exception messages or logs. The only server text ever shown is Infisical's own error `message`, cut to 200 characters and stripped of control characters.
- Redirects are never followed, so credentials and the access token never go to another host.
- The access token is never stored, and it must be visible ASCII before it's used in a header.
- Responses are capped at 16 MiB and JSON nesting at 64 levels.
- TLS uses the JVM's defaults. There's no option to turn certificate checks off.
- Plain `http://` URLs work, for example inside a private network, but the Spring Boot adapter logs a warning.

Proxies and private certificate authorities use the standard JVM system properties, such as `https.proxyHost`, `https.proxyPort`, `javax.net.ssl.trustStore` and `javax.net.ssl.trustStorePassword`.

## Building from source

Gradle runs on JDK 17 or newer (21 recommended). The full test matrix also needs JDKs 8, 11, 17, 21 and 25 installed where Gradle can find them; Gradle never downloads JDKs itself.

```sh
./gradlew build          # compile, test on JDK 17, check the published POMs
./gradlew testAllJdks    # run the tests on every supported JDK
```

Dependencies are locked, and their SHA-256 checksums are verified. After changing a dependency, regenerate both and review the diff:

```sh
./gradlew --write-verification-metadata sha256 resolveAndLockAll --write-locks build
```
