package com.watermonitor;

import com.watermonitor.alert.AlertSettingsService;
import com.watermonitor.device.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application against a throwaway Postgres (Testcontainers):
 * verifies Flyway migrations apply cleanly and bootstrap seeding works.
 */
@SpringBootTest
@Testcontainers
class ApplicationStartupTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired DeviceRepository devices;
    @Autowired AlertSettingsService settings;

    @Test
    void migrationsApplyAndBootstrapSeeds() {
        assertThat(devices.count()).isEqualTo(1);          // initial device registered
        assertThat(settings.get().getLowThresholdPsi())    // defaults seeded (20 PSI confirmed)
                .isEqualByComparingTo("20");
    }
}
