#!/usr/bin/env python3
"""
Export MySQL table DDL from a live database.

This script reads the actual database state with SHOW CREATE TABLE, so the
output reflects the currently connected MySQL schema instead of reconstructing
DDL from migration files.

Connection defaults match this repository's application.yml/docker-compose.yml:

  python3 scripts/export_mysql_schema.py

Common overrides:

  python3 scripts/export_mysql_schema.py \
    --url 'jdbc:mysql://localhost:3306/marketing_cost?useSSL=false' \
    --user root \
    --password root123 \
    --output /Users/xiexicheng/Desktop/demo3/marketing_cost_schema_from_db.sql

Environment variables are also supported:

  SPRING_DATASOURCE_URL
  SPRING_DATASOURCE_USERNAME
  SPRING_DATASOURCE_PASSWORD

or:

  DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence
from urllib.parse import parse_qs, unquote, urlparse

try:
    import pymysql
    from pymysql.cursors import DictCursor
except ImportError:  # pragma: no cover - depends on local tool env
    pymysql = None
    DictCursor = None

try:
    import mysql.connector
except ImportError:  # pragma: no cover - depends on local tool env
    mysql = None
else:
    mysql = mysql.connector


DEFAULT_JDBC_URL = (
    "jdbc:mysql://localhost:3306/marketing_cost"
    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
)


@dataclass(frozen=True)
class DbConfig:
    host: str
    port: int
    database: str
    user: str
    password: str


def parse_jdbc_mysql_url(raw_url: Optional[str]) -> Dict[str, Any]:
    if not raw_url:
        return {}
    url = raw_url.strip()
    if url.startswith("jdbc:"):
        url = url[len("jdbc:") :]
    parsed = urlparse(url)
    if parsed.scheme != "mysql":
        raise ValueError(f"Only mysql JDBC URLs are supported: {raw_url}")
    database = parsed.path.lstrip("/")
    params = parse_qs(parsed.query)
    return {
        "host": parsed.hostname or "localhost",
        "port": parsed.port or 3306,
        "database": unquote(database) if database else "marketing_cost",
        "user": first_param(params, "user"),
        "password": first_param(params, "password"),
    }


def first_param(params: Dict[str, List[str]], key: str) -> Optional[str]:
    values = params.get(key)
    if not values:
        return None
    return values[0]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export live MySQL schema DDL with SHOW CREATE TABLE."
    )
    parser.add_argument(
        "--url",
        default=os.environ.get("SPRING_DATASOURCE_URL") or DEFAULT_JDBC_URL,
        help="JDBC MySQL URL. Defaults to SPRING_DATASOURCE_URL or marketing_cost localhost.",
    )
    parser.add_argument("--host", default=os.environ.get("DB_HOST"))
    parser.add_argument("--port", type=int, default=env_int("DB_PORT"))
    parser.add_argument("--database", default=os.environ.get("DB_NAME"))
    parser.add_argument(
        "--user",
        default=os.environ.get("SPRING_DATASOURCE_USERNAME")
        or os.environ.get("DB_USER"),
    )
    parser.add_argument(
        "--password",
        default=os.environ.get("SPRING_DATASOURCE_PASSWORD")
        or os.environ.get("DB_PASS"),
    )
    parser.add_argument(
        "--output",
        default="/Users/xiexicheng/Desktop/demo3/marketing_cost_schema_from_db.sql",
        help="Output SQL file path.",
    )
    parser.add_argument(
        "--include-drop",
        action="store_true",
        help="Add DROP TABLE IF EXISTS before each CREATE TABLE.",
    )
    parser.add_argument(
        "--include-views",
        action="store_true",
        help="Also export views with SHOW CREATE VIEW.",
    )
    return parser.parse_args()


def env_int(name: str) -> Optional[int]:
    value = os.environ.get(name)
    if not value:
        return None
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer: {value}") from exc


def build_config(args: argparse.Namespace) -> DbConfig:
    url_config = parse_jdbc_mysql_url(args.url)
    return DbConfig(
        host=args.host or url_config.get("host") or "localhost",
        port=args.port or url_config.get("port") or 3306,
        database=args.database or url_config.get("database") or "marketing_cost",
        user=args.user or url_config.get("user") or "root",
        password=args.password or url_config.get("password") or "root123",
    )


def connect(config: DbConfig):
    if pymysql is not None:
        return pymysql.connect(
            host=config.host,
            port=config.port,
            user=config.user,
            password=config.password,
            database=config.database,
            charset="utf8mb4",
            cursorclass=DictCursor,
            autocommit=True,
            read_timeout=120,
            write_timeout=120,
        )
    if mysql is not None:
        return mysql.connect(
            host=config.host,
            port=config.port,
            user=config.user,
            password=config.password,
            database=config.database,
            charset="utf8mb4",
            use_unicode=True,
            autocommit=True,
        )
    print(
        "ERROR: a MySQL Python driver is required. Install one of: "
        "pip install pymysql OR pip install mysql-connector-python",
        file=sys.stderr,
    )
    raise SystemExit(2)


def open_cursor(conn):
    if pymysql is not None and isinstance(conn, pymysql.connections.Connection):
        return conn.cursor()
    return conn.cursor(dictionary=True)


def fetch_all(cursor, sql: str, params: Sequence[Any] = ()) -> List[Dict[str, Any]]:
    cursor.execute(sql, params)
    return list(cursor.fetchall())


def quote_identifier(identifier: str) -> str:
    return "`" + identifier.replace("`", "``") + "`"


def list_objects(cursor, include_views: bool) -> List[Dict[str, str]]:
    table_types = ["BASE TABLE"]
    if include_views:
        table_types.append("VIEW")
    placeholders = ", ".join(["%s"] * len(table_types))
    rows = fetch_all(
        cursor,
        f"""
        SELECT table_name, table_type
          FROM information_schema.tables
         WHERE table_schema = DATABASE()
           AND table_type IN ({placeholders})
         ORDER BY table_type, table_name
        """,
        table_types,
    )
    return [
        {
            "name": row.get("table_name") or row.get("TABLE_NAME"),
            "type": row.get("table_type") or row.get("TABLE_TYPE"),
        }
        for row in rows
    ]


def show_create(cursor, name: str, object_type: str) -> str:
    if object_type == "VIEW":
        cursor.execute(f"SHOW CREATE VIEW {quote_identifier(name)}")
        row = cursor.fetchone()
        return row.get("Create View") or row.get("Create View".lower())
    cursor.execute(f"SHOW CREATE TABLE {quote_identifier(name)}")
    row = cursor.fetchone()
    return row.get("Create Table") or row.get("Create Table".lower())


def strip_auto_increment(create_sql: str) -> str:
    return re.sub(r"\sAUTO_INCREMENT=\d+", "", create_sql)


def render_schema(cursor, config: DbConfig, include_drop: bool, include_views: bool) -> str:
    objects = list_objects(cursor, include_views)
    lines = [
        "-- MySQL schema exported from live database",
        f"-- Database: {config.database}",
        "-- Generated by scripts/export_mysql_schema.py",
        "-- Contains table/view DDL only. No row data is exported.",
        "SET NAMES utf8mb4;",
        "SET FOREIGN_KEY_CHECKS = 0;",
        "",
    ]
    for obj in objects:
        name = obj["name"]
        object_type = obj["type"]
        create_sql = strip_auto_increment(show_create(cursor, name, object_type))
        lines.append("-- ============================================================================")
        lines.append(f"-- {object_type}: {name}")
        lines.append("-- ============================================================================")
        if include_drop and object_type == "BASE TABLE":
            lines.append(f"DROP TABLE IF EXISTS {quote_identifier(name)};")
        elif include_drop and object_type == "VIEW":
            lines.append(f"DROP VIEW IF EXISTS {quote_identifier(name)};")
        lines.append(create_sql + ";")
        lines.append("")
    lines.append("SET FOREIGN_KEY_CHECKS = 1;")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    config = build_config(args)
    output = Path(args.output).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    conn = connect(config)
    try:
        with open_cursor(conn) as cursor:
            sql = render_schema(
                cursor,
                config,
                include_drop=args.include_drop,
                include_views=args.include_views,
            )
    finally:
        conn.close()
    output.write_text(sql, encoding="utf-8")
    print(f"Wrote schema DDL to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
