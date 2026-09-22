package com.example.jwt.domain.module;

/**
 * The module_service couldn't be reached or kept failing: retries exhausted, timeouts, 5xx
 * responses, or the circuit breaker is open.
 */
public class ModuleServiceUnavailableException extends RuntimeException {

  public ModuleServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
