package com.devicelk;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Enforces the modular-monolith boundary rules at build time.
 * <p>
 * {@link ApplicationModules#verify()} fails the build when a module reaches
 * into another module's internals (any sub-package such as {@code domain},
 * {@code repository} or {@code service}) or when modules form a dependency
 * cycle. Only types in a module's base package — its facade and published
 * API — may be referenced from other modules.
 */
class ModularityTests {

    static final ApplicationModules modules =
            ApplicationModules.of(DeviceLkInventoryApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    /**
     * Prints the detected module arrangement so boundary regressions are easy
     * to diagnose from the build log.
     */
    @Test
    void printsModuleArrangement() {
        modules.forEach(System.out::println);
    }
}
