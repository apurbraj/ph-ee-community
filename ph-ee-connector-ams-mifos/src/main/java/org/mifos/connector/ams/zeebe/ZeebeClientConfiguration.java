package org.mifos.connector.ams.zeebe;

import io.zeebe.client.ZeebeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnExpression("${zeebe.enabled:true}")
public class ZeebeClientConfiguration {

    @Value("${zeebe.broker.contactpoint}")
    private String zeebeBrokerContactpoint;

    @Value("${zeebe.client.max-execution-threads}")
    private int zeebeClientMaxThreads;

    @Bean
    public ZeebeClient setup() {
        return ZeebeClient.newClientBuilder()
                .brokerContactPoint(zeebeBrokerContactpoint)
                .usePlaintext()
                .defaultJobPollInterval(Duration.ofMillis(10))
                .defaultJobWorkerMaxJobsActive(512)
//                .numJobWorkerExecutionThreads(zeebeClientMaxThreads)
                .build();
    }
}
