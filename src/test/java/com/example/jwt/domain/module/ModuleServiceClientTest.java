package com.example.jwt.domain.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Exercises timeout, retry and circuit breaker against a real (local) HTTP server - no Spring
 * context, no mocks of the HTTP layer.
 */
class ModuleServiceClientTest {

  private static final UUID MODULE_ID = UUID.fromString("c02f58f2-3aca-4f1e-8076-bacf6f1999e6");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final String MODULE_JSON = """
      {"id":"c02f58f2-3aca-4f1e-8076-bacf6f1999e6","code":"CLOUD-ARCH",
       "name":"Cloud Architecture","description":"Designing reliable and scalable cloud systems",
       "created_at":"2026-09-16T09:02:09","updated_at":"2026-09-16T09:02:09"}""";

  private HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();
  private volatile IntSupplier statusForRequest = () -> 200;
  private volatile Duration delay = Duration.ZERO;
  private volatile Duration bodyDelay = Duration.ZERO;

  private ModuleServiceProperties properties;
  private CircuitBreaker circuitBreaker;
  private ModuleServiceClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      sleep(delay);
      int status = statusForRequest.getAsInt();
      byte[] body = status == 200 ? MODULE_JSON.getBytes(StandardCharsets.UTF_8)
          : "{\"code\":\"ERROR\",\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      if (status == 204) {
        exchange.sendResponseHeaders(204, -1);
      } else {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().flush();
        sleep(bodyDelay);
        exchange.getResponseBody().write(body);
      }
      exchange.close();
    });
    // concurrent handling, so a deliberately slow response doesn't queue up the retries
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();

    properties = new ModuleServiceProperties(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        new ModuleServiceProperties.Retry(3, Duration.ofMillis(10), 2),
        new ModuleServiceProperties.CircuitBreaker(50, 4, 4, Duration.ofSeconds(30), 1));
    circuitBreaker = CircuitBreaker.of("test", ModuleServiceConfig.circuitBreakerConfig(properties));
    Retry retry = Retry.of("test", ModuleServiceConfig.retryConfig(properties));
    RestClient restClient = RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(ModuleServiceConfig.requestFactory(properties))
        .build();
    client = new ModuleServiceClient(restClient, circuitBreaker, retry);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void returnsModuleWhenAvailable() {
    ModuleDTO module = client.getModule(MODULE_ID);

    assertThat(module.id()).isEqualTo(MODULE_ID);
    assertThat(module.code()).isEqualTo("CLOUD-ARCH");
    assertThat(requests).hasValue(1);
  }

  @Test
  void unknownModuleIsNotRetriedAndDoesNotTripTheBreaker() {
    statusForRequest = () -> 404;

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> client.getModule(MODULE_ID))
          .isInstanceOf(ModuleNotFoundException.class);
    }

    assertThat(requests).hasValue(5);
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void transientServerErrorIsRetried() {
    AtomicInteger calls = new AtomicInteger();
    statusForRequest = () -> calls.incrementAndGet() <= 2 ? 503 : 200;

    ModuleDTO module = client.getModule(MODULE_ID);

    assertThat(module.code()).isEqualTo("CLOUD-ARCH");
    assertThat(requests).hasValue(3);
  }

  @Test
  void slowResponseTimesOutAndFailsAfterRetries() {
    delay = Duration.ofSeconds(3); // read timeout is 1s

    long start = System.nanoTime();
    assertThatThrownBy(() -> client.getModule(MODULE_ID))
        .isInstanceOf(ModuleServiceUnavailableException.class);
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(requests).hasValue(3);
    // 3 attempts x 1s timeout + backoff, far below 3 x 3s
    assertThat(took).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void timeoutWhileReadingTheBodyIsRetriedToo() {
    AtomicInteger calls = new AtomicInteger();
    // first response stalls mid-body past the read timeout, the retry gets a normal one
    statusForRequest = () -> {
      bodyDelay = calls.incrementAndGet() == 1 ? Duration.ofSeconds(3) : Duration.ZERO;
      return 200;
    };

    ModuleDTO module = client.getModule(MODULE_ID);

    assertThat(module.code()).isEqualTo("CLOUD-ARCH");
    assertThat(requests).hasValue(2);
  }

  @Test
  void circuitOpensAfterRepeatedFailuresAndThenFailsFast() {
    statusForRequest = () -> 500;

    // 2 calls x 3 attempts = 6 recorded failures >= minimumNumberOfCalls (4) at 100% -> open
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> client.getModule(MODULE_ID))
          .isInstanceOf(ModuleServiceUnavailableException.class);
    }
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    int requestsBeforeOpenCall = requests.get();

    assertThatThrownBy(() -> client.getModule(MODULE_ID))
        .isInstanceOf(ModuleServiceUnavailableException.class)
        .hasCauseInstanceOf(CallNotPermittedException.class);
    // open breaker: no request reaches the server, and it isn't retried either
    assertThat(requests).hasValue(requestsBeforeOpenCall);
  }

  @Test
  void assignsModule() {
    statusForRequest = () -> 204;

    client.assignModuleToUser(USER_ID, MODULE_ID);

    assertThat(requests).hasValue(1);
  }

  @Test
  void assigningUnknownModuleReportsNotFound() {
    statusForRequest = () -> 404;

    assertThatThrownBy(() -> client.assignModuleToUser(USER_ID, MODULE_ID))
        .isInstanceOf(ModuleNotFoundException.class);
  }
}
