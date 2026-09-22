package com.example.jwt.domain.module;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ModuleServiceProperties.class)
public class ModuleServiceConfig {

  static final String NAME = "moduleService";

  @Bean
  public ModuleServiceClient moduleServiceClient(ModuleServiceProperties properties,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<MeterRegistry> meterRegistry) {
    CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
    RetryRegistry retries = RetryRegistry.ofDefaults();
    CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(NAME,
        circuitBreakerConfig(properties));
    Retry retry = retries.retry(NAME, retryConfig(properties));

    // resilience4j_circuitbreaker_state / _calls and resilience4j_retry_calls on
    // /actuator/prometheus
    meterRegistry.ifAvailable(registry -> {
      TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(registry);
      TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(registry);
    });

    RestClient.Builder builder = RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory(properties));
    // http_client_requests_seconds{client_name="module-service",...} on /actuator/prometheus
    observationRegistry.ifAvailable(builder::observationRegistry);

    return new ModuleServiceClient(builder.build(), circuitBreaker, retry);
  }

  static JdkClientHttpRequestFactory requestFactory(ModuleServiceProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.readTimeout());
    return factory;
  }

  static CircuitBreakerConfig circuitBreakerConfig(ModuleServiceProperties properties) {
    ModuleServiceProperties.CircuitBreaker cb = properties.circuitBreaker();
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(cb.slidingWindowSize())
        .minimumNumberOfCalls(cb.minimumNumberOfCalls())
        .failureRateThreshold(cb.failureRateThreshold())
        .waitDurationInOpenState(cb.waitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsInHalfOpenState())
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        // A 404 ("module doesn't exist") is a valid answer, not a failure of the service.
        .recordException(ModuleServiceClient::isTransientFailure)
        .build();
  }

  static RetryConfig retryConfig(ModuleServiceProperties properties) {
    ModuleServiceProperties.Retry r = properties.retry();
    return RetryConfig.custom()
        .maxAttempts(r.maxAttempts())
        .intervalFunction(
            IntervalFunction.ofExponentialBackoff(r.waitDuration(), r.backoffMultiplier()))
        // Not retried: 404s, other 4xx, and an open circuit breaker.
        .retryOnException(ModuleServiceClient::isTransientFailure)
        .build();
  }
}
