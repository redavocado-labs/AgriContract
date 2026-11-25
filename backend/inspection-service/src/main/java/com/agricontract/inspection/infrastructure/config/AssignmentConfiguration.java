package com.agricontract.inspection.infrastructure.config;

import java.security.SecureRandom;
import com.agricontract.calendar.VietnamBusinessDays;
import java.util.random.RandomGenerator;
import java.time.Duration;
import com.agricontract.inspection.application.port.FreeCancellationWindow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AssignmentConfiguration {
    @Bean
    VietnamBusinessDays vietnamBusinessDays(@Value("${VIETNAM_HOLIDAYS}") String holidays) {
        return VietnamBusinessDays.fromConfiguration(holidays);
    }

    @Bean
    RandomGenerator inspectorRandom() { return new SecureRandom(); }

    /** At most 30 calendar days: a generous bound for a cancellation grace period. */
    @Bean
    FreeCancellationWindow freeCancellationWindow(@Value("${inspection.assignment.free-cancel-hours:4}") long hours) {
        if (hours < 1 || hours > 720) throw new IllegalArgumentException("free-cancel-hours must be between 1 and 720");
        return new FreeCancellationWindow(Duration.ofHours(hours));
    }
}
