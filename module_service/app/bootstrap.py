"""Idempotent schema setup and seed data, run once at application startup.

Replaces running schema.sql by hand: the tables are created from the ORM
models (same columns, keys and foreign key as schema.sql) and the modules
from schema.sql are inserted if they don't exist yet. Several replicas can
start at the same time - "already exists" races are ignored.
"""

import logging

from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError, OperationalError, ProgrammingError
from sqlalchemy.orm import Session

from app import models  # noqa: F401 - registers the tables on Base.metadata
from app.database import Base
from app.models import Module

logger = logging.getLogger(__name__)

SEED_MODULES = [
    {
        "id": "c02f58f2-3aca-4f1e-8076-bacf6f1999e6",
        "code": "CLOUD-ARCH",
        "name": "Cloud Architecture",
        "description": "Designing reliable and scalable cloud systems",
    },
    {
        "id": "6d5889ee-f4c7-44d7-a887-da92d2a51ac4",
        "code": "DATABASES",
        "name": "Database Systems",
        "description": "Relational data modeling and SQL fundamentals",
    },
    {
        "id": "674ca4e0-6334-4b12-aa83-d97895049b8a",
        "code": "SECURITY",
        "name": "Application Security",
        "description": "Secure software design and common vulnerabilities",
    },
    {
        "id": "4b9ff45a-d90f-42b0-8b72-20f0b92b6027",
        "code": "WEB-DEV",
        "name": "Web Development",
        "description": "Building modern web applications and APIs",
    },
]


def init_schema(engine: Engine) -> None:
    try:
        Base.metadata.create_all(engine)
    except (OperationalError, ProgrammingError) as exc:
        # Another replica created the tables between our existence check and
        # our CREATE TABLE.
        logger.info("Schema creation raced with another instance, retrying: %s", exc)
        Base.metadata.create_all(engine)


def seed_modules(engine: Engine) -> None:
    for data in SEED_MODULES:
        with Session(engine) as db:
            if db.get(Module, data["id"]) is not None:
                continue
            db.add(Module(**data))
            try:
                db.commit()
                logger.info("Seeded module %s", data["code"])
            except IntegrityError:
                db.rollback()


def bootstrap(engine: Engine) -> None:
    init_schema(engine)
    seed_modules(engine)
