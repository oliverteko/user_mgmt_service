Spring 4.1 compliant app that demonstrates the authentication and authorization of a user via JWT

## Module assignment (module_service)

Modules and user-module assignments are owned by the Python [`module_service`](module_service/README.md) and its own DigitalOcean Managed MySQL. This service never touches that database - it calls the module_service synchronously over REST via its Kubernetes Service (`MODULE_SERVICE_URL`, default `http://module-service:8080`).

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/modules` | Modules that can be assigned |
| `PUT` | `/users/{userId}/modules/{moduleId}` | Assign a module to a user (idempotent). Allowed for the user themself or with the `USER_MODIFY` authority. |

`PUT` first checks via the module_service API that the module exists, then assigns it:

| Status | When |
| --- | --- |
| `200` | Assigned (or already was) - body: user id + module |
| `400` | Malformed id |
| `401` / `403` | Not logged in / not allowed for this user |
| `404` | User or module doesn't exist |
| `503` | module_service unavailable (timeouts, retries exhausted, circuit breaker open) |

Resilience (`ModuleServiceClient`, settings in `ModuleServiceProperties` / `module-service.*`): connect timeout 1s, read timeout 2s; up to 3 attempts with exponential backoff for 5xx, I/O errors and timeouts (never for 404/4xx); circuit breaker opens at 50 % failures over the last 10 calls (min. 5) and fails fast for 20s. Metrics on `/actuator/prometheus`: `http_client_requests_seconds{client_name="module-service"}`, `resilience4j_circuitbreaker_state`, `resilience4j_retry_calls_total`.

The frontend proxies both routes (`/api/modules`, `/api/users/{userId}/modules/{moduleId}`) with the JWT from its cookie, like the other API routes.
