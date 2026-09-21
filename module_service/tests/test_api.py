from uuid import uuid4

from fastapi.testclient import TestClient

CLOUD_ARCH_ID = "c02f58f2-3aca-4f1e-8076-bacf6f1999e6"


def test_health_endpoints(client: TestClient) -> None:
    assert client.get("/health/live").status_code == 200
    ready = client.get("/health/ready")
    assert ready.status_code == 200
    assert ready.json() == {"status": "UP"}


def test_seed_modules_are_available(client: TestClient) -> None:
    response = client.get("/api/v1/modules")
    assert response.status_code == 200
    codes = {module["code"] for module in response.json()}
    assert {"CLOUD-ARCH", "DATABASES", "SECURITY", "WEB-DEV"} <= codes


def test_get_unknown_module_returns_404(client: TestClient) -> None:
    response = client.get(f"/api/v1/modules/{uuid4()}")
    assert response.status_code == 404
    assert response.json()["code"] == "MODULE_NOT_FOUND"


def test_assign_module_is_idempotent(client: TestClient) -> None:
    user_id = uuid4()
    url = f"/api/v1/users/{user_id}/modules/{CLOUD_ARCH_ID}"
    assert client.put(url).status_code == 204
    assert client.put(url).status_code == 204


def test_assign_unknown_module_returns_404(client: TestClient) -> None:
    response = client.put(f"/api/v1/users/{uuid4()}/modules/{uuid4()}")
    assert response.status_code == 404
    assert response.json()["code"] == "MODULE_NOT_FOUND"


def test_metrics_expose_request_counters(client: TestClient) -> None:
    client.get("/api/v1/modules")
    body = client.get("/metrics").text
    assert "http_requests_total" in body
    assert "http_request_duration_seconds" in body
    assert 'handler="/api/v1/modules"' in body
    # probe traffic is excluded from the request metrics
    assert 'handler="/health/live"' not in body
