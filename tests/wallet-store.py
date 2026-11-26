"""Exercise the production desktop Store with fictional wallet responses."""

import asyncio
import runpy

from screenshot_browser import BUILD, capture_panel, fixture_browser, fixture_page


MOCK = r"""(() => {
  const invoke = window.__TAURI__.core.invoke;
  const wallet = window.__wallet = {
    active: false, day: 20496, balance: 65, time_off_bought: 0,
    time_off_limit: 45, time_off_price_per_minute: 1, needs_upgrade: [],
  };
  const receipts = new Set();
  window.__TAURI__.core.invoke = async (command, args) => {
    if (command === 'wallet_view') return structuredClone(wallet);
    if (command === 'habits_view') return {
      rows: [{habit: {id: 'read', name: 'Read 10 pages', reward: 5, weekdays: 0, interval: 1,
                      time: null}, due: true, streak: 3, week: []}],
      credit: wallet.time_off_bought, cap: 45, wallet_active: wallet.active,
    };
    if (command !== 'wallet_buy_time') return invoke(command, args);
    window.__calls.push({c: command, args});
    if (receipts.has(args.requestId)) return {status: 'already_bought'};
    if (args.expectedDay !== wallet.day) throw Error('Day changed');
    wallet.balance -= args.minutes;
    wallet.time_off_bought += args.minutes;
    receipts.add(args.requestId);
    window.__handlers['habits-updated']?.({});
    window.__handlers['wallet-updated']?.({});
    if (window.__loseReceipt) throw Error('Response lost');
    return {status: 'bought'};
  };
})();"""


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
        coins = page.get_by_role("button", name="Store, 65 coins")
        assert not await coins.count()
        await page.evaluate("""() => {
            window.__wallet.active = true;
            window.__handlers['wallet-updated']({});
            window.__handlers['habits-updated']({});
        }""")
        await coins.click()
        await store.get_by_text("15 minutes for today").wait_for()
        await page.locator("#panel .habit-reward").get_by_text("+5 coins", exact=True).wait_for()
        await capture_panel(page, output, "desktop-store.png")
        await page.keyboard.press("Escape")
        await store.wait_for(state="hidden")
        assert await coins.evaluate("(button) => button === document.activeElement")
        await coins.click()
        await store.get_by_role("button", name="15 coins").click()
        confirm = page.get_by_role("dialog", name="Buy 15 minutes?")
        await capture_panel(page, output, "desktop-store-confirm.png")
        await page.evaluate("window.__loseReceipt = true")
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        await confirm.get_by_text("This purchase couldn’t be confirmed. Try confirming again.").wait_for()
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        receipt = page.get_by_role("dialog", name="15 minutes added")
        await receipt.wait_for()
        await page.get_by_role("button", name="Store, 50 coins").wait_for()
        await page.wait_for_function("window.focusHabitCredit === 15")
        await page.get_by_text("60m base + 15m bought", exact=True).wait_for()
        await capture_panel(page, output, "desktop-store-receipt.png")
        await receipt.get_by_role("button", name="Done").click()
        requests = await page.evaluate("window.__calls.filter(c => c.c === 'wallet_buy_time').map(c => c.args)")
        assert requests[0] == requests[1]
        assert requests[0]["minutes"] == 15 and requests[0]["expectedDay"] == 20496

        await page.get_by_role("button", name="Store, 50 coins").click()
        await store.get_by_role("button", name="15 coins").click()
        await page.evaluate("window.__wallet.day += 1; window.__wallet.time_off_bought = 0")
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        await store.get_by_text("A new day started. Check today’s allowance before buying.").wait_for()
        assert await page.evaluate("window.__wallet.balance") == 50
        assert not errors, errors
        print("PASS: desktop Store activation, keyboard dismissal, purchase, lost receipt retry, allowance and midnight.")


if __name__ == "__main__":
    asyncio.run(main())
