#!/usr/bin/env python3
"""Run the four Buyer API integration tests against a ready local runtime."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


REQUIRED_FIXTURE_KEYS = (
    "NEXA_DEV_BUYER_EMAIL",
    "NEXA_DEV_BUYER_PASSWORD",
    "NEXA_DEV_TENANT_SLUG",
    "NEXA_DEV_WORKSPACE_SLUG",
)
OPTIONAL_FIXTURE_KEYS = ("NEXA_LOCAL_SALES_ORDER_ID",)
ALLOWED_FIXTURE_KEYS = set(REQUIRED_FIXTURE_KEYS + OPTIONAL_FIXTURE_KEYS)
FIXTURE_FILE_ENV = "NEXA_BUYER_LOCAL_API_ENV_FILE"
DEFAULT_FIXTURE_FILE = Path.home() / ".config/nexa/buyer-local-api.env"


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(2)


def is_inside_git_worktree(path: Path) -> bool:
    return any((parent / ".git").exists() for parent in (path, *path.parents))


def parse_private_fixture_file(path: Path, repo_root: Path) -> dict[str, str]:
    if path.is_symlink():
        fail("Buyer API fixture file must not be a symbolic link.")
    try:
        resolved = path.resolve(strict=True)
        metadata = resolved.stat()
    except OSError:
        fail("Buyer API fixture file is unavailable.")
    if not stat.S_ISREG(metadata.st_mode):
        fail("Buyer API fixture path must be a regular file.")
    if metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) != 0o600:
        fail("Buyer API fixture file must be owned by this user with mode 0600.")
    try:
        resolved.relative_to(repo_root)
    except ValueError:
        pass
    else:
        fail("Buyer API fixture file must be outside the Mobile repository.")
    if is_inside_git_worktree(resolved):
        fail("Buyer API fixture file must be outside Git worktrees.")

    values: dict[str, str] = {}
    try:
        lines = resolved.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        fail("Buyer API fixture file could not be read as UTF-8.")
    for line_number, original in enumerate(lines, start=1):
        line = original.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, raw_value = line.partition("=")
        key = key.strip()
        if not separator or key not in ALLOWED_FIXTURE_KEYS:
            fail(f"Unsupported Buyer API fixture entry on line {line_number}.")
        if key in values:
            fail(f"Duplicate Buyer API fixture key on line {line_number}.")
        value = raw_value.strip()
        if value.startswith(("'", '"')):
            quote = value[0]
            if len(value) < 2 or value[-1] != quote or quote in value[1:-1]:
                fail(f"Invalid quoted Buyer API fixture value on line {line_number}.")
            value = value[1:-1]
        elif any(character.isspace() or character in "'\"#`\\" for character in value):
            fail(f"Quote special Buyer API fixture values on line {line_number}.")
        if not value or "\x00" in value:
            fail(f"Empty or invalid Buyer API fixture value on line {line_number}.")
        values[key] = value
    return values


def local_api_origin() -> str:
    raw = os.environ.get("NEXA_API_BASE_URL", "http://localhost:8080")
    try:
        parsed = urlsplit(raw)
        host = parsed.hostname
        _ = parsed.port
    except ValueError:
        fail("NEXA_API_BASE_URL must be a local HTTP origin.")
    if (
        parsed.scheme != "http"
        or host not in {"localhost", "127.0.0.1", "::1"}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        fail("NEXA_API_BASE_URL must be a local HTTP loopback origin.")
    return f"http://{parsed.netloc}"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def require_api_readiness(origin: str) -> None:
    request = Request(f"{origin}/actuator/health/readiness", method="GET")
    try:
        with build_opener(NoRedirect).open(request, timeout=3) as response:
            if response.status != 200 or json.load(response).get("status") != "UP":
                fail("Local API is not ready; start the local Tenant runtime first.")
    except (HTTPError, URLError, TimeoutError, OSError, ValueError):
        fail("Local API is not ready; start the local Tenant runtime first.")


def main() -> int:
    repo_root = Path(__file__).resolve().parents[1]
    app_root = repo_root / "apps/buyer-mobile"
    environment = os.environ.copy()
    fixture_path_value = environment.get(FIXTURE_FILE_ENV)
    fixture_path = (
        Path(fixture_path_value).expanduser()
        if fixture_path_value
        else DEFAULT_FIXTURE_FILE
    )
    if fixture_path_value or fixture_path.exists() or fixture_path.is_symlink():
        environment.update(parse_private_fixture_file(fixture_path, repo_root))
    environment.pop(FIXTURE_FILE_ENV, None)

    for key in REQUIRED_FIXTURE_KEYS:
        if not environment.get(key):
            fail(f"Missing Buyer API fixture value: {key}.")
    sales_order_id = environment.get("NEXA_LOCAL_SALES_ORDER_ID")
    if sales_order_id and not re.fullmatch(
        r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
        sales_order_id,
    ):
        fail("NEXA_LOCAL_SALES_ORDER_ID must be a UUID.")

    origin = local_api_origin()
    require_api_readiness(origin)
    flutter = os.environ.get("FLUTTER_BIN", "flutter")
    command = [
        flutter,
        "test",
        "--no-pub",
        "--reporter",
        "compact",
        "--dart-define=NEXA_RUN_LOCAL_API_INTEGRATION=true",
        f"--dart-define=NEXA_API_BASE_URL={origin}",
        "test/local_api_integration_test.dart",
        "test/local_financial_reads_integration_test.dart",
    ]
    print("Local API readiness: UP; running four opt-in Buyer integration tests.")
    return subprocess.run(command, cwd=app_root, env=environment, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
