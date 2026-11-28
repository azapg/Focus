"""Exercise the production desktop Store with fictional wallet responses."""

import asyncio
from pathlib import Path
import runpy

from screenshot_browser import BUILD, capture_panel, fixture_browser, fixture_page


MOCK = (Path(__file__).parent / "fixtures" / "wallet-store.mock.js").read_text(encoding="utf-8")


async def main(output=None):
    async with fixture_browser() as context:
        device_mock = runpy.run_path(str(BUILD.parent / "tests/device-panel.py"))["MOCK"]
        await context.add_init_script(device_mock + "\n" + MOCK)
        page = await fixture_page(context)
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        await page.goto((BUILD / "index.html").as_uri())
        await page.locator("#panel.open").wait_for()
        store = page.get_by_role("dialog", name="Store", exact=True)
        fresh_wallet = page.get_by_role("button", name="Store, 0 coins")
        await fresh_wallet.wait_for()
        await fresh_wallet.click()
        assert await store.get_by_role("button", name="15 coins").is_disabled()
        assert not await store.get_by_text("Streak Freeze").count()
        await page.keyboard.press("Escape")
        await store.wait_for(state="hidden")
        await page.evaluate("""() => {
            window.__wallet.balance = 65;
            window.__handlers['wallet-updated']({});
            window.__wallet.active = false;
            window.__handlers['wallet-updated']({});
            window.__handlers['habits-updated']({});
        }""")
        await page.get_by_role("button", name="Store, 65 coins").wait_for(state="hidden")
        await page.locator("#panel .habit-reward").get_by_text("+5m", exact=True).wait_for()
        await capture_panel(page, output, "desktop-store-before.png")
        await page.evaluate("""() => {
            window.__wallet.active = true;
            window.__wallet.balance = 0;
            window.__handlers['wallet-updated']({});
            window.__handlers['habits-updated']({});
        }""")
        await page.get_by_role("button", name="Store, 0 coins").click()
        assert await store.get_by_role("button", name="15 coins").is_disabled()
        assert not await store.get_by_text("Streak Freeze").count()
        await capture_panel(page, output, "desktop-store-first-open.png")
        await page.keyboard.press("Escape")
        await store.wait_for(state="hidden")
        await page.evaluate("""() => {
            window.__wallet.balance = 65;
            window.__handlers['wallet-updated']({});
        }""")
        coins = page.get_by_role("button", name="Store, 65 coins")
        await coins.click()
        await store.get_by_text("5 min free").wait_for()
        assert not await store.get_by_text("Streak Freeze").count()
        await page.locator("#panel .habit-reward").get_by_text("+5 coins", exact=True).wait_for()
        await capture_panel(page, output, "desktop-store.png")
        await page.keyboard.press("Escape")
        await store.wait_for(state="hidden")
        assert await coins.evaluate("(button) => button === document.activeElement")
        await coins.click()
        await store.get_by_role("button", name="15 coins").click()
        confirm = page.get_by_role("dialog", name="Are you sure?")
        await capture_panel(page, output, "desktop-store-confirm.png")
        await page.evaluate("window.__loseReceipt = true")
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        await confirm.get_by_text("This purchase couldn’t be confirmed. Try again.").wait_for()
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        receipt = page.get_by_role("dialog", name="5 min free started")
        await receipt.wait_for()
        await page.get_by_role("button", name="Store, 50 coins").wait_for()
        await page.wait_for_function("window.focusHabitCredit === 5")
        await page.get_by_text("60m base + 5m extra", exact=True).wait_for()
        await capture_panel(page, output, "desktop-store-receipt.png")
        await receipt.get_by_role("button", name="Done").click()
        requests = await page.evaluate("window.__calls.filter(c => c.c === 'wallet_buy_time').map(c => c.args)")
        assert requests[0] == requests[1]
        assert requests[0]["minutes"] == 5 and requests[0]["expectedDay"] == 20496

        await page.get_by_role("button", name="Store, 50 coins").click()
        await store.get_by_role("button", name="15 coins").click()
        await page.evaluate("window.__wallet.day += 1; window.__wallet.time_off_bought = 0")
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        await store.get_by_text("A new day started. Check today’s limit before buying.").wait_for()
        assert await page.evaluate("window.__wallet.balance") == 50
        await page.evaluate("""() => {
            window.__streak = {freezeReady: false, priceCoins: 60};
            window.__handlers['wallet-updated']({});
            window.__handlers['habits-updated']({});
        }""")
        await page.wait_for_function("window.focusHabitCredit === 0")
        freeze = store.get_by_role("button", name="Streak Freeze, 60 coins")
        await freeze.wait_for()
        assert await freeze.is_disabled()
        await store.get_by_text("10 coins needed", exact=True).wait_for()
        await page.evaluate("""() => {
            window.__wallet.balance = 65;
            window.__handlers['wallet-updated']({});
        }""")
        await page.get_by_role("button", name="Store, 65 coins").wait_for()
        await capture_panel(page, output, "desktop-freeze-store.png")
        await freeze.click()
        confirm_freeze = page.get_by_role("dialog", name="Buy a Streak Freeze?")
        await confirm_freeze.get_by_text("Starting tomorrow", exact=False).wait_for()
        await capture_panel(page, output, "desktop-freeze-confirm.png")
        await confirm_freeze.get_by_role("button", name="Confirm · 60 coins").click()
        await confirm_freeze.get_by_text("This purchase couldn’t be confirmed. Try again.").wait_for()
        await confirm_freeze.get_by_role("button", name="Confirm · 60 coins").click()
        freeze_receipt = page.get_by_role("dialog", name="Freeze purchased")
        await freeze_receipt.wait_for()
        await page.get_by_role("button", name="Store, 5 coins").wait_for()
        await capture_panel(page, output, "desktop-freeze-receipt.png")
        await freeze_receipt.get_by_role("button", name="Done").click()
        await page.get_by_role("button", name="Store, 5 coins").click()
        await store.get_by_text("One freeze held").wait_for()
        assert not await freeze.count()
        freeze_requests = await page.evaluate("window.__calls.filter(c => c.c === 'streak_buy_freeze').map(c => c.args)")
        assert len(freeze_requests) == 2 and freeze_requests[0] == freeze_requests[1]
        assert not errors, errors
        print("PASS: fresh zero-balance Store entrypoint opens without Clean Streak; purchases, retries, cutover and freeze inventory are covered.")


if __name__ == "__main__":
    asyncio.run(main())
