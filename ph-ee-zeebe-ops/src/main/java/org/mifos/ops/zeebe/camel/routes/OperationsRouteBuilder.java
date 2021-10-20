package org.mifos.ops.zeebe.camel.routes;

import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.json.JSONArray;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mifos.ops.zeebe.zeebe.ZeebeMessages.OPERATOR_MANUAL_RECOVERY;
import static org.mifos.ops.zeebe.zeebe.ZeebeVariables.*;

@Component
public class OperationsRouteBuilder extends ErrorHandlerRouteBuilder {

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private Logger logger;

    @Autowired
    private RestHighLevelClient esClient;

    @Override
    public void configure() {

        /**
         * Get the process variables by process instance key
         *
         * demo url: /channel/process/variable/2251799813783649
         * here [2251799813783649] is the value for path parameter [PROCESS_INSTANCE_KEY]
         *
         * example response: {
         * "isRtpRequest":"false",
         * "initiatorFspId":"\"ibank-usa\"",
         * "originDate":"1633441154238"
         * }
         */
        from(String.format("rest:get:/channel/process/variable/{%s}", PROCESS_INSTANCE_KEY))
                .id("get-process-variable")
                .log(LoggingLevel.INFO, "## Fetch process variable")
                .process(exchange -> {

                    Long processId = exchange.getIn().getHeader(PROCESS_INSTANCE_KEY, Long.class);

                    TermsAggregationBuilder valueAgg = AggregationBuilders.terms("value")
                            .field("value.value")
                            .size(100);
                    TermsAggregationBuilder nameAgg = AggregationBuilders.terms("key")
                            .field("value.name")
                            .size(100)
                            .subAggregation(valueAgg);

                    SearchSourceBuilder builder = new SearchSourceBuilder().aggregation(nameAgg)
                            .query(QueryBuilders.matchQuery("value.processInstanceKey", processId));

                    SearchRequest searchRequest =
                            new SearchRequest().indices("zeebe-*").source(builder);

                    SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);

                    JSONObject responseToBeReturned = new JSONObject();

                    String r = response.toString();
                    JSONObject res = new JSONObject(r);
                    JSONArray keyBucket = res.getJSONObject("aggregations").getJSONObject("sterms#key")
                            .getJSONArray("buckets");

                    keyBucket.forEach(elm -> {
                        JSONObject bucket = (JSONObject) elm;
                        String key = bucket.getString("key");
                        Object value = ((JSONObject)bucket.getJSONObject("sterms#value").getJSONArray("buckets")
                                .get(0)).get("key");

                        responseToBeReturned.put(key, value);
                    });

                    exchange.getMessage().setBody(responseToBeReturned.toString());

                });
        /**
         * Get the process current state and variables by process instance id
         *
         * demo url: /channel/process/variable/2251799813686414
         * here [2251799813686414] is the value for path parameter [PROCESS_INSTANCE_ID]
         *
         * example response: {
         *   "currentState": "",
         *   "processVariables": {}
         * }
         */
        from(String.format("rest:get:/channel/process/{%s}", PROCESS_DEFINITION_KEY))
                .id("get-process-variable")
                .log(LoggingLevel.INFO, "## Fetch process variable and current state")
                .process(exchange -> {

                    Long processInstanceKey = exchange.getIn().getHeader(PROCESS_DEFINITION_KEY, Long.class);

                    try {
                        JSONObject processVariables = getProcessVariable(processInstanceKey);
                        String state = getCurrentState(processInstanceKey);
                        JSONObject responseToBeReturned = new JSONObject();
                        responseToBeReturned.put("currentState", state);
                        responseToBeReturned.put("processVariables", processVariables);
                        exchange.getMessage().setBody(responseToBeReturned.toString());
                    } catch (Exception e) {
                        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
                        exchange.getMessage().setBody(e.toString());
                    }
                });

