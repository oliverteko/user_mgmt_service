"""Prometheus HTTP metrics, exposed on /metrics and scraped via a ServiceMonitor.

- http_requests_total{method,handler,status}: request rate and error rate
  (status is grouped as 2xx/4xx/5xx to keep label cardinality low)
- http_request_duration_seconds{method,handler}: response time (histogram)

`handler` is the route template (/api/v1/modules/{module_id}), not the raw
path, so every module id doesn't become its own time series. Probe and
scrape requests aren't counted.
"""

import time

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

REQUESTS = Counter(
    "http_requests_total",
    "Total HTTP requests.",
    ["method", "handler", "status"],
)
LATENCY = Histogram(
    "http_request_duration_seconds",
    "HTTP request duration in seconds.",
    ["method", "handler"],
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10),
)

_EXCLUDED_PREFIXES = ("/metrics", "/health/")


def setup_metrics(app: FastAPI) -> None:
    @app.middleware("http")
    async def record_metrics(request: Request, call_next):  # type: ignore[no-untyped-def]
        if request.url.path.startswith(_EXCLUDED_PREFIXES):
            return await call_next(request)

        start = time.perf_counter()
        status_group = "5xx"
        try:
            response = await call_next(request)
            status_group = f"{response.status_code // 100}xx"
            return response
        finally:
            route = request.scope.get("route")
            handler = getattr(route, "path", "unmatched")
            REQUESTS.labels(request.method, handler, status_group).inc()
            LATENCY.labels(request.method, handler).observe(time.perf_counter() - start)

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
