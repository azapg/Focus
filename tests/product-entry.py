"""Smoke the checked-in offline shell and its production desktop frontend."""

import asyncio

from screenshot_browser import PRODUCT_ENTRY, fixture_browser, fixture_page


async def main(output=None):
    async with fixture_browser() as context:
        page = await fixture_page(context)
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        await page.goto(PRODUCT_ENTRY.as_uri())
        entry_url = page.url
        app = page.frame_locator("#focus-desktop-frame")
        await app.locator("#panel.open").wait_for()
        assert await app.locator(".amb > span").first.text_content() == "·", "The embedded frontend must keep UTF-8 text."
        zero_balance = app.get_by_role("button", name="Store, 0 coins")
        await zero_balance.wait_for()
        await zero_balance.click()
        store = app.get_by_role("dialog", name="Store", exact=True)
        await store.wait_for()
        assert await store.get_by_role("button", name="15 coins").is_disabled()
        await store.get_by_role("button", name="Close Store").click()
        await store.wait_for(state="hidden")
        await page.get_by_role("button", name="Funded sample · 65 coins").click()
        assert page.url == entry_url, "Sample switching should stay inside the preview."
        funded_balance = app.get_by_role("button", name="Store, 65 coins")
        await funded_balance.wait_for()
        await funded_balance.click()
        store = app.get_by_role("dialog", name="Store", exact=True)
        await store.get_by_role("button", name="15 coins").click()
        confirm = app.get_by_role("dialog", name="Are you sure?")
        await confirm.get_by_role("button", name="Confirm · 15 coins").click()
        receipt = app.get_by_role("dialog", name="5 min free started")
        await receipt.wait_for()
        await receipt.get_by_role("button", name="Done").click()
        await app.get_by_role("button", name="Store, 50 coins").wait_for()
        await page.get_by_role("button", name="Clean wallet · 0 coins").click()
        assert page.url == entry_url, "Returning to the clean sample should stay inside the preview."
        await app.get_by_role("button", name="Store, 0 coins").wait_for()

        assert not errors, errors
        print("PASS: checked-in entry opens Today, switches clean/funded samples in place, disables a zero-coin purchase, and completes the fictional funded Store purchase.")


if __name__ == "__main__":
    asyncio.run(main())
