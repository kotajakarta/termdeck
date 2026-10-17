from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from html import escape
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.trustedhost import TrustedHostMiddleware

from .api import create_api_router, create_ws_router
from .vault import Vault

LOCAL_HOSTS = ["localhost", "127.0.0.1"]


def create_app(
    vault: Vault,
    token: str,
    static_dir: Path | None,
    allowed_hosts: list[str] | None = None,
) -> FastAPI:
    """Build the app.

    `allowed_hosts` restricts the Host header (and WebSocket Origin) to these
    hostnames, which blocks DNS-rebinding and cross-site WebSocket attacks.
    None disables the check (tests).
    """

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        yield
        await vault.lock()

    app = FastAPI(title="TermDeck", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    if allowed_hosts is not None:
        app.add_middleware(TrustedHostMiddleware, allowed_hosts=allowed_hosts)
    app.include_router(create_api_router(vault, token))
    app.include_router(create_ws_router(vault, allowed_hosts))

    index_file = static_dir / "index.html" if static_dir is not None else None
    if static_dir is not None and index_file is not None and index_file.exists():
        # The session token is embedded in the page; only same-origin scripts can read it.
        meta = f'<meta name="termdeck-token" content="{escape(token)}">'

        async def index() -> HTMLResponse:
            # Read on every request so a UI rebuild (new asset hashes) needs no server restart.
            html = index_file.read_text(encoding="utf-8").replace("<head>", f"<head>\n    {meta}", 1)
            return HTMLResponse(html, headers={"Cache-Control": "no-store"})

        app.add_api_route("/", index, methods=["GET"], include_in_schema=False)
        app.add_api_route("/index.html", index, methods=["GET"], include_in_schema=False)
        app.mount("/", StaticFiles(directory=static_dir), name="static")
    return app
