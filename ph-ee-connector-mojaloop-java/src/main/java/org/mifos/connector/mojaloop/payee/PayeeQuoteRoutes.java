package org.mifos.connector.mojaloop.payee;

import org.mifos.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.common.mojaloop.dto.MoneyData;
import org.mifos.common.mojaloop.dto.QuoteSwitchRequestDTO;
import org.mifos.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.common.mojaloop.ilp.Ilp;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;

import org.mifos.connector.mojaloop.interop.SwitchOutRouteBuilder;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Component
public class PayeeQuoteRoutes extends ErrorHandlerRouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${switch.quote-service}")
    private String switchQuoteService;

    @Value("${bpmn.flows.quote}")
    private String quoteFlow;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    public PayeeQuoteRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:POST:/switch/quotes")
                .log(LoggingLevel.WARN, "######## SWITCH -> PAYEE - forward quote request - STEP 2")
                .setProperty("savedBody", bodyAs(String.class))
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(exchange -> {
                            QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);

                            zeebeProcessStarter.startZeebeWorkflow(quoteFlow, exchange.getProperty("savedBody", String.class), variables -> {
                                variables.put("qid", request.getQuoteId());
                                variables.put("fspiop-source", request.getPayee().getPartyIdInfo().getFspId());
                                variables.put("fspiop-destination", request.getPayer().getPartyIdInfo().getFspId());
                                variables.put("transactionId", request.getTransactionId());

                                ZeebeProcessStarter.camelHeadersToZeebeVariables(exchange, variables,
                                        "Date",
                                        "traceparent"
                                );
                            });
                        }
                );

        from("direct:send-quote-to-switch")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(exchange -> {
                    QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);
                    Ilp ilp = ilpBuilder.build(request.getTransactionId(),
                            request.getQuoteId(),
                            request.getAmount().getAmountDecimal(),
                            request.getAmount().getCurrency(),
                            request.getPayer(),
                            request.getPayee(),
                            request.getAmount().getAmountDecimal());

                    QuoteSwitchResponseDTO response = new QuoteSwitchResponseDTO(
                            request.getAmount(),
                            request.getAmount(), // calculated from: amount - fee - comission
                            new MoneyData("0", "USD"), // hardcoded free fee
                            new MoneyData("0", "USD"), // hardcoded free comission
                            LocalDateTime.now().plusHours(1),
                            null,
                            ilp.getPacket(),
                            ilp.getCondition(),
                            null
                    );

                    exchange.getIn().setBody(response);

                    Map<String, Object> headers = new HashMap<>();
                    headers.put("qid", request.getQuoteId());
                    headers.put("Content-Type", SwitchOutRouteBuilder.QUOTES_CONTENT_TYPE_HEADER);
                    headers.put("fspiop-source", request.getPayee().getPartyIdInfo().getFspId());
                    headers.put("fspiop-destination", request.getPayer().getPartyIdInfo().getFspId());
                    headers.put("Date", exchange.getIn().getHeader("Date"));
                    headers.put("traceparent", exchange.getIn().getHeader("traceparent"));
                    Object tracestate = exchange.getIn().getHeader("tracestate");
                    if (tracestate != null) {
                        headers.put("tracestate", tracestate);
                    }
                    headers.put("Host", switchQuoteService);
                    exchange.getIn().removeHeaders("*");
                    exchange.getIn().setHeaders(headers);
                })
                .process(pojoToString)
                .toD("rest:PUT:/quotes/${header.qid}?host={{switch.host}}");
    }
}
