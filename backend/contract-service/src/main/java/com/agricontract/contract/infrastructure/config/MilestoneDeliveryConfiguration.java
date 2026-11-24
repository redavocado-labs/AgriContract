package com.agricontract.contract.infrastructure.config;

import com.agricontract.contract.application.port.MilestoneDeliverySettings;
import com.agricontract.contract.application.port.MilestoneFlagSettings;
import com.agricontract.contract.application.port.MilestoneNotificationSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** contract.milestone.* from application.yml, handed to the application layer as plain values. */
@Configuration
public class MilestoneDeliveryConfiguration {

    @Bean
    MilestoneDeliverySettings milestoneDeliverySettings(
            @Value("${contract.milestone.weigh-correction-window:PT1H}") Duration weighCorrectionWindow) {
        return new MilestoneDeliverySettings(weighCorrectionWindow);
    }

    @Bean
    MilestoneFlagSettings milestoneFlagSettings(
            @Value("${contract.milestone.seller-response-window-days:2}") int sellerResponseWindowDays) {
        return new MilestoneFlagSettings(sellerResponseWindowDays);
    }

    @Bean
    MilestoneNotificationSettings milestoneNotificationSettings(
            @Value("${contract.milestone.buyer-receive-window-days:2}") int buyerReceiveWindowDays,
            @Value("${contract.milestone.funding-reminder-business-days:2}") int fundingReminderBusinessDays,
            @Value("${contract.notification.operator-email:}") String operatorEmail) {
        return new MilestoneNotificationSettings(buyerReceiveWindowDays, fundingReminderBusinessDays, operatorEmail);
    }
}
