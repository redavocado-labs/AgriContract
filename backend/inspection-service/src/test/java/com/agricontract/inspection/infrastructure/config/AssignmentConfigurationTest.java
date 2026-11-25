package com.agricontract.inspection.infrastructure.config;

import com.agricontract.calendar.VietnamBusinessDays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class AssignmentConfigurationTest {
    @Test void freeCancellationWindowDefaultsToFourHours() {
        new ApplicationContextRunner().withUserConfiguration(AssignmentConfiguration.class)
                .withPropertyValues("VIETNAM_HOLIDAYS=2026-01-01")
                .run(context -> assertThat(context.getBean(com.agricontract.inspection.application.port.FreeCancellationWindow.class).duration()).isEqualTo(java.time.Duration.ofHours(4)));
    }

    @Test void customFreeCancellationWindowIsBound() {
        new ApplicationContextRunner().withUserConfiguration(AssignmentConfiguration.class)
                .withPropertyValues("VIETNAM_HOLIDAYS=2026-01-01", "inspection.assignment.free-cancel-hours=12")
                .run(context -> assertThat(context.getBean(com.agricontract.inspection.application.port.FreeCancellationWindow.class).duration()).isEqualTo(java.time.Duration.ofHours(12)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "721", "invalid", "9223372036854775807", "9223372036854775808"})
    void invalidFreeCancellationWindowFailsStartup(String value) {
        new ApplicationContextRunner().withUserConfiguration(AssignmentConfiguration.class)
                .withPropertyValues("VIETNAM_HOLIDAYS=2026-01-01", "inspection.assignment.free-cancel-hours=" + value)
                .run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ",,,", " , , ", "not-a-date", "2026-02-30"})
    void invalidCalendarCannotSilentlyBecomeWeekendOnly(String value) {
        new ApplicationContextRunner().withUserConfiguration(AssignmentConfiguration.class)
                .withBean(PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
                .withPropertyValues("VIETNAM_HOLIDAYS=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void missingConfigurationFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(AssignmentConfiguration.class)
                .withBean(PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void validExternalConfigurationIsUsed() {
        new ApplicationContextRunner().withUserConfiguration(AssignmentConfiguration.class)
                .withBean(PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
                .withPropertyValues("VIETNAM_HOLIDAYS=2026-10-26, 2027-01-01")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var calendar = context.getBean(VietnamBusinessDays.class);
                    assertThat(calendar.deadline(Instant.parse("2026-10-23T03:00:00Z"), 1))
                            .isEqualTo(Instant.parse("2026-10-27T16:59:59.999Z"));
                });
    }
}
