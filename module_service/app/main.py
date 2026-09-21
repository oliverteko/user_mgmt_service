import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app.api import module_router, user_module_router
from app.bootstrap import bootstrap
from app.config import get_settings
from app.database import engine
from app.metrics import setup_metrics

settings = get_settings()
logging.basicConfig(
    level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s %(message)s"
)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    bootstrap(engine)
    yield


app = FastAPI(
    title="Module Service",
    description="Manages modules.",
    version=settings.app_version,
    lifespan=lifespan,
)
app.include_router(module_router)
app.include_router(user_module_router)

setup_metrics(app)


@app.get("/health/live", include_in_schema=False)
def liveness() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready", include_in_schema=False)
def readiness() -> JSONResponse:
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except Exception:
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, content={"status": "DOWN"}
        )
    return JSONResponse(content={"status": "UP"})


@app.exception_handler(HTTPException)
async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail
    if not isinstance(detail, dict) or "code" not in detail:
        detail = {"code": "HTTP_ERROR", "message": str(detail)}
    return JSONResponse(status_code=exc.status_code, content=detail, headers=exc.headers)
