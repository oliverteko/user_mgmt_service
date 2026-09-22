package com.example.jwt.domain.module;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection and resilience settings for the synchronous REST calls to the module_service.
 * Bound from {@code module-service.*} (application.properties / environment).
 */
@ConfigurationProperties("module-service")
public record ModuleServiceProperties(
    @DefaultValue("http://module-service:8080") String baseUrl,
    @DefaultValue("1s") Duration connectTimeout,
    @DefaultValue("2s") Duration readTimeout,
    @DefaultValue Retry retry,
    @DefaultValue CircuitBreaker circuitBreaker) {

  /** Retries transient failures (5xx, I/O errors, timeouts) with exponential backoff. */
  public record Retry(
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("200ms") Duration waitDuration,
      @DefaultValue("2") double backoffMultiplier) {
  }

  /** Stops calling the module_service for a while once too many calls in a row failed. */
  public record CircuitBreaker(
      @DefaultValue("50") float failureRateThreshold,
      @DefaultValue("10") int slidingWindowSize,
      @DefaultValue("5") int minimumNumberOfCalls,
      @DefaultValue("20s") Duration waitDurationInOpenState,
      @DefaultValue("2") int permittedCallsInHalfOpenState) {
  }
}
