"""Exercise the Focus nightly updater UI with synthetic Tauri responses."""
import asyncio

from screenshot_browser import BUILD, capture_panel, fixture_browser, fixture_page

MOCK = r"""(() => {
 const m=60000, d=new Date('2026-02-12T12:00:00Z'); d.setHours(0,0,0,0); const base=d.getTime();
 const now=base+14*60*m, s=base+9*60*m;
 window.__calls=[];
 window.__history={now,spans:[{s,e:s+90*m,app:'VS Code',label:'VS Code'},{s:s+90*m,e:s+115*m,app:'YouTube',label:'YouTube'}],pauses:[]};
 window.__update={
   status:'available',
   current_build_id:'111111a',
   current_commit_sha:'111111a000000000000000000000000000000000',
   built_at:'2026-02-11T22:00:00Z',
   dry_run:false,
   enabled:true,
   repo:'example-org/focus',
   tag:'nightly',
   has_token:false,
   available:{
     build_id:'222222b',
     commit_sha:'222222b000000000000000000000000000000000',
     commit_subject:'fix: retain the selected activity tab',
     published_at:'2026-02-12T05:30:00Z',
     size_bytes:9840128,
     dismissed:false
   },
   last_checked_at:now-120000,
   error:null,
   message:null
 };
 window.__handlers={};
 window.__TAURI__={
   event:{listen:async(name,fn)=>{window.__handlers[name]=fn;return()=>{}}},
   core:{invoke:async(c,args={})=>{
     window.__calls.push({c,args});
     if(c==='get_state')return {paused_at:0,paused_until:0,now,dry_run:false};
     if(c==='startup_panel')return 'today';
     if(c==='history')return window.__history;
     if(c==='activity_rules_load')return {version:1,rules:[]};
     if(c==='activity_rules_save'||c==='activity_uncategorized_save')return null;
     if(c==='device_list')return [];
     if(c==='updater_status')return structuredClone(window.__update);
     if(c==='updater_dismiss'){
       if(window.__update.available) window.__update.available.dismissed=true;
       window.__handlers.update?.({payload:structuredClone(window.__update)});
       return structuredClone(window.__update);
     }
     if(c==='updater_set_enabled'){
       window.__update.enabled=Boolean(args.enabled);
       window.__handlers.update?.({payload:structuredClone(window.__update)});
       return structuredClone(window.__update);
     }
     if(c==='updater_set_token'){
       window.__update.has_token=Boolean(args.token);
       if(window.__update.has_token){window.__update.status='idle';window.__update.error=null;}
       window.__handlers.update?.({payload:structuredClone(window.__update)});
       return structuredClone(window.__update);
     }
     if(c==='updater_check'){
       window.__update.last_checked_at=now;
       window.__update.status='available';
       window.__update.error=null;
       window.__handlers.update?.({payload:structuredClone(window.__update)});
       return structuredClone(window.__update);
     }
     if(c==='updater_install'){
       window.__update.status='applying';
       window.__update.message='Installing update and restarting...';
       window.__handlers.update?.({payload:structuredClone(window.__update)});
       return structuredClone(window.__update);
     }
     return null;
   }}
 };
})();"""


async def main(output=None):
    async with fixture_browser() as context:
        await context.add_init_script(MOCK)
        page = await fixture_page(context)
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        await page.goto((BUILD / "index.html").as_uri())
        await page.locator("#panel.open").wait_for()
        await page.wait_for_timeout(600)

        # 1. Hover pill T3 circle-up icon + panel header update icon are visible (no inline banner).
        assert await page.locator("#dockUpdateBtn").is_visible()
        assert await page.locator("#headerUpdateBtn").is_visible()
        assert await page.locator("#updatePopover").is_hidden()

        # Clicking the circle-up icon opens the dark obsidian-glass update popover (no Later button).
        await page.locator("#headerUpdateBtn").click()
        await page.locator("#updatePopover").wait_for()
        pop_text = await page.locator("#updatePopover").inner_text()
        assert "Update available" in pop_text
        assert "Update & restart" in pop_text
        assert "Later" not in pop_text

        await capture_panel(page, output, "updater-popover.png")

        # 2. Closing the popover hides it cleanly while keeping the subtle icon button.
        await page.locator("#closeUpdatePopover").click()
        await page.locator("#updatePopover").wait_for(state="hidden")
        assert await page.locator("#dockUpdateBtn").is_visible()

        # 3. Settings opens on T3 2-pane Protection tab; Updates tab shows build + token form.
        await page.locator("#deviceSettings").click()
        await page.locator("#settingsPage").wait_for(state="visible")
        await page.wait_for_timeout(150)
        await capture_panel(page, output, "settings-protection.png")

        await page.locator('[data-stab="updates"]').click()
        await page.locator("#updaterBuild").wait_for()
        assert "Build 111111a" in await page.locator("#updaterBuild").inner_text()
        assert await page.locator("#settingsUpdateNow").is_visible()

        await page.get_by_role("button", name="Add token").click()
        await page.locator("#updaterToken").wait_for()
        await page.wait_for_timeout(150)

        await capture_panel(page, output, "updater-settings.png")

        # 4. Saving token encrypts & triggers check, clears input, never leaks token in DOM.
        await page.locator("#updaterToken").fill("github_pat_example_secret_value")
        await page.locator("#saveUpdaterToken").click()
        await page.locator("#removeUpdaterToken").wait_for()
        body_text = await page.locator("#settingsPage").inner_text()
        assert "github_pat_example_secret_value" not in body_text
        assert await page.evaluate(
            "window.__calls.some(c => c.c === 'updater_set_token' && c.args.token === 'github_pat_example_secret_value')"
        )
        assert await page.evaluate(
            "window.__calls.some(c => c.c === 'updater_check')"
        )

        # 5. Update & restart triggers updater_install and transitions to applying state.
        await page.locator("#settingsUpdateNow").click()
        await page.wait_for_function(
            "document.querySelector('#updaterStatus')?.textContent?.includes('Installing update')"
        )
        assert await page.evaluate(
            "window.__calls.some(c => c.c === 'updater_install')"
        )
        assert not errors, f"Unexpected page errors: {errors}"
        print("updater-ui.py passed")


if __name__ == "__main__":
    asyncio.run(main())
