package com.planus.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    private static final DockerImageName PGVECTOR_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres");

    @Bean
    PostgreSQLContainer<?> postgresContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
                .withDatabaseName("planus")
                .withUsername("planus")
                .withPassword("planus");
        container.start();
        return container;
    }

    @Bean
    DynamicPropertyRegistrar dynamicPropertyRegistrar(PostgreSQLContainer<?> container) {
        return registry -> {
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        };
    }
}
