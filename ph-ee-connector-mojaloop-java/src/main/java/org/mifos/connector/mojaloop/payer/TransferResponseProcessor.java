package org.mifos.connector.mojaloop.payer;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CACHED_TRANSACTION_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TRANSFER_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TRANSFER_STATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.TRANSFER_RESPONSE;


@Component
public class TransferResponseProcessor implements Processor {

    @Autowired
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> variables = new HashMap<>();
        Object isPayeeTransferFailed = exchange.getProperty(TRANSFER_FAILED);
        if (isPayeeTransferFailed != null && (boolean)isPayeeTransferFailed) {
            variables.put(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
            variables.put(TRANSFER_FAILED, true);
        } else {
            variables.put(TRANSFER_STATE, exchange.getIn().getBody(TransferSwitchResponseDTO.class).getTransferState().name());
        }

        zeebeClient.newPublishMessageCommand()
                .messageName(TRANSFER_RESPONSE)
                .correlationKey(exchange.getProperty(CACHED_TRANSACTION_ID, String.class))
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send();
    }
}
