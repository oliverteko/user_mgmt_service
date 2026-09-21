# =============================================================================
# Stage 1: install dependencies from uv.lock into a virtualenv
# =============================================================================
FROM python:3.12-slim AS build

COPY --from=ghcr.io/astral-sh/uv:0.12 /uv /usr/local/bin/uv

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

# Dependencies first (cached layer), then the app itself
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY app app
RUN uv sync --frozen --no-dev

# =============================================================================
# Stage 2: runtime
# =============================================================================
FROM python:3.12-slim AS runtime

# Numeric UID/GID so Kubernetes can verify runAsNonRoot (Kyverno policy
# require-run-as-nonroot) - a named USER can't be checked by the kubelet.
RUN groupadd --system --gid 10001 app && \
    useradd --system --uid 10001 --gid 10001 --no-create-home app

WORKDIR /app
COPY --from=build --chown=10001:10001 /app /app

ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1

USER 10001:10001

EXPOSE 8080

# One uvicorn process: the service scales vertically via its CPU/memory
# limits (see the Helm chart), sync endpoints run in its thread pool.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers", "--no-access-log"]
