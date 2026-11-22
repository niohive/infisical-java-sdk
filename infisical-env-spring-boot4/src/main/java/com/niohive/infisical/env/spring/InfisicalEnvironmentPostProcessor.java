package com.niohive.infisical.env.spring;

import com.niohive.infisical.env.InfisicalClient;
import com.niohive.infisical.env.InfisicalSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * Loads secrets from Infisical into the environment before the application starts, so that placeholders such
 * as {@code ${DB_PASSWORD}} resolve from Infisical. The secrets sit just after the process environment:
 * environment variables still win over Infisical, and Infisical wins over application.yaml. A configured but
 * failing Infisical stops startup rather than running without secrets. Registered through spring.factories.
 */
public final class InfisicalEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Name of the property source that holds the secrets. */
    public static final String PROPERTY_SOURCE_NAME = "infisical";

    private final Log log;
    private final InfisicalClient client;

    /**
     * Called by Spring Boot.
     *
     * @param logFactory Spring Boot's factory for logs written before logging is set up
     */
    public InfisicalEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this(logFactory.getLog(InfisicalEnvironmentPostProcessor.class), new InfisicalClient());
    }

    InfisicalEnvironmentPostProcessor(Log log, InfisicalClient client) {
        this.log = log;
        this.client = client;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Optional<InfisicalSettings> configured = InfisicalSettings.from(key -> property(environment, key));
        if (configured.isEmpty()) {
            log.info("Infisical not configured (INFISICAL_CLIENT_ID unset); using environment variables only");
            return;
        }
        InfisicalSettings settings = configured.get();
        if (settings.url().regionMatches(true, 0, "http://", 0, "http://".length())) {
            log.warn("Infisical URL uses plain HTTP; the client secret and secrets travel unencrypted");
        }
        Map<String, Object> secrets = new HashMap<>(client.fetchSecrets(settings));
        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, secrets);
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
        log.info("Loaded " + secrets.size() + " secrets from Infisical (" + settings + ")");
    }

    /** Spring's error for an unresolvable placeholder quotes the whole value, so only the key is reported. */
    private static String property(ConfigurableEnvironment environment, String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + " contains a ${...} placeholder that can't be resolved");
        }
    }
}
