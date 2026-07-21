package com.devicelk;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL 16 container for the integration tests.
 * <p>
 * Started once for the whole JVM rather than once per test class. The container
 * is deliberately <b>not</b> managed by {@code @Testcontainers}/{@code @Container},
 * which would tie its lifecycle to a single class and pay the startup cost again
 * for every one; a static singleton is the documented pattern for sharing it.
 * Ryuk stops it when the JVM exits, so nothing is leaked.
 * <p>
 * A real database rather than an in-memory stand-in because several behaviours
 * under test are enforced by PostgreSQL itself — partial unique indexes, the
 * cart's composite unique constraint, and the transaction-abort semantics the
 * concurrent-create path is written against.
 */
public abstract class AbstractPostgresTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withInitScript("db/init-schemas.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
