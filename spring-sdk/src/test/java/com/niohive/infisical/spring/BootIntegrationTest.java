package com.niohive.infisical.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.niohive.infisical.FakeInfisical;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** Starts a real Spring Boot application, so the spring.factories registration is exercised too. */
class BootIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    @Test
    void secretsResolvePlaceholdersInApplicationProperties() throws Exception {
        try (FakeInfisical fake = new FakeInfisical()) {
            fake.secrets(200, "{\"secrets\":[{\"secretKey\":\"EDB_PASSWORD\",\"secretValue\":\"from-infisical\"}]}");
            SpringApplication app = new SpringApplication(EmptyConfig.class);
            app.setWebApplicationType(WebApplicationType.NONE);

            try (ConfigurableApplicationContext context = app.run(
                    "--INFISICAL_URL=" + fake.url(),
                    "--INFISICAL_CLIENT_ID=client-id",
                    "--INFISICAL_CLIENT_SECRET=client-secret",
                    "--INFISICAL_PROJECT=project-id",
                    "--INFISICAL_ENVIRONMENT=dev")) {

                assertEquals("from-infisical", context.getEnvironment().getProperty("app.password"));
                assertTrue(context.getEnvironment().getPropertySources()
                        .contains(InfisicalEnvironmentPostProcessor.PROPERTY_SOURCE_NAME));
                assertEquals(2, fake.requests().size(), "logged in and listed exactly once");
            }
        }
    }
}
