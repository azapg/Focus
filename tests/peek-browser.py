"""Opt-in live Peek smoke test with an isolated Linux Chromium profile.

Build the host first, then run with:
    FOCUS_TEST_CHROME=/usr/bin/chromium uv run --with playwright python tests/peek-browser.py \
        --x-post <public-post> --x-short <short-link> --facebook-post <public-post>
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.parse import urlencode

from playwright.async_api import Error, async_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
EXTENSION_ID = "mfafgohdpfnknfgedjiicnmgomjpdock"
HOST = "org.nightingalelab.focusdns"


async def heartbeat(file):
    while True:
        stamp = file.with_suffix(".tmp")
        stamp.write_text(str(int(time.time() * 1000)))
        stamp.replace(file)
        await asyncio.sleep(0.5)


async def navigate(page, url):
    previous = page.url
    try:
        await page.goto(url, wait_until="commit")
    except Error as err:
        if "net::ERR_NAME_NOT_RESOLVED" not in str(err) and "net::ERR_ABORTED" not in str(err):
            raise
    prefix = f"chrome-extension://{EXTENSION_ID}/peek.html?id="
    old_token = previous.removeprefix(prefix) if previous.startswith(prefix) else ""
    await expect(page).to_have_url(
        re.compile(rf"^{re.escape(prefix)}(?!{re.escape(old_token)}$)[a-f0-9-]+$"),
        timeout=20_000,
    )


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--x-post", required=True)
    parser.add_argument("--x-short", required=True)
    parser.add_argument("--facebook-post", required=True)
    args = parser.parse_args()
    host_exe = ROOT / "browser-host/target/debug/focus-browser-host"
    assert host_exe.is_file(), "Build the native browser host first"
    with tempfile.TemporaryDirectory(prefix="focus-peek-e2e-") as temp:
        base = Path(temp)
        profile = base / "profile"
        registration = profile / "NativeMessagingHosts"
        registration.mkdir(parents=True)
        (registration / f"{HOST}.json").write_text(json.dumps({
            "name": HOST,
            "description": "isolated Peek smoke test host",
            "path": str(host_exe),
            "type": "stdio",
            "allowed_origins": [f"chrome-extension://{EXTENSION_ID}/"],
        }))
        data = base / "data" / "FocusDNS"
        browser_data = data / "browser"
        browser_data.mkdir(parents=True)
        state = data / "state.json"
        state.write_text('{"paused_until":0}')
        pulse = asyncio.create_task(heartbeat(browser_data / "app-seen"))
        try:
            async with async_playwright() as p:
                context = await p.chromium.launch_persistent_context(
                    str(profile),
                    executable_path=os.environ.get("FOCUS_TEST_CHROME", "/usr/bin/chromium"),
                    headless=True,
                    env={**os.environ, "HOME": str(base), "XDG_CONFIG_HOME": str(base / "config"),
                         "LOCALAPPDATA": str(data.parent)},
                    args=[f"--disable-extensions-except={ROOT}/extension",
                          f"--load-extension={ROOT}/extension", "--no-sandbox",
                          "--host-resolver-rules=MAP x.com ~NOTFOUND, MAP www.facebook.com ~NOTFOUND"],
                    viewport={"width": 960, "height": 800},
                )
                try:
                    worker = context.service_workers[0] if context.service_workers else await context.wait_for_event("serviceworker")
                    short = await worker.evaluate("""({ host, url }) => new Promise((resolve) => {
                        const port = chrome.runtime.connectNative(host);
                        const timer = setTimeout(() => { resolve(null); port.disconnect(); }, 10000);
                        port.onMessage.addListener((message) => {
                            if (message.type !== 'peek_reply') return;
                            clearTimeout(timer);
                            resolve(message.result.url);
                            port.disconnect();
                        });
                        port.postMessage({ type: 'peek_follow', version: 1, url });
                    })""", {"host": HOST, "url": args.x_short})
                    assert short == "https://twitter.com/espn/status/2120010545872949671/video/1", short
                    page = context.pages[0]
                    await page.bring_to_front()
                    google = "https://www.google.com/url?" + urlencode({"url": args.facebook_post, "ved": "test"})
                    await context.route(google, lambda route: route.fulfill(
                        status=302, headers={"location": args.facebook_post},
                    ))
                    requested_images = []
                    page.on("request", lambda req: requested_images.append(req.url) if req.resource_type == "image" else None)
                    for url, site in [(args.x_post, "X"), (google, "Facebook")]:
                        await navigate(page, url)
                        await expect(page.locator("#status")).not_to_have_text("Loading…", timeout=20_000)
                        assert await page.locator("#snapshot").is_visible(), await page.locator("#status").inner_text()
                        await expect(page.locator("#site")).to_have_text(site)
                        await page.wait_for_function("document.querySelector('#image').naturalWidth > 0")
                        assert (await page.locator("img").get_attribute("src")).startswith("data:image/")
                        assert await page.locator("a, iframe, video").count() == 0
                    assert all(url.startswith("data:image/") for url in requested_images), requested_images

                    await page.locator("#pause").click()
                    await expect(page.locator("#pause")).to_be_enabled()
                    await expect(page.locator("#pause-status")).to_be_hidden()
                    assert int((browser_data / "peek-pause").read_text()) > 0
                    assert json.loads(state.read_text())["paused_until"] == 0
                    await navigate(page, "https://x.com/explore")
                    await page.get_by_text("Open a direct link to a public post.", exact=False).wait_for()
                    rows = [json.loads(line) for file in (browser_data / "peeks").glob("*.jsonl")
                            for line in file.read_text().splitlines()]
                    assert len(rows) == 2 and all(row.keys() == {"t"} for row in rows)
                    print("PASS: short-link discovery, X and Facebook snapshots, inline images, pause handoff, feed refusal, timestamp-only history")
                finally:
                    await context.close()
        finally:
            pulse.cancel()


if __name__ == "__main__":
    asyncio.run(main())