        /**
         * Starts a workflow with the set of variables passed as body parameters
         *
         * method: [POST]
         * request body: {
         *     "var1": "val1",
         *     "var2": "val2"
         * }
         *
         * response body: Null
         *
         * demo url: /channel/workflow/international_remittance_payer_process-ibank-usa
         * Here [international_remittance_payer_process-ibank-usa] is the value of [BPMN_PROCESS_ID] path variable
         *
         */
        from(String.format("rest:POST:/channel/workflow/{%s}", BPMN_PROCESS_ID))
                .id("workflow-start")
                .log(LoggingLevel.INFO, "## Starting new workflow")
                .process(e -> {

                    JSONObject variables = new JSONObject(e.getIn().getBody(String.class));

                    e.getMessage().setBody(e.getIn().getHeader(BPMN_PROCESS_ID, String.class));


                    zeebeClient.newCreateInstanceCommand()
                            .bpmnProcessId(e.getIn().getHeader(BPMN_PROCESS_ID, String.class))
                            .latestVersion()
                            .variables(variables)
                            .send()
                            .join();

                });

        /**
         * Bulk cancellation of active process by processId
         *
         * method: [PUT]
         * request body: {
         *     processId: [123, 456, 789]
         * }
         *
         * response body: {
         *     success: [], # list of processId which was successfully cancelled
         *     failed: [] # list of processId whose cancellation wasn't successful
         *     cancellationSuccessful: int # total number of process which was successfully cancelled
         *     cancellationFailed: int # total number of process whose cancellation wasn't successful
         *
         * }
         */
        from("rest:PUT:/channel/workflow")
                .id("bulk-cancellation")
                .log(LoggingLevel.INFO, "## bulk cancellation by process id")
                .process(exchange -> {

                    JSONObject object = new JSONObject(exchange.getIn().getBody(String.class));
                    JSONArray processIds = object.getJSONArray("processId");

                    JSONArray success = new JSONArray();
                    JSONArray failed = new JSONArray();

                    AtomicInteger successfullyCancelled = new AtomicInteger();
                    AtomicInteger cancellationFailed = new AtomicInteger();


                    processIds.forEach(elm -> {
                        long processId = Long.parseLong(elm.toString());

                        try {
                            zeebeClient.newCancelInstanceCommand(processId).send().join();
                            success.put(processId);
                            successfullyCancelled.getAndIncrement();
                        }catch (Exception e) {
                            failed.put(processId);
                            cancellationFailed.getAndIncrement();
                            logger.error("Cancellation of process id " + processId + " failed\n" + e.getMessage());
                        }

                    });

                    JSONObject response = new JSONObject();
                    response.put("success", success);
                    response.put("failed", failed);
                    response.put("cancellationSuccessful", successfullyCancelled.get());
                    response.put("cancellationFailed", cancellationFailed.get());

                    exchange.getMessage().setBody(response.toString());
                });


        from("rest:get:/es/health")
                .id("es-test")
                .log(LoggingLevel.INFO, "## Testing es connection")
                .process(exchange -> {
                    JSONObject jsonResponse = new JSONObject();
                    try {
                        GetIndexRequest request = new GetIndexRequest("*");
                        esClient.indices().get(request, RequestOptions.DEFAULT);
                        jsonResponse.put("status", "UP");
                    } catch (Exception e) {
                        jsonResponse.put("status", "down");
                        jsonResponse.put("reason", e.getMessage());
                    }

                    exchange.getMessage().setBody(jsonResponse.toString());
                });

