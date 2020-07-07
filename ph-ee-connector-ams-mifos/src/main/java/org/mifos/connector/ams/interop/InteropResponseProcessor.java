package org.mifos.connector.ams.interop;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.ams.camel.config.CamelProperties.CONTINUE_PROCESSING;
import static org.mifos.connector.ams.camel.config.CamelProperties.ZEEBE_JOB_KEY;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.ACCOUNT_CURRENCY;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.INTEROP_REGISTRATION_FAILED;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.PARTY_ID;
import static org.mifos.connector.ams.zeebe.ZeebeVariables.PARTY_ID_TYPE;
import static org.mifos.connector.common.camel.ErrorHandlerRouteBuilder.createError;
import static org.mifos.connector.common.mojaloop.type.ErrorCode.INTERNAL_SERVER_ERROR;

@Component
@ConditionalOnExpression("${ams.local.enabled}")
public class InteropResponseProcessor implements Processor {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${ams.local.version}")
    private String amsVersion;

    @Autowired
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) {
        Integer responseCode = exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        String partyIdType = exchange.getProperty(PARTY_ID_TYPE, String.class);
        String partyId = exchange.getProperty(PARTY_ID, String.class);

        Map<String, Object> variables = new HashMap<>();
        variables.put(INTEROP_REGISTRATION_FAILED, false);
        boolean isRequestFailed = false;
        if (responseCode > 202) {
            isRequestFailed = true;
            String errorMsg = String.format("Invalid responseCode %s for interop-identifier action, partyIdType: %s partyId: %s Message: %s",
                    responseCode,
                    partyIdType,
                    partyId,
                    exchange.getIn().getBody(String.class));

            logger.error(errorMsg);
            variables.put(ERROR_INFORMATION, createError(String.valueOf(INTERNAL_SERVER_ERROR.getCode()), errorMsg).toString());
            variables.put(INTEROP_REGISTRATION_FAILED, true);
        }

        Boolean continueProcessing = exchange.getProperty(CONTINUE_PROCESSING, Boolean.class);
        if (continueProcessing == null || !continueProcessing || isRequestFailed) {
            variables.put(ACCOUNT_CURRENCY, exchange.getProperty(ACCOUNT_CURRENCY, String.class));
            zeebeClient.newCompleteCommand(exchange.getProperty(ZEEBE_JOB_KEY, Long.class))
                    .variables(variables)
                    .send()
                    .join();
            if (isRequestFailed) {
                exchange.setRouteStop(true);
            }
        }
    }
}
