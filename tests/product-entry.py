"""Smoke the checked-in offline entry using Focus's production desktop UI."""

import asyncio

from screenshot_browser import PRODUCT_ENTRY, fixture_browser, fixture_page


async def main(output=None):
    async with fixture_browser() as context:
        page = await fixture_page(context)
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        await page.goto(PRODUCT_ENTRY.as_uri())
        await page.locator("#panel.open").wait_for()
        zero_balance = page.get_by_role("button", name="Store, 0 coins")
        await zero_balance.wait_for()
        await zero_balance.click()
        store = page.get_by_role("dialog", name="Store", exact=True)
        await store.wait_for()
        assert await store.get_by_role("button", name="15 coins").is_disabled()
        await page.keyboard.press("Escape")

        await page.goto(PRODUCT_ENTRY.as_uri() + "?wallet=funded")
        await page.locator("#panel.open").wait_for()
        funded_balance = page.get_by_role("button", name="Store, 65 coins")
        await funded_balance.wait_for()
        await funded_balance.click()
        store = page.get_by_role("dialog", name="Store", exact=True)
        await store.get_by_role("button", name="15 coins").click()
        confirm = page.get_by_role("dialog", name="Are you sure?")
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        await page.get_by_role("dialog", name="5 min free started").wait_for()
        await page.get_by_role("button", name="Store, 50 coins").wait_for()

        assert not errors, errors
        print("PASS: generated offline entry opens Today, disables a purchase at 0 coins, and completes the production Store purchase with the fictional 65 coin sample.")


if __name__ == "__main__":
    asyncio.run(main())