        /**
         * Get the process definition key and name
         *
         * sample response body: {
         *    "bulk_processor-ibank-usa":[
         *       2251799813685998,
         *       2251799814125425
         *    ],
         *    "international_remittance_payee_process-ibank-india":[
         *       2251799813686276,
         *       2251799814069864
         *    ],
         *    "international_remittance_payer_process-ibank-usa":[
         *       2251799813686414,
         *       2251799814069794
         *    ],
         *    "international_remittance_payer_process-ibank-india":[
         *       2251799813686138,
         *       2251799814070206
         *    ],
         *    "international_remittance_payee_process-ibank-usa":[
         *       2251799813686068,
         *       2251799814070344
         *    ]
         * }
         */
        from("rest:get:/channel/process")
                .id("get-process-definition-key-name")
                .log(LoggingLevel.INFO, "## get process definition key and name")
                .process(exchange -> {
                    TermsAggregationBuilder definitionKeyAggregation = AggregationBuilders.terms("defKey")
                            .field("value.processDefinitionKey")
                            .size(1005);
                    TermsAggregationBuilder definitionNameAggregation = AggregationBuilders.terms("processId")
                            .field("value.bpmnProcessId")
                            .size(5)
                            .subAggregation(definitionKeyAggregation);

                    SearchSourceBuilder builder = new SearchSourceBuilder().aggregation(definitionNameAggregation);

                    SearchRequest searchRequest =
                            new SearchRequest().indices("zeebe-*").source(builder);
                    SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);

                    JSONObject responseToBeReturned =  new JSONObject();

                    String r = response.toString();
                    JSONObject res = new JSONObject(r);
                    JSONArray buckets = res.getJSONObject("aggregations")
                            .getJSONObject("sterms#processId").getJSONArray("buckets");

                    buckets.forEach(element -> {
                        String processId = ((JSONObject) element).getString("key");
                        JSONArray processDefinitionsKeys = new JSONArray();

                        // loop over each internal aggregation to get the processDefinitionKey
                        ((JSONObject) element).getJSONObject("lterms#defKey").getJSONArray("buckets")
                        .forEach(elm -> processDefinitionsKeys.put(((JSONObject) elm).getLong("key")));

                        responseToBeReturned.put(processId, processDefinitionsKeys);
                    });

                    exchange.getMessage().setBody(responseToBeReturned.toString());
                });

        from("rest:POST:/channel/transaction/{" + TRANSACTION_ID + "}/resolve")
                .id("transaction-resolve")
                .log(LoggingLevel.INFO, "## operator transaction resolve")
                .process(e -> {
                    Map<String, Object> variables = new HashMap<>();
                    JSONObject request = new JSONObject(e.getIn().getBody(String.class));
                    request.keys().forEachRemaining(k -> variables.put(k, request.get(k)));

                    zeebeClient.newPublishMessageCommand()
                            .messageName(OPERATOR_MANUAL_RECOVERY)
                            .correlationKey(e.getIn().getHeader(TRANSACTION_ID, String.class))
                            .timeToLive(Duration.ofMillis(30000))
                            .variables(variables)
                            .send()
                            .join();
                })
                .setBody(constant(null));

        from("rest:POST:/channel/job/resolve")
                .id("job-resolve")
                .log(LoggingLevel.INFO, "## operator job resolve")
                .process(e -> {
                    JSONObject request = new JSONObject(e.getIn().getBody(String.class));
                    JSONObject incident = request.getJSONObject("incident");
                    Map<String, Object> newVariables = new HashMap<>();
                    JSONObject requestedVariables = request.getJSONObject("variables");
                    requestedVariables.keys().forEachRemaining(k -> newVariables.put(k, requestedVariables.get(k)));

                    zeebeClient.newSetVariablesCommand(incident.getLong("elementInstanceKey"))
                            .variables(newVariables)
                            .send()
                            .join();

                    zeebeClient.newUpdateRetriesCommand(incident.getLong("jobKey"))
                            .retries(incident.getInt("newRetries"))
                            .send()
                            .join();

                    zeebeClient.newResolveIncidentCommand(incident.getLong("key"))
                            .send()
                            .join();
                })
                .setBody(constant(null));

        from("rest:POST:/channel/workflow/resolve")
                .id("workflow-resolve")
                .log(LoggingLevel.INFO, "## operator workflow resolve")
                .process(e -> {
                    JSONObject request = new JSONObject(e.getIn().getBody(String.class));
                    JSONObject incident = request.getJSONObject("incident");
                    Map<String, Object> newVariables = new HashMap<>();
                    JSONObject requestedVariables = request.getJSONObject("variables");
                    requestedVariables.keys().forEachRemaining(k -> newVariables.put(k, requestedVariables.get(k)));

                    zeebeClient.newSetVariablesCommand(incident.getLong("elementInstanceKey"))
                            .variables(newVariables)
                            .send()
                            .join();

                    zeebeClient.newResolveIncidentCommand(incident.getLong("key"))
                            .send()
                            .join();
                })
                .setBody(constant(null));

        from("rest:POST:/channel/workflow/{workflowInstanceKey}/cancel")
                .id("workflow-cancel")
                .log(LoggingLevel.INFO, "## operator workflow cancel ${header.workflowInstanceKey}")
                .process(e -> zeebeClient.newCancelInstanceCommand(Long.parseLong(e.getIn().getHeader("workflowInstanceKey", String.class)))
                        .send()
                        .join())
                .setBody(constant(null));

    }

    private JSONObject getProcessVariable(Long processInstanceKey) throws IOException {
        TermsAggregationBuilder valueAgg = AggregationBuilders.terms("value")
                .field("value.value")
                .size(100);
        TermsAggregationBuilder nameAgg = AggregationBuilders.terms("key")
                .field("value.name")
                .size(100)
                .subAggregation(valueAgg);

        SearchSourceBuilder builder = new SearchSourceBuilder().aggregation(nameAgg)
                .query(QueryBuilders.matchQuery("value.processInstanceKey", processInstanceKey));

        SearchRequest searchRequest =
                new SearchRequest().indices("zeebe-*").source(builder);

        SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);

        JSONObject responseToBeReturned = new JSONObject();

        String r = response.toString();
        JSONObject res = new JSONObject(r);
        JSONArray keyBucket = res.getJSONObject("aggregations").getJSONObject("sterms#key")
                .getJSONArray("buckets");

        keyBucket.forEach(elm -> {
            JSONObject bucket = (JSONObject) elm;
            String key = bucket.getString("key");
            Object value = ((JSONObject)bucket.getJSONObject("sterms#value").getJSONArray("buckets")
                    .get(0)).get("key");

            responseToBeReturned.put(key, value);
        });

        return responseToBeReturned;
    }

    private String getCurrentState(Long processInstanceKey) throws Exception {
        TermsAggregationBuilder definitionNameAggregation = AggregationBuilders.terms("worker")
                .field("value.worker")
                .size(5);

        BoolQueryBuilder query = QueryBuilders.boolQuery()
                .filter(QueryBuilders.boolQuery()
                        .should(getMatchPhraseQueryBuilder("intent", "ELEMENT_ACTIVATED"))
                        .should(getMatchPhraseQueryBuilder("intent", "ELEMENT_ACTIVATING"))
                        .minimumShouldMatch(1))
                        .mustNot(getMatchPhraseQueryBuilder("intent", "ELEMENT_COMPLETED"))
                .filter(QueryBuilders.matchPhraseQuery("value.processDefinitionKey", processInstanceKey));

        SearchSourceBuilder builder = new SearchSourceBuilder().aggregation(definitionNameAggregation)
                .query(query);

        SearchRequest searchRequest =
                new SearchRequest().indices("zeebe-*").source(builder);

        SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);

        String r = response.toString();
        JSONObject res = new JSONObject(r);
        JSONArray keyBucket = res.getJSONObject("aggregations").getJSONObject("sterms#worker")
                .getJSONArray("buckets");

        if(keyBucket.isEmpty()) {
            throw new Exception("Unable to fetch current state");
        }

        return ((JSONObject) keyBucket.get(0)).getString("key");
    }

    private QueryBuilder getMatchPhraseQueryBuilder(String key, String valueToMatch) {
        return QueryBuilders.matchPhraseQuery(key, valueToMatch);
    }
}
