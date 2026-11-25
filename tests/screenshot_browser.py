"""Common browser setup for the production frontend proof flows."""

import datetime
import os
from contextlib import asynccontextmanager
from pathlib import Path
from tempfile import TemporaryDirectory

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "ui-dist"
CLOCK = datetime.datetime(2026, 2, 12, 14, tzinfo=datetime.timezone.utc)


@asynccontextmanager
async def fixture_browser():
    async with async_playwright() as playwright:
        with TemporaryDirectory(prefix="focus-browser-") as home:
            browser = await playwright.chromium.launch(
                executable_path=os.environ.get("FOCUS_TEST_CHROME"),
                env={"HOME": home, "XDG_CONFIG_HOME": home, "XDG_CACHE_HOME": home,
                     "PATH": os.defpath, "LANG": "C.UTF-8"},
                args=["--allow-file-access-from-files", "--disable-notifications",
                      "--disable-background-networking"],
            )
            try:
                context = await browser.new_context(
                    viewport={"width": 820, "height": 760},
                    timezone_id="UTC", locale="en-US", service_workers="block",
                )
                await context.route_web_socket("**/*", lambda socket: socket.close())

                async def local_build_only(route):
                    if route.request.url.startswith(BUILD.as_uri() + "/"):
                        await route.continue_()
                    else:
                        await route.abort()

                await context.route("**/*", local_build_only)
                yield context
            finally:
                await browser.close()


async def fixture_page(context):
    page = await context.new_page()
    await page.clock.set_fixed_time(CLOCK)
    return page


async def capture_panel(page, output, name):
    if output is None:
        return
    panel = await page.locator("#panel").bounding_box()
    await page.screenshot(
        path=output / name, animations="disabled", caret="hide", omit_background=True,
        clip={"x": 0, "y": 0, "width": 820, "height": min(760, panel["height"] + 66)},
    )
