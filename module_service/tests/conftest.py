import os
import tempfile
from collections.abc import Iterator
from pathlib import Path

import pytest

# The engine is created at import time from DATABASE_URL, so point it at a
# throwaway SQLite file before anything from `app` is imported.
_db_file = Path(tempfile.mkdtemp()) / "test.db"
os.environ["DATABASE_URL"] = f"sqlite:///{_db_file.as_posix()}"

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402


@pytest.fixture()
def client() -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client
