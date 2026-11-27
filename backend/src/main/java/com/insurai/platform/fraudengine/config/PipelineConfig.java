package com.insurai.platform.fraudengine.config;

import com.insurai.platform.fraudengine.domain.pipeline.DecisionEngine;
import com.insurai.platform.fraudengine.domain.pipeline.RiskScorer;
import com.insurai.platform.fraudengine.domain.pipeline.RulesEngine;
import com.insurai.platform.fraudengine.domain.pipeline.VelocityChecker;
import com.insurai.platform.fraudengine.domain.ports.AccountLimitPort;
import com.insurai.platform.fraudengine.domain.ports.LocationRiskPort;
import com.insurai.platform.fraudengine.domain.ports.MerchantBlacklistPort;
import com.insurai.platform.fraudengine.domain.ports.VelocityPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free domain pipeline components as Spring beans, injecting
 * the infrastructure adapters that implement each port and the externalized
 * {@link FraudProperties}.
 */
@Configuration
public class PipelineConfig {

    @Bean
    public RulesEngine rulesEngine(MerchantBlacklistPort blacklistPort,
                                   AccountLimitPort accountLimitPort,
                                   LocationRiskPort locationRiskPort) {
        return new RulesEngine(blacklistPort, accountLimitPort, locationRiskPort);
    }

    @Bean
    public VelocityChecker velocityChecker(VelocityPort velocityPort, FraudProperties properties) {
        return new VelocityChecker(velocityPort,
                properties.velocity().windowSeconds(),
                properties.velocity().maxTransactions());
    }

    @Bean
    public RiskScorer riskScorer(FraudProperties properties) {
        return new RiskScorer(properties.weights());
    }

    @Bean
    public DecisionEngine decisionEngine(FraudProperties properties) {
        return new DecisionEngine(properties.threshold());
    }
}
