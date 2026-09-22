package com.example.jwt.domain.module;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous REST client for the module_service, called via its Kubernetes Service.
 *
 * <p>Every call is protected three ways:
 * <ul>
 *   <li><b>Timeout</b> - connect/read timeouts on the underlying HTTP client (see
 *   {@link ModuleServiceConfig}), so a hanging module_service can't block request threads.</li>
 *   <li><b>Retry</b> - transient failures (5xx, I/O errors, timeouts) are retried with
 *   exponential backoff. A 404 is an answer, not a failure, and is never retried.</li>
 *   <li><b>Circuit breaker</b> - once too many calls failed, further calls fail fast for a
 *   while instead of piling up on a service that is down.</li>
 * </ul>
 * Retry wraps the circuit breaker, so every attempt is counted by the breaker, and an open
 * breaker ({@link CallNotPermittedException}) is not retried.
 */
public class ModuleServiceClient {

  private final RestClient restClient;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;

  public ModuleServiceClient(RestClient restClient, CircuitBreaker circuitBreaker, Retry retry) {
    this.restClient = restClient;
    this.circuitBreaker = circuitBreaker;
    this.retry = retry;
  }

  /** Returns the module, or throws {@link ModuleNotFoundException} if it doesn't exist. */
  public ModuleDTO getModule(UUID moduleId) {
    return execute(() -> restClient.get()
        .uri("/api/v1/modules/{moduleId}", moduleId)
        .retrieve()
        .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
          throw new ModuleNotFoundException(moduleId);
        })
        .body(ModuleDTO.class));
  }

  /** All modules known to the module_service. */
  public List<ModuleDTO> getModules() {
    return execute(() -> restClient.get()
        .uri("/api/v1/modules")
        .retrieve()
        .body(new ParameterizedTypeReference<List<ModuleDTO>>() {
        }));
  }

  /** Assigns the module to the user (idempotent on the module_service side). */
  public void assignModuleToUser(UUID userId, UUID moduleId) {
    execute(() -> restClient.put()
        .uri("/api/v1/users/{userId}/modules/{moduleId}", userId, moduleId)
        .retrieve()
        .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
          throw new ModuleNotFoundException(moduleId);
        })
        .toBodilessEntity());
  }

  private <T> T execute(Supplier<T> call) {
    Supplier<T> decorated = Retry.decorateSupplier(retry,
        CircuitBreaker.decorateSupplier(circuitBreaker, call));
    try {
      return decorated.get();
    } catch (ModuleNotFoundException e) {
      throw e;
    } catch (CallNotPermittedException e) {
      throw new ModuleServiceUnavailableException(
          "module_service is unavailable (circuit breaker open)", e);
    } catch (HttpClientErrorException e) {
      throw new ModuleServiceUnavailableException(
          "module_service rejected the request (" + e.getStatusCode() + ")", e);
    } catch (RestClientException e) {
      // 5xx, I/O errors, timeouts - after the retries are used up
      throw new ModuleServiceUnavailableException(
          "module_service is unavailable (" + e.getMessage() + ")", e);
    }
  }

  /** Failures worth retrying and counting against the circuit breaker. */
  static boolean isTransientFailure(Throwable throwable) {
    return throwable instanceof HttpServerErrorException
        || throwable instanceof ResourceAccessException
        // A read timeout that hits while the response body is being read doesn't surface as a
        // ResourceAccessException but as "Error while extracting response" with an
        // IOException ("closed") underneath - still a timeout, so still transient.
        || (throwable instanceof RestClientException && hasIoCause(throwable));
  }

  private static boolean hasIoCause(Throwable throwable) {
    for (Throwable t = throwable.getCause(); t != null; t = t.getCause()) {
      if (t instanceof IOException) {
        return true;
      }
    }
    return false;
  }
}
