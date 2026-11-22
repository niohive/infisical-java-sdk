package com.niohive.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/** Proves each test task of the JDK matrix really runs on the JDK it claims to. */
class JavaVersionTest {

    @Test
    void runsOnTheJdkTheTestTaskClaims() {
        String expected = System.getProperty("expected.java");
        assumeTrue(expected != null, "only meaningful when run by Gradle");
        assertEquals(expected, System.getProperty("java.specification.version"));
    }
}
