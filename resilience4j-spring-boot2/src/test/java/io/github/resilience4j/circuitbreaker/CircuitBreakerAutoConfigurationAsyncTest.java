/*
 * Copyright 2017 Robert Winkler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.circuitbreaker;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.github.resilience4j.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import io.github.resilience4j.common.circuitbreaker.monitoring.endpoint.CircuitBreakerEndpointResponse;
import io.github.resilience4j.common.circuitbreaker.monitoring.endpoint.CircuitBreakerEventDTO;
import io.github.resilience4j.common.circuitbreaker.monitoring.endpoint.CircuitBreakerEventsEndpointResponse;
import io.github.resilience4j.service.test.DummyService;
import io.github.resilience4j.service.test.TestApplication;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = TestApplication.class)
public class CircuitBreakerAutoConfigurationAsyncTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8090);
    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    CircuitBreakerProperties circuitBreakerProperties;
    @Autowired
    DummyService dummyService;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * The test verifies that a CircuitBreaker instance is created and configured properly when the
     * DummyService is invoked and that the CircuitBreaker records successful and failed calls.
     */
    @Test
    public void testCircuitBreakerAutoConfigurationAsync()
        throws IOException, ExecutionException, InterruptedException {
        assertThat(circuitBreakerRegistry).isNotNull();
        assertThat(circuitBreakerProperties).isNotNull();
        List<CircuitBreakerEventDTO> circuitBreakerEventsBefore = getCircuitBreakersEvents();
        List<CircuitBreakerEventDTO> circuitBreakerEventsForABefore = getCircuitBreakerEvents("backendA");

        try {
            dummyService.doSomethingAsync(true);
        } catch (IOException ex) {
            // Do nothing. The IOException is recorded by the CircuitBreaker as part of the setRecordFailurePredicate as a failure.
        }
        // The invocation is recorded by the CircuitBreaker as a success.
        final CompletableFuture<String> stringCompletionStage = dummyService
            .doSomethingAsync(false);
        assertThat(stringCompletionStage.get()).isEqualTo("Test result");

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(DummyService.BACKEND);
        assertThat(circuitBreaker).isNotNull();

        // expect circuitbreakers actuator endpoint contains both circuit breakers
        ResponseEntity<CircuitBreakerEndpointResponse> circuitBreakerList = restTemplate
            .getForEntity("/actuator/circuitbreakers", CircuitBreakerEndpointResponse.class);
        assertThat(circuitBreakerList.getBody().getCircuitBreakers()).hasSize(6)
            .containsExactly("backendA", "backendB", "backendC", "backendSharedA", "backendSharedB",
                "dummyFeignClient");

        // expect circuitbreaker-event actuator endpoint recorded both events
        assertThat(getCircuitBreakersEvents())
            .hasSize(circuitBreakerEventsBefore.size() + 2);
        assertThat(getCircuitBreakerEvents("backendA"))
            .hasSize(circuitBreakerEventsForABefore.size() + 2);

        // expect no health indicator for backendB, as it is disabled via properties
        ResponseEntity<CompositeHealthResponse> healthResponse = restTemplate
            .getForEntity("/actuator/health/circuitBreakers", CompositeHealthResponse.class);
        assertThat(healthResponse.getBody().getDetails()).isNotNull();
        assertThat(healthResponse.getBody().getDetails().get("backendA")).isNotNull();
        assertThat(healthResponse.getBody().getDetails().get("backendB")).isNull();
        assertThat(healthResponse.getBody().getDetails().get("backendSharedA")).isNotNull();
        assertThat(healthResponse.getBody().getDetails().get("backendSharedB")).isNotNull();
    }

    private List<CircuitBreakerEventDTO> getCircuitBreakersEvents() {
        return getEventsFrom("/actuator/circuitbreakerevents");
    }

    private List<CircuitBreakerEventDTO> getCircuitBreakerEvents(String name) {
        return getEventsFrom("/actuator/circuitbreakerevents/" + name);
    }

    private List<CircuitBreakerEventDTO> getEventsFrom(String path) {
        return restTemplate.getForEntity(path, CircuitBreakerEventsEndpointResponse.class)
            .getBody().getCircuitBreakerEvents();
    }
}