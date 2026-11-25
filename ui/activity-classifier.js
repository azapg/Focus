// V1 Activity Categorization & Entity Normalization for FocusDNS (Windows + TV).
// Pure local rules + user/agent overrides from %LOCALAPPDATA%\FocusDNS\rules.json.
// Turns raw process names, subdomains, URLs, and TV packages into clean app names,
// deduplicated page/item titles, human activity verbs, and Creating vs Consuming totals.

const ActivityClassifier = (() => {
  const MIN = 60e3;
  const DAY = 864e5;

  const CATEGORIES = [
    { id: 'building', label: 'Building', group: 'creating', order: 1 },
    { id: 'writing', label: 'Writing', group: 'creating', order: 2 },
    { id: 'designing', label: 'Designing', group: 'creating', order: 3 },
    { id: 'watching', label: 'Watching', group: 'consuming', order: 4 },
    { id: 'browsing', label: 'Browsing', group: 'consuming', order: 5 },
    { id: 'messaging', label: 'Messaging', group: 'consuming', order: 6 },
    { id: 'listening', label: 'Listening', group: 'consuming', order: 7 },
    { id: 'reading', label: 'Reading', group: 'learn', order: 8 },
    { id: 'learning', label: 'Learning', group: 'learn', order: 9 },
    { id: 'system', label: 'Utilities', group: 'other', order: 10 },
    { id: 'unknown', label: 'Uncategorized', group: 'unknown', order: 11 },
  ];

  const CATEGORY_MAP = Object.fromEntries(CATEGORIES.map((c) => [c.id, c]));

  const VALID_RULE_CATEGORIES = new Set([
    'building', 'writing', 'designing', 'watching', 'browsing', 'messaging', 'listening', 'reading', 'learning', 'system', 'ignore',
  ]);

  const BROWSER_APPS = new Set([
    'chrome', 'edge', 'brave', 'firefox', 'opera', 'vivaldi', 'arc', 'zen', 'thorium', 'chromium',
  ]);

  const SYSTEM_NOISE_APPS = new Set([
    'searchhost', 'applicationframehost', 'shellhost', 'pickerhost', 'shellexperiencehost',
    'startmenuexperiencehost', 'officeclicktorun', 'credentialuibroker', 'werfault',
    'yourphoneappproxyhost', 'lockapp', 'unknown', 'focusdns',
  ]);

  const SYSTEM_NOISE_HOSTS = new Set([
    'accounts.google.com', 't.co', 'antigravity.google', 'test.nextdns.io',
    'us-east-2.signin.aws.amazon.com',
  ]);

  const DESKTOP_APPS = {
    't3 code': { app: 'T3 Code', category: 'building' },
    'vs code': { app: 'VS Code', category: 'building' },
    'code': { app: 'VS Code', category: 'building' },
    'zed': { app: 'Zed', category: 'building' },
    'cursor': { app: 'Cursor', category: 'building' },
    'terminal': { app: 'Terminal', category: 'building' },
    'windowsterminal': { app: 'Terminal', category: 'building' },
    'docker desktop': { app: 'Docker Desktop', category: 'building' },
    'xampp-control': { app: 'XAMPP', category: 'building' },
    'winword': { app: 'Word', category: 'writing' },
    'word': { app: 'Word', category: 'writing' },
    'excel': { app: 'Excel', category: 'writing' },
    'powerpnt': { app: 'PowerPoint', category: 'writing' },
    'obsidian': { app: 'Obsidian', category: 'writing' },
    'notion': { app: 'Notion', category: 'writing' },
    'google docs': { app: 'Google Docs', category: 'writing' },
    'google sheets': { app: 'Google Sheets', category: 'writing' },
    'discord': { app: 'Discord', category: 'messaging' },
    'whatsapp': { app: 'WhatsApp', category: 'messaging' },
    'slack': { app: 'Slack', category: 'messaging' },
    'telegram': { app: 'Telegram', category: 'messaging' },
    'olk': { app: 'Outlook', category: 'messaging' },
    'outlook': { app: 'Outlook', category: 'messaging' },
    'gmail': { app: 'Gmail', category: 'messaging' },
    'figma': { app: 'Figma', category: 'designing' },
    'spotify': { app: 'Spotify', category: 'listening' },
    'youtube': { app: 'YouTube', category: 'watching' },
    'netflix': { app: 'Netflix', category: 'watching' },
    'twitch': { app: 'Twitch', category: 'watching' },
    'disney+': { app: 'Disney+', category: 'watching' },
    'prime video': { app: 'Prime Video', category: 'watching' },
    'vlc': { app: 'VLC', category: 'watching' },
    'github': { app: 'GitHub', category: 'building' },
    'colab': { app: 'Google Colab', category: 'building' },
    'claude': { app: 'Claude', category: 'building' },
    'chatgpt': { app: 'ChatGPT', category: 'building' },
    'x': { app: 'X', category: 'browsing' },
    'reddit': { app: 'Reddit', category: 'browsing' },
    'instagram': { app: 'Instagram', category: 'browsing' },
    'facebook': { app: 'Facebook', category: 'browsing' },
    'tiktok': { app: 'TikTok', category: 'watching' },
    'linkedin': { app: 'LinkedIn', category: 'browsing' },
    'hacker news': { app: 'Hacker News', category: 'browsing' },
    'threads': { app: 'Threads', category: 'browsing' },
    'bluesky': { app: 'Bluesky', category: 'browsing' },
    'classroom': { app: 'Google Classroom', category: 'browsing' },
    'explorer': { app: 'File Explorer', category: 'system' },
    'snippingtool': { app: 'Snipping Tool', category: 'system' },
    'keepassxc': { app: 'KeePassXC', category: 'system' },
    'taskmgr': { app: 'Task Manager', category: 'system' },
    'googledrivefs': { app: 'Google Drive', category: 'system' },
    'google drive': { app: 'Google Drive', category: 'system' },
    'google calendar': { app: 'Google Calendar', category: 'system' },
    'readest': { app: 'Readest', category: 'reading' },
    'duolingo': { app: 'Duolingo', category: 'learning' },
    'remote atv': { app: 'Remote ATV', category: 'system' },
    'google tv': { app: 'Google TV', category: 'watching' },
    'nextdns': { app: 'NextDNS', category: 'system' },
  };

  const TV_PACKAGES = {
    'com.google.android.youtube.tv': { app: 'YouTube', category: 'watching' },
    'com.google.android.youtube.tvkids': { app: 'YouTube Kids', category: 'watching' },
    'com.google.android.youtube.tvunplugged': { app: 'YouTube TV', category: 'watching' },
    'com.google.android.youtube.tvmusic': { app: 'YouTube Music', category: 'listening' },
    'com.netflix.ninja': { app: 'Netflix', category: 'watching' },
    'tv.twitch.android.app': { app: 'Twitch', category: 'watching' },
    'com.disney.disneyplus': { app: 'Disney+', category: 'watching' },
    'com.disney.starplus': { app: 'Star+', category: 'watching' },
    'com.spotify.tv.android': { app: 'Spotify', category: 'listening' },
    'com.apple.atve.androidtv.appletv': { app: 'Apple TV', category: 'watching' },
    'com.amazon.amazonvideo.livingroom': { app: 'Prime Video', category: 'watching' },
    'com.hbo.hbonow': { app: 'HBO Max', category: 'watching' },
    'com.wbd.stream': { app: 'HBO Max', category: 'watching' },
    'com.hulu.livingroomplus': { app: 'Hulu', category: 'watching' },
    'com.crunchyroll.crunchyroid': { app: 'Crunchyroll', category: 'watching' },
    'com.stremio.one': { app: 'Stremio', category: 'watching' },
    'com.peacocktv.peacockandroid': { app: 'Peacock TV', category: 'watching' },
    'com.tubitv': { app: 'Tubi', category: 'watching' },
    'com.espn.score_center': { app: 'ESPN', category: 'watching' },
    'com.nbaimd.gametime.nba2011': { app: 'NBA', category: 'watching' },
    'com.dazn': { app: 'DAZN', category: 'watching' },
    'com.viki.android': { app: 'Viki', category: 'watching' },
    'com.discovery.discoveryplus.androidtv': { app: 'discovery+', category: 'watching' },
    'com.viacom.betplus': { app: 'BET+', category: 'watching' },
    'com.bydeluxe.d3.android.program.starz': { app: 'STARZ', category: 'watching' },
    'com.sling': { app: 'Sling TV', category: 'watching' },
    'com.philo.philo.google': { app: 'Philo', category: 'watching' },
    'com.google.android.videos': { app: 'Google TV', category: 'watching' },
    'tv.hackertube': { app: 'HackerTube', category: 'watching' },
    'com.tvnpass.tv': { app: 'TVNPass', category: 'watching' },
    'com.tcl.tv': { app: 'Live TV', category: 'watching' },
    'com.tcl.partnercustomizer': { app: 'TCL Channel', category: 'watching' },
    'com.tcl.waterfall.overseas': { app: 'TCL Channel', category: 'watching' },
    'com.tcl.ui_mediaCenter': { app: 'Media Player', category: 'watching' },
    'com.tcl.browser': { app: 'BrowseHere', category: 'browsing' },
    'com.google.android.play.games': { app: 'Play Games', category: 'watching' },
    'com.FDGEntertainment.redball4.gp': { app: 'Red Ball 4', category: 'watching' },
    'com.tcl.dashboard': { app: 'TCL Home', category: 'system', noise: true },
    'com.tcl.suspension': { app: 'Quick Panel', category: 'system', noise: true },
    'com.tcl.guard': { app: 'Safety Guard', category: 'system', noise: true },
    'com.tcl.gallery': { app: 'Gallery', category: 'system' },
    'com.tcl.t_solo': { app: 'T-Solo', category: 'system', noise: true },
    'com.android.vending': { app: 'Play Store', category: 'system' },
    // Phone apps
    'com.bilingify.readest': { app: 'Readest', category: 'reading' },
    'com.duolingo': { app: 'Duolingo', category: 'learning' },
    'tech.simha.androidtvremote': { app: 'Remote ATV', category: 'system' },
    'com.instagram.android': { app: 'Instagram', category: 'browsing' },
    'com.twitter.android': { app: 'X', category: 'browsing' },
    'com.reddit.frontpage': { app: 'Reddit', category: 'browsing' },
    'com.zhiliaoapp.musically': { app: 'TikTok', category: 'watching' },
    'com.google.android.youtube': { app: 'YouTube', category: 'watching' },
    'com.netflix.mediaclient': { app: 'Netflix', category: 'watching' },
    'com.spotify.music': { app: 'Spotify', category: 'listening' },
    'com.whatsapp': { app: 'WhatsApp', category: 'messaging' },
    'com.whatsapp.w4b': { app: 'WhatsApp Business', category: 'messaging' },
    'com.discord': { app: 'Discord', category: 'messaging' },
    'org.telegram.messenger': { app: 'Telegram', category: 'messaging' },
    'com.anthropic.claude': { app: 'Claude', category: 'building' },
    'com.openai.chatgpt': { app: 'ChatGPT', category: 'building' },
    'com.github.android': { app: 'GitHub', category: 'building' },
    'com.termux': { app: 'Termux', category: 'building' },
    'com.t3tools.t3code': { app: 'T3 Code', category: 'building' },
    'md.obsidian': { app: 'Obsidian', category: 'writing' },
    'notion.id': { app: 'Notion', category: 'writing' },
    'com.android.chrome': { app: 'Chrome', category: 'browsing' },
    'com.sec.android.app.sbrowser': { app: 'Samsung Internet', category: 'browsing' },
  };

  const HOSTNAME = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/;
  const PLAY_MOVIES = /\bplay movies\b/;
  const PAUSE_HINTS = {
    x: ['twitter', 'tweet', ' x ', 'x.com', 'post'],
    youtube: ['youtube', 'video', 'yt', 'talk', 'watch', 'tv', 'lecture'],
    netflix: ['netflix', 'episode', 'watch', 'tv', 'movie', 'show'],
    twitch: ['twitch', 'stream', 'watch', 'tv'],
    discord: ['discord'],
    reddit: ['reddit'],
    instagram: ['instagram', 'reel', 'ig'],
    whatsapp: ['whatsapp', 'message'],
  };

  function safeUrl(raw) {
    if (!raw || typeof raw !== 'string') return null;
    try {
      return new URL(raw);
    } catch {
      return null;
    }
  }

  function cleanTitle(title) {
    return String(title || '')
      .replace(/\u200b/g, '')
      .replace(/\u00a0/g, ' ')
      .replace(/^\(\d+\)\s+/, '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function stripSuffix(title, suffixes) {
    let out = cleanTitle(title);
    for (const suffix of suffixes) {
      if (typeof suffix === 'string') {
        if (out.toLowerCase().endsWith(suffix.toLowerCase())) {
          out = out.slice(0, -suffix.length).trim();
        }
      } else {
        out = out.replace(suffix, '').trim();
      }
    }
    return out;
  }

  function domainBase(host) {
    return String(host || '').toLowerCase().replace(/^www\./, '');
  }

  function prettyHost(host) {
    const base = domainBase(host);
    if (!base) return 'Web';
    const parts = base.split('.');
    if (parts.length >= 2) {
      const root = parts[parts.length - 2];
      if (root.length <= 3) return base;
      return root.charAt(0).toUpperCase() + root.slice(1);
    }
    return base;
  }

  function normalizeRuleList(userRules) {
    const list = Array.isArray(userRules)
      ? userRules
      : Array.isArray(userRules?.rules)
        ? userRules.rules
        : [];
    return list.filter((r) =>
      r && typeof r === 'object' &&
      typeof r.match === 'string' &&
      typeof r.value === 'string' &&
      r.value.trim() &&
      VALID_RULE_CATEGORIES.has(String(r.category || '').toLowerCase()),
    );
  }

  function findUserRule(rules, { host, app, label, pkg, legacyApp }) {
    if (!rules.length) return null;
    const lowHost = host ? domainBase(host) : '';
    const lowApp = app ? String(app).toLowerCase() : '';
    const lowLabel = label ? String(label).toLowerCase() : '';
    const lowPkg = pkg ? String(pkg).toLowerCase() : '';

    for (const r of rules) {
      const m = r.match.toLowerCase();
      const v = r.value.trim().toLowerCase();
      if (m === 'host' && lowHost) {
        const rv = domainBase(v);
        if (lowHost === rv || lowHost.endsWith('.' + rv)) return r;
      } else if (m === 'package' && lowPkg && lowPkg === v) {
        return r;
      } else if (m === 'app' && (lowApp === v || lowLabel === v || legacyApp === v)) {
        return r;
      }
    }
    return null;
  }

  function upsertUserRule(doc, nextRule) {
    const baseDoc = doc && typeof doc === 'object' ? doc : { version: 1, rules: [] };
    const existing = normalizeRuleList(baseDoc);
    const match = String(nextRule.match || '').toLowerCase();
    const value = String(nextRule.value || '').trim();
    const category = String(nextRule.category || '').toLowerCase();
    if (!match || !value || !VALID_RULE_CATEGORIES.has(category)) {
      throw new Error('Invalid activity rule');
    }
    const clean = {
      match,
      value: match === 'host' ? domainBase(value) : value,
      app: String(nextRule.app || value).trim(),
      category,
    };
    const key = `${clean.match}:${clean.value.toLowerCase()}`;
    const filtered = existing.filter((r) => `${r.match.toLowerCase()}:${(r.match.toLowerCase() === 'host' ? domainBase(r.value) : r.value).toLowerCase()}` !== key);
    filtered.push(clean);
    return { ...(Array.isArray(baseDoc) ? {} : baseDoc), version: 1, rules: filtered };
  }

  function applyRuleOverride(baseResult, rule) {
    if (!rule) return baseResult;
    const cat = String(rule.category).toLowerCase();
    return {
      ...baseResult,
      app: rule.app ? String(rule.app).trim() : baseResult.app,
      category: cat === 'ignore' ? 'system' : cat,
      noise: cat === 'ignore',
      userRule: true,
    };
  }

  function classifyWeb(urlObj, rawTitle, audible) {
    const host = urlObj.hostname.toLowerCase();
    const base = domainBase(host);
    const path = urlObj.pathname || '/';
    const title = cleanTitle(rawTitle);

    if (host === 'localhost' || host === '127.0.0.1') {
      if (/t3 code/i.test(title)) {
        const sub = path === '/pair' ? 'Pair session' : 'Web workspace';
        return { app: 'T3 Code', category: 'building', itemKey: `t3-web:${sub}`, itemTitle: sub, itemKind: 'workspace', matchType: 'host', matchValue: base };
      }
      if (/focus browser installation test/i.test(title) || path.includes('focus-browser-install-test')) {
        return { app: 'FocusDNS Test', category: 'system', itemKey: 'focus-test', itemTitle: 'Browser install test', itemKind: 'utility', noise: true, matchType: 'host', matchValue: base };
      }
      const label = title || `localhost:${urlObj.port || '80'}`;
      return { app: 'Local Dev', category: 'building', itemKey: `local:${urlObj.port || ''}:${label}`, itemTitle: label, itemKind: 'local', itemMeta: urlObj.port ? `port ${urlObj.port}` : 'localhost', matchType: 'host', matchValue: base };
    }

    if (SYSTEM_NOISE_HOSTS.has(host) || SYSTEM_NOISE_HOSTS.has(base)) {
      return {
        app: base === 't.co' ? 'Link redirect' : base === 'accounts.google.com' ? 'Google Sign-in' : prettyHost(host),
        category: 'system',
        itemKey: `sys:${base}`,
        itemTitle: title || base,
        itemKind: 'utility',
        noise: true,
        matchType: 'host',
        matchValue: base,
      };
    }

    const readingOrLearning = base === 'web.readest.com' || base.endsWith('.web.readest.com') ? DESKTOP_APPS.readest
      : base === 'duolingo.com' || base.endsWith('.duolingo.com') ? DESKTOP_APPS.duolingo : null;
    if (readingOrLearning) {
      return { ...readingOrLearning, itemKey: `web:${base}${path}`, itemTitle: title || readingOrLearning.app, itemKind: 'page', matchType: 'host', matchValue: base };
    }

    if (base === 'notion.com' || base === 'app.notion.com' || base === 'notion.so' || host.endsWith('.notion.site')) {
      const idMatch = path.match(/([0-9a-f]{32})(?:$|\/)/i);
      const pageId = idMatch ? idMatch[1].toLowerCase() : path;
      let pageName = stripSuffix(title, [' | Notion', ' - Notion', ' – Notion', ' — Notion']);
      const lowQuality = !pageName || pageName.toLowerCase() === 'notion' || pageName.toLowerCase() === 'new page' || pageName.length <= 2;
      if (!pageName || pageName.toLowerCase() === 'notion') {
        const slug = path.replace(/^\/p\//, '').replace(/-[0-9a-f]{32}$/i, '').replace(/-/g, ' ').trim();
        pageName = slug || 'Workspace';
      }
      return { app: 'Notion', category: 'writing', itemKey: `notion:${pageId}`, itemTitle: pageName, itemKind: 'page', itemMeta: 'Page', lowQualityTitle: lowQuality, matchType: 'host', matchValue: base };
    }

    if (base === 'x.com' || base === 'twitter.com') {
      const query = path === '/search' ? urlObj.searchParams.get('q')?.trim() : '';
      if (query && query !== 'REDACTED') {
        return { app: 'X', category: 'browsing', itemKind: 'search', place: { kind: 'search', label: query }, matchType: 'host', matchValue: base };
      }
      if (path === '/' || path === '/home') {
        return { app: 'X', category: 'browsing', itemKey: 'x:home', itemTitle: 'Home feed', itemKind: 'feed', place: path === '/home' ? { kind: 'home' } : null, matchType: 'host', matchValue: base };
      }
      const statusMatch = path.match(/^\/([^/]+)\/status\/(\d+)/);
      if (statusMatch) {
        const handle = `@${statusMatch[1]}`;
        const snippet = stripSuffix(title, [' / X', ' on X']).replace(/^.+? on X:\s*/i, '').replace(/^"|"$/g, '');
        return {
          app: 'X',
          category: 'browsing',
          itemKey: `x:status:${statusMatch[2]}`,
          itemTitle: snippet ? `${handle}: ${snippet.slice(0, 58)}${snippet.length > 58 ? '…' : ''}` : `${handle} post`,
          itemKind: 'post',
          itemMeta: handle,
          matchType: 'host',
          matchValue: base,
        };
      }
      const profileMatch = path.match(/^\/([A-Za-z0-9_]+)\/?$/);
      if (profileMatch && !['explore', 'notifications', 'messages', 'search', 'i', 'settings', 'compose'].includes(profileMatch[1].toLowerCase())) {
        const handle = `@${profileMatch[1]}`;
        const profileTitle = stripSuffix(title, [' / X', ' / Twitter']);
        const suffix = `(${handle})`;
        const confirmed = profileTitle.toLowerCase().endsWith(suffix.toLowerCase());
        const label = confirmed ? profileTitle.slice(0, -suffix.length).trim() : '';
        return { app: 'X', category: 'browsing', itemKey: `x:profile:${profileMatch[1].toLowerCase()}`, itemTitle: `${handle} profile`, itemKind: 'profile', itemMeta: handle, place: confirmed ? { kind: 'profile', label: label || handle, handle: handle.toLowerCase() } : null, matchType: 'host', matchValue: base };
      }
      return { app: 'X', category: 'browsing', itemKey: `x:other:${path.split('/')[1] || 'other'}`, itemTitle: stripSuffix(title, [' / X']) || 'X', itemKind: 'page', matchType: 'host', matchValue: base };
    }

    if (base === 'acquisition.com') {
      const lesson = stripSuffix(title, [
        ' - $100M Leads Course - Alex Hormozi',
        ' - $100M Offers Course - Alex Hormozi',
        ' - Alex Hormozi',
        ' | Acquisition.com',
      ]);
      const isCourse = path.startsWith('/training/') || /course/i.test(title);
      return {
        app: isCourse ? '$100M Leads Course' : 'Acquisition.com',
        category: isCourse || audible ? 'watching' : 'browsing',
        itemKey: `acq:${path}`,
        itemTitle: lesson || 'Training lesson',
        itemKind: 'lesson',
        itemMeta: 'Acquisition.com',
        matchType: 'host',
        matchValue: base,
      };
    }

    if (base === 'figma.com') {
      const designMatch = path.match(/^\/(?:design|file|board|proto)\/([^/]+)/);
      const communityMatch = path.match(/^\/community\/file\/([^/]+)/);
      const key = designMatch ? `figma:d:${designMatch[1]}` : communityMatch ? `figma:c:${communityMatch[1]}` : `figma:${path}`;
      const fileName = stripSuffix(title, [' – Figma', ' - Figma', ' | Figma', ' (Community)', ' | BRIX Templates']);
      return { app: 'Figma', category: 'designing', itemKey: key, itemTitle: fileName || 'Design file', itemKind: communityMatch ? 'community' : 'file', itemMeta: communityMatch ? 'Community file' : 'Design file', matchType: 'host', matchValue: base };
    }

    if (base === 'forge.ui8.ai' || base === 'remix.ui8.ai') {
      const proj = stripSuffix(title, [' · Forge', ' - Forge', ' | Forge']);
      return { app: 'Forge', category: 'designing', itemKey: `forge:${path}`, itemTitle: proj || 'Interactive preview', itemKind: 'prototype', itemMeta: 'UI8 Forge', matchType: 'host', matchValue: base };
    }

    if (base === 'github.com') {
      const parts = path.split('/').filter(Boolean);
      if (parts.length >= 2) {
        const repo = `${parts[0]}/${parts[1]}`;
        if (parts[2] === 'issues' && parts[3]) {
          const issueTitle = stripSuffix(title, [` · Issue #${parts[3]} · ${repo}`, ' · GitHub']);
          return { app: 'GitHub', category: 'building', itemKey: `gh:${repo}#${parts[3]}`, itemTitle: `${repo} #${parts[3]}`, itemKind: 'issue', itemMeta: issueTitle.slice(0, 64), matchType: 'host', matchValue: base };
        }
        if (parts[2] === 'issues') {
          return { app: 'GitHub', category: 'building', itemKey: `gh:${repo}:issues`, itemTitle: `${repo} · Issues`, itemKind: 'issues', matchType: 'host', matchValue: base };
        }
        if (parts[2] === 'pull' && parts[3]) {
          return { app: 'GitHub', category: 'building', itemKey: `gh:${repo}!${parts[3]}`, itemTitle: `${repo} PR #${parts[3]}`, itemKind: 'pr', matchType: 'host', matchValue: base };
        }
        return { app: 'GitHub', category: 'building', itemKey: `gh:${repo}`, itemTitle: repo, itemKind: 'repo', itemMeta: parts[2] === 'tree' ? 'Code browser' : 'Repository', matchType: 'host', matchValue: base };
      }
      return { app: 'GitHub', category: 'building', itemKey: `gh:${path}`, itemTitle: stripSuffix(title, [' · GitHub', ' - GitHub']) || 'GitHub', itemKind: 'page', matchType: 'host', matchValue: base };
    }

    if (base === 'youtube.com' || base === 'm.youtube.com' || base === 'youtu.be') {
      const vid = urlObj.searchParams.get('v') || (base === 'youtu.be' ? path.slice(1) : '');
      const videoTitle = stripSuffix(title, [' - YouTube', ' · YouTube']);
      return { app: 'YouTube', category: 'watching', itemKey: vid ? `yt:${vid}` : `yt:${path}`, itemTitle: videoTitle || 'YouTube', itemKind: vid ? 'video' : 'feed', place: /^\/shorts(?:\/|$)/.test(path) ? { kind: 'shorts' } : null, matchType: 'host', matchValue: base };
    }

    if (base === 'twitch.tv') {
      const chan = path.split('/').filter(Boolean)[0];
      return { app: 'Twitch', category: 'watching', itemKey: `twitch:${chan || 'home'}`, itemTitle: chan ? `${chan} stream` : 'Twitch', itemKind: 'stream', matchType: 'host', matchValue: base };
    }

    if (base === 'open.spotify.com' || base === 'spotify.com') {
      const isPlayer = base === 'open.spotify.com';
      const cleaned = stripSuffix(title, [' | Spotify', ' - Spotify']);
      return { app: 'Spotify', category: 'listening', itemKey: isPlayer ? 'spotify:player' : `spotify:${path}`, itemTitle: isPlayer ? 'Web Player' : (cleaned || 'Spotify'), itemKind: 'audio', matchType: 'host', matchValue: base };
    }

    if (base === 'claude.ai') {
      return { app: 'Claude', category: 'building', itemKey: `claude:${path}`, itemTitle: stripSuffix(title, [' - Claude', ' | Claude']) || 'Chat session', itemKind: 'chat', matchType: 'host', matchValue: base };
    }
    if (base === 'chatgpt.com' || base === 'chat.openai.com') {
      return { app: 'ChatGPT', category: 'building', itemKey: `chatgpt:${path}`, itemTitle: stripSuffix(title, [' - ChatGPT', ' | ChatGPT']) || 'Chat session', itemKind: 'chat', matchType: 'host', matchValue: base };
    }

    if (base === 'mail.google.com') {
      const cleaned = title.replace(/\s+-\s+[^-\s]+@[^-\s]+\s+-\s+Gmail$/i, '').replace(/\s+-\s+Gmail$/i, '').trim();
      return { app: 'Gmail', category: 'messaging', itemKey: `gmail:${cleaned.toLowerCase() || path}`, itemTitle: cleaned || 'Inbox', itemKind: 'thread', matchType: 'host', matchValue: base };
    }

    if (base === 'web.whatsapp.com') {
      return { app: 'WhatsApp', category: 'messaging', itemKey: 'wa:web', itemTitle: 'WhatsApp Web', itemKind: 'chat', matchType: 'host', matchValue: base };
    }
    if (base === 'discord.com') {
      return { app: 'Discord', category: 'messaging', itemKey: `discord:${path}`, itemTitle: stripSuffix(title, [' | Discord']) || 'Discord', itemKind: 'chat', matchType: 'host', matchValue: base };
    }

    if (base === 'docs.google.com') {
      const isSheet = path.startsWith('/spreadsheets');
      const isSlide = path.startsWith('/presentation');
      const appName = isSheet ? 'Google Sheets' : isSlide ? 'Google Slides' : 'Google Docs';
      const docName = stripSuffix(title, [' - Google Docs', ' - Google Sheets', ' - Google Slides']);
      return { app: appName, category: 'writing', itemKey: `gdocs:${path.split('/').slice(0, 4).join('/')}`, itemTitle: docName || 'Document', itemKind: 'doc', matchType: 'host', matchValue: base };
    }

    if (base === 'google.com' && path.startsWith('/search')) {
      const q = urlObj.searchParams.get('q') || stripSuffix(title, [' - Google Search']);
      return { app: 'Google Search', category: 'browsing', itemKey: `gsearch:${(q || '').toLowerCase()}`, itemTitle: q || 'Search', itemKind: 'search', matchType: 'host', matchValue: base };
    }

    if (base === 'reddit.com' || base === 'old.reddit.com') {
      const sub = path.match(/^\/r\/([^/]+)/);
      const post = stripSuffix(title, [/\s*:\s*r\/[^/]+$/i, ' - Reddit']);
      return { app: 'Reddit', category: 'browsing', itemKey: `reddit:${path}`, itemTitle: sub ? `r/${sub[1]} · ${post}` : (post || 'Reddit'), itemKind: 'thread', matchType: 'host', matchValue: base };
    }

    if (base === 'support.google.com') {
      return { app: 'Google Support', category: 'browsing', itemKey: `gsupport:${path}`, itemTitle: stripSuffix(title, [' - Google Help', ' - Android Help']) || 'Help thread', itemKind: 'article', matchType: 'host', matchValue: base };
    }

    // Developer docs & engineering hubs
    if (
      host.endsWith('.console.aws.amazon.com') ||
      base === 'developer.chrome.com' ||
      base === 'developer.mozilla.org' ||
      base === 'stackoverflow.com' ||
      base === 'docs.rs' ||
      base === 'crates.io' ||
      base === 'npmjs.com' ||
      base === 'ui-sdk.dev'
    ) {
      const appMap = {
        'developer.chrome.com': 'Chrome Docs',
        'developer.mozilla.org': 'MDN',
        'stackoverflow.com': 'Stack Overflow',
        'docs.rs': 'docs.rs',
        'crates.io': 'crates.io',
        'npmjs.com': 'npm',
        'ui-sdk.dev': 'UI SDK',
      };
      const appName = host.endsWith('.console.aws.amazon.com') ? 'AWS Console' : (appMap[base] || prettyHost(host));
      return { app: appName, category: 'building', itemKey: `dev:${base}${path}`, itemTitle: title || appName, itemKind: 'docs', matchType: 'host', matchValue: base };
    }

    if (base === 'techradar.com' || base === 'play.google.com' || base === 'store.steampowered.com') {
      const appMap = { 'techradar.com': 'TechRadar', 'play.google.com': 'Google Play', 'store.steampowered.com': 'Steam' };
      return { app: appMap[base], category: 'browsing', itemKey: `web:${base}${path}`, itemTitle: stripSuffix(title, [' - Apps on Google Play', ' on Steam']) || appMap[base], itemKind: 'page', matchType: 'host', matchValue: base };
    }

    // Unknown website: stays honest as Uncategorized so Focus Ratio stays clean
    // until user clicks a quick-tag or an agent adds it to rules.json!
    return {
      app: prettyHost(host),
      category: audible ? 'watching' : 'unknown',
      itemKey: `web:${base}${path}`,
      itemTitle: title || base,
      itemKind: 'page',
      itemMeta: base,
      matchType: 'host',
      matchValue: base,
    };
  }

  function classifySpan(span, deviceKind = 'windows', userRules = null) {
    const rules = normalizeRuleList(userRules);
    const rawApp = String(span.app || '').trim();
    const rawLabel = String(span.label || rawApp || '').trim();
    const lowApp = rawApp.toLowerCase();
    const lowLabel = rawLabel.toLowerCase();

    // 1. Android Phone / Mobile browser domain span
    if (deviceKind === 'phone' || rawApp.startsWith('site:') || span.domain) {
      const dom = String(span.domain || (rawApp.startsWith('site:') ? rawApp.slice(5) : '')).trim().toLowerCase();
      if (dom) {
        const fakeUrl = safeUrl(`https://${dom}/`);
        if (fakeUrl) {
          const web = classifyWeb(fakeUrl, '', false);
          const res = {
            app: web.app,
            category: web.category,
            itemKey: null,
            itemTitle: null,
            itemKind: 'phone_web',
            noise: Boolean(web.noise),
            source: 'phone_browser',
            matchType: 'host',
            matchValue: web.matchValue || domainBase(dom),
          };
          const override = findUserRule(rules, { host: fakeUrl.hostname, app: res.app, label: rawLabel });
          return applyRuleOverride(res, override);
        }
      }
    }

    // 1b. Android TV or Phone app span
    if (deviceKind === 'tv' || deviceKind === 'phone' || (rawApp.includes('.') && !BROWSER_APPS.has(lowApp))) {
      const itemKind = deviceKind === 'phone' ? 'phone_app' : 'tv_app';
      const src = deviceKind === 'phone' ? 'phone' : 'tv';
      const pkg = TV_PACKAGES[rawApp] || TV_PACKAGES[lowApp];
      let res;
      if (pkg) {
        res = { app: pkg.app, category: pkg.category, itemKey: null, itemTitle: null, itemKind, noise: Boolean(pkg.noise), source: src, matchType: 'package', matchValue: rawApp };
      } else if (DESKTOP_APPS[lowLabel]) {
        const known = DESKTOP_APPS[lowLabel];
        res = { app: known.app, category: known.category, itemKey: null, itemTitle: null, itemKind, noise: false, source: src, matchType: 'package', matchValue: rawApp };
      } else {
        res = { app: rawLabel || rawApp, category: 'unknown', itemKey: null, itemTitle: null, itemKind, noise: false, source: src, matchType: 'package', matchValue: rawApp };
      }
      const legacyApp = lowApp === 'com.google.android.videos' ? 'play movies' : '';
      const override = findUserRule(rules, { pkg: rawApp, app: res.app, label: rawLabel, legacyApp });
      return applyRuleOverride(res, override);
    }

    // 2. System noise process check
    if (SYSTEM_NOISE_APPS.has(lowApp) || SYSTEM_NOISE_APPS.has(lowLabel)) {
      return { app: rawApp === 'FocusDNS' ? 'FocusDNS' : rawApp, category: 'system', itemKey: null, itemTitle: null, itemKind: 'os', noise: true, source: span.source || 'window_title', matchType: 'app', matchValue: rawApp };
    }

    // 3. Extension-observed browser span
    if (span.source === 'chromium_extension' && span.browser?.url) {
      const urlObj = safeUrl(span.browser.url);
      if (urlObj) {
        const web = classifyWeb(urlObj, span.browser.title || '', Boolean(span.browser.audible));
        const res = {
          app: web.app,
          category: web.category,
          itemKey: web.itemKey,
          itemTitle: web.itemTitle,
          itemKind: web.itemKind || 'page',
          itemMeta: web.itemMeta || '',
          place: web.place || null,
          lowQualityTitle: Boolean(web.lowQualityTitle),
          audible: Boolean(span.browser.audible),
          noise: Boolean(web.noise),
          source: 'chromium_extension',
          matchType: web.matchType || 'host',
          matchValue: web.matchValue || domainBase(urlObj.hostname),
        };
        const override = findUserRule(rules, { host: urlObj.hostname, app: res.app, label: rawLabel });
        return applyRuleOverride(res, override);
      }
    }

    // 4. Browser without rich URL (restricted tab, new tab, or coarse window_title)
    if (BROWSER_APPS.has(lowApp)) {
      if (lowLabel && lowLabel !== lowApp) {
        const known = DESKTOP_APPS[lowLabel];
        if (known) {
          const res = { app: known.app, category: known.category, itemKey: null, itemTitle: null, itemKind: 'web_coarse', noise: false, source: span.source || 'window_title', matchType: 'app', matchValue: known.app };
          const override = findUserRule(rules, { app: res.app, label: rawLabel });
          return applyRuleOverride(res, override);
        }
        if (lowLabel.length <= 253 && HOSTNAME.test(lowLabel)) {
          const fakeUrl = safeUrl(`https://${rawLabel}/`);
          if (fakeUrl) {
            const web = classifyWeb(fakeUrl, '', false);
            const res = { app: web.app, category: web.category, itemKey: null, itemTitle: null, itemKind: 'web_coarse', noise: Boolean(web.noise), source: span.source || 'window_title', matchType: 'host', matchValue: web.matchValue };
            const override = findUserRule(rules, { host: fakeUrl.hostname, app: res.app, label: rawLabel });
            return applyRuleOverride(res, override);
          }
        }
        const res = { app: rawLabel, category: 'unknown', itemKey: null, itemTitle: null, itemKind: 'web_coarse', noise: false, source: span.source || 'window_title', matchType: 'app', matchValue: rawLabel };
        const override = findUserRule(rules, { app: res.app, label: rawLabel });
        return applyRuleOverride(res, override);
      }
      const res = { app: rawApp, category: 'browsing', itemKey: null, itemTitle: null, itemKind: 'browser_untracked', noise: false, source: span.source || 'window_title', matchType: 'app', matchValue: rawApp };
      const override = findUserRule(rules, { app: res.app, label: rawLabel });
      return applyRuleOverride(res, override);
    }

    // 5. Desktop app catalog lookup
    const known = DESKTOP_APPS[lowApp] || DESKTOP_APPS[lowLabel];
    if (known) {
      const res = { app: known.app, category: known.category, itemKey: null, itemTitle: null, itemKind: 'desktop_app', noise: false, source: span.source || 'window_title', matchType: 'app', matchValue: rawApp };
      const override = findUserRule(rules, { app: res.app, label: rawApp });
      return applyRuleOverride(res, override);
    }

    // 6. Unknown desktop app -> Uncategorized
    const res = { app: rawLabel || rawApp || 'Unknown', category: 'unknown', itemKey: null, itemTitle: null, itemKind: 'desktop_app', noise: false, source: span.source || 'window_title', matchType: 'app', matchValue: rawApp || rawLabel };
    const override = findUserRule(rules, { app: res.app, label: rawApp });
    return applyRuleOverride(res, override);
  }

  function overlap(a, b, x, y) {
    return Math.max(0, Math.min(b, y) - Math.max(a, x));
  }

  function summarizeClassified(sources, from, to, { includeNoise = false, userRules = null } = {}) {
    const categories = Object.create(null);
    const apps = Object.create(null);
    let totalActive = 0;
    let totalOff = 0;
    let totalNoise = 0;
    let creatingMs = 0;
    let consumingMs = 0;
    let utilitiesMs = 0;
    let learningMs = 0;
    let unknownMs = 0;
    let offTotal = 0;

    for (const src of sources) {
      const h = src.history;
      const boundTo = Math.min(to, h.now || to);
      const offs = (h.pauses || []).map((p) => [p.t, Math.min(p.end, boundTo)]);
      const offDuring = (a, b) => offs.reduce((n, [x, y]) => n + overlap(a, b, x, y), 0);
      offTotal += offs.reduce((n, [x, y]) => n + overlap(x, y, from, boundTo), 0);

      for (const s of h.spans || []) {
        if (s.label === 'FocusDNS') continue;
        const a = Math.max(s.s, from);
        const b = Math.min(s.e, boundTo);
        if (b <= a) continue;
        const d = b - a;
        const o = offDuring(a, b);
        const c = classifySpan(s, src.kind, userRules);

        if (c.noise) {
          totalNoise += d;
          if (!includeNoise) continue;
        }

        totalActive += d;
        totalOff += o;

        const catMeta = CATEGORY_MAP[c.category] || CATEGORY_MAP.unknown;
        if (catMeta.group === 'creating') creatingMs += d;
        else if (catMeta.group === 'consuming') consumingMs += d;
        else if (catMeta.group === 'other') utilitiesMs += d;
        else if (catMeta.group === 'learn') learningMs += d;
        else unknownMs += d;

        const cat = (categories[catMeta.id] ??= {
          id: catMeta.id,
          label: catMeta.label,
          group: catMeta.group,
          order: catMeta.order,
          ms: 0,
          off: 0,
          apps: Object.create(null),
          devices: new Set(),
        });
        cat.ms += d;
        cat.off += o;
        cat.devices.add(src.name);

        const appKey = `${catMeta.id}::${c.app}`;
        const appEntry = (apps[appKey] ??= {
          app: c.app,
          category: catMeta.id,
          categoryLabel: catMeta.label,
          group: catMeta.group,
          matchType: c.matchType || 'app',
          matchValue: c.matchValue || c.app,
          ms: 0,
          off: 0,
          audibleMs: 0,
          devices: Object.create(null),
          items: Object.create(null),
          untrackedMs: 0,
        });
        appEntry.ms += d;
        appEntry.off += o;
        if (c.audible) appEntry.audibleMs += d;
        appEntry.devices[src.name] = (appEntry.devices[src.name] || 0) + d;
        cat.apps[appKey] = appEntry;

        if (c.itemKey && c.itemTitle) {
          const item = (appEntry.items[c.itemKey] ??= {
            key: c.itemKey,
            title: c.itemTitle,
            kind: c.itemKind,
            meta: c.itemMeta || '',
            ms: 0,
            off: 0,
            last: 0,
            lowQuality: Boolean(c.lowQualityTitle),
          });
          item.ms += d;
          item.off += o;
          const shouldReplace =
            (item.lowQuality && !c.lowQualityTitle) ||
            (s.e >= item.last && !(c.lowQualityTitle && !item.lowQuality));
          if (shouldReplace) {
            item.title = c.itemTitle;
            item.meta = c.itemMeta || item.meta;
            item.last = s.e;
            item.lowQuality = Boolean(c.lowQualityTitle);
          }
        } else if (src.kind === 'windows' && c.itemKind === 'browser_untracked') {
          appEntry.untrackedMs += d;
        }
      }
    }

    const finalizeApp = (a) => ({
      app: a.app,
      category: a.category,
      categoryLabel: a.categoryLabel,
      group: a.group,
      matchType: a.matchType,
      matchValue: a.matchValue,
      ms: a.ms,
      off: a.off,
      audibleMs: a.audibleMs,
      devices: Object.entries(a.devices)
        .map(([name, ms]) => ({ name, ms }))
        .sort((x, y) => y.ms - x.ms),
      items: Object.values(a.items)
        .map(({ key, title, kind, meta, ms, off }) => ({ key, title, kind, meta, ms, off }))
        .sort((x, y) => y.ms - x.ms),
      untrackedMs: a.untrackedMs,
    });

    const appList = Object.values(apps).map(finalizeApp).sort((x, y) => y.ms - x.ms);
    const categoryList = Object.values(categories)
      .map((cat) => ({
        id: cat.id,
        label: cat.label,
        group: cat.group,
        order: cat.order,
        ms: cat.ms,
        off: cat.off,
        share: totalActive ? cat.ms / totalActive : 0,
        devices: [...cat.devices],
        apps: Object.values(cat.apps).map(finalizeApp).sort((x, y) => y.ms - x.ms),
      }))
      .sort((x, y) => y.ms - x.ms);

    const ratioDenom = creatingMs + consumingMs;
    const creatingPct = ratioDenom ? Math.round((creatingMs / ratioDenom) * 100) : 0;
    const consumingPct = ratioDenom ? 100 - creatingPct : 0;

    return {
      active: totalActive,
      activeOff: totalOff,
      off: offTotal,
      noiseMs: totalNoise,
      creatingMs,
      consumingMs,
      utilitiesMs,
      learningMs,
      unknownMs,
      ratioDenom,
      creatingPct,
      consumingPct,
      creating: appList.filter((a) => a.group === 'creating'),
      consuming: appList.filter((a) => a.group === 'consuming'),
      utilities: appList.filter((a) => a.group === 'other'),
      uncategorized: appList.filter((a) => a.group === 'unknown'),
      categories: categoryList,
      apps: appList,
    };
  }

  function reasonMentions(reason, appName) {
    const original = ` ${String(reason || '').toLowerCase()} `;
    const clean = String(appName || '').toLowerCase().replace(/\s*\(.+\)$/, '').trim();
    if (!clean) return false;
    if (original.includes(clean)) return true;
    if (PLAY_MOVIES.test(original)) return clean === 'google tv';
    const base = clean.replace(/^www\./, '').split('.')[0];
    if (clean.includes('.') && base.length > 2 && original.includes(base)) return true;
    const hints = PAUSE_HINTS[clean] || [];
    return hints.some((h) => original.includes(h));
  }

  // Reasons often name an activity instead of an app ("browse a little", "watch some tv").
  const CATEGORY_HINTS = {
    browsing: ['browse', 'browsing', 'scroll', 'search', 'look up', 'website'],
    watching: ['watch', 'video', 'tv', 'movie', 'show', 'episode', 'stream'],
    messaging: ['message', 'chat', 'reply', 'text', 'dm'],
    listening: ['music', 'song', 'listen', 'podcast'],
  };
  function reasonMentionsCategory(reason, category) {
    const r = ` ${String(reason || '').toLowerCase()} `;
    if (category === 'watching' && PLAY_MOVIES.test(r)) return false;
    return (CATEGORY_HINTS[category] || []).some((h) => new RegExp(`\\b${h}`).test(r));
  }

  /** Classify what happened during one pause, separating matched, creating (work), and drift. */
  function classifyPause(sourceHistory, pause, deviceKind = 'windows', userRules = null) {
    const end = Math.min(pause.end, sourceHistory.now || pause.end);
    const byApp = Object.create(null);
    for (const s of sourceHistory.spans || []) {
      if (s.label === 'FocusDNS') continue;
      const d = overlap(s.s, s.e, pause.t, end);
      if (!d) continue;
      const c = classifySpan(s, deviceKind, userRules);
      if (c.noise) continue;
      const display = (c.app === 'X' && c.itemKind === 'profile' && c.itemMeta) ? `X (${c.itemMeta})` : c.app;
      const entry = (byApp[display] ??= { app: display, category: c.category, group: (CATEGORY_MAP[c.category] || CATEGORY_MAP.unknown).group, ms: 0 });
      entry.ms += d;
    }
    const items = Object.values(byApp)
      .filter((x) => x.ms >= 18e3)
      .sort((a, b) => b.ms - a.ms)
      .slice(0, 3)
      .map((x) => {
        const matched = reasonMentions(pause.reason, x.app) || reasonMentionsCategory(pause.reason, x.category);
        // Unknown apps are not evidence of drift; only recognized consuming apps are.
        const isWork = x.group !== 'consuming';
        const status = matched ? 'matched' : isWork ? 'work' : 'drift';
        return { ...x, matched, status };
      });
    // Drift = one recognized consuming app the reason didn't mention, used for at least 2 minutes.
    const hasDrift = items.some((x) => x.status === 'drift' && x.ms >= 2 * MIN);
    return { items, hasDrift };
  }

  /** 5-minute buckets across 24h for the F2 timeline: creating, consuming, off, away, future. */
  function dayStripClassified(sources, day, userRules = null) {
    const B = 5 * MIN;
    const now = Math.max(...sources.map((s) => s.history.now || 0), 0);
    const out = [];
    for (let t = day; t < day + DAY; t += B) {
      const bEnd = t + B;
      let st = 'away';
      let top = '';
      if (t >= now) {
        st = 'future';
      } else {
        let off = false;
        let createMs = 0;
        let consumeMs = 0;
        let otherMs = 0;
        const appMs = Object.create(null);
        for (const src of sources) {
          const h = src.history;
          if ((h.pauses || []).some((p) => overlap(p.t, Math.min(p.end, h.now), t, bEnd) > 0)) off = true;
          for (const s of h.spans || []) {
            if (s.label === 'FocusDNS') continue;
            const d = overlap(s.s, s.e, t, bEnd);
            if (!d) continue;
            const c = classifySpan(s, src.kind, userRules);
            if (c.noise) continue;
            const g = (CATEGORY_MAP[c.category] || CATEGORY_MAP.unknown).group;
            if (g === 'creating') createMs += d;
            else if (g === 'consuming') consumeMs += d;
            else otherMs += d;
            appMs[c.app] = (appMs[c.app] || 0) + d;
          }
        }
        const tot = createMs + consumeMs + otherMs;
        if (off) {
          st = 'off';
        } else if (tot >= B / 2) {
          st = createMs >= consumeMs ? 'creating' : 'consuming';
        }
        const best = Object.entries(appMs).sort((a, b) => b[1] - a[1])[0];
        top = best ? best[0] : '';
      }
      const last = out[out.length - 1];
      if (last && last.st === st) {
        last.n++;
        if (!last.top && top) last.top = top;
      } else {
        out.push({ st, n: 1, t, top });
      }
    }
    return out;
  }

  // Fine categories: the breakdown shown in the panel. Coarse V1 categories above still drive
  // pause audits and the day strip. `categories.json` (written by Jev or an agent) refines them.
  const FINE = [
    { id: 'coding', label: 'Coding', group: 'create', color: '#d4d4d8' },
    { id: 'business', label: 'Business', group: 'create', color: '#c4c4ca' },
    { id: 'writing', label: 'Writing', group: 'create', color: '#bdbdc4' },
    { id: 'design', label: 'Design', group: 'create', color: '#a8a8b0' },
    { id: 'learning', label: 'Learning', group: 'learn', color: '#a1a1aa' },
    { id: 'reading', label: 'Reading', group: 'learn', color: '#8f8f98' },
    { id: 'messaging', label: 'Messaging', group: 'connect', color: '#71717a' },
    { id: 'social', label: 'Social feeds', group: 'drift', color: '#f59e0b' },
    { id: 'video', label: 'Videos', group: 'drift', color: '#e58e26' },
    { id: 'tv', label: 'TV & streams', group: 'drift', color: '#d97706' },
    { id: 'music', label: 'Music', group: 'neutral', color: '#63636c' },
    { id: 'games', label: 'Games', group: 'drift', color: '#ea580c' },
    { id: 'news', label: 'News', group: 'drift', color: '#71717a' },
    { id: 'shopping', label: 'Shopping', group: 'drift', color: '#71717a' },
    { id: 'web', label: 'Web', group: 'neutral', color: '#52525b' },
    { id: 'adult', label: 'Adult', group: 'drift', color: '#ef4444' },
    { id: 'errands', label: 'Errands', group: 'neutral', color: '#52525b' },
    { id: 'unsorted', label: 'Not sure', group: 'neutral', color: '#3f3f46' },
  ];
  const FINE_MAP = Object.fromEntries(FINE.map((c) => [c.id, c]));
  const SOCIAL_APPS = new Set(['x', 'reddit', 'instagram', 'facebook', 'tiktok', 'threads', 'bluesky', 'linkedin', 'hacker news']);

  function fineKey(c) {
    return c.itemTitle ? `item:${c.app}|${c.itemTitle}` : null;
  }

  /** Fine category for a classified span: categories.json first, then a fallback from the V1 verb. */
  function fineCategory(span, c, deviceKind, cache) {
    if (/\.pdf($|[?#])/i.test(span.browser?.url || '')) return { id: 'reading', app: 'PDF files' };
    if (c.itemKind === 'browser_untracked') return { id: 'unsorted', app: c.app };
    if (CATEGORY_MAP[c.category]?.group === 'learn') return { id: c.category, app: c.app };
    const items = cache?.items || {};
    const hit = (fineKey(c) && items[fineKey(c)]) || items[`app:${c.app}`];
    if (hit && FINE_MAP[hit]) return { id: deviceKind === 'tv' && hit === 'video' ? 'tv' : hit, app: c.app };
    const fallback = {
      building: 'coding', writing: 'writing', designing: 'design', messaging: 'messaging', listening: 'music',
      system: 'errands', unknown: 'unsorted',
      watching: deviceKind === 'tv' ? 'tv' : 'video',
      browsing: SOCIAL_APPS.has(String(c.app).toLowerCase()) ? 'social' : 'web',
    }[c.category] || 'unsorted';
    return { id: fallback, app: c.app };
  }

  function mergeIntervals(intervals) {
    const valid = intervals.filter(([x, y]) => y > x).sort((a, b) => a[0] - b[0]);
    if (!valid.length) return [];
    const merged = [valid[0].slice()];
    for (let i = 1; i < valid.length; i++) {
      const [x, y] = valid[i];
      const last = merged[merged.length - 1];
      if (x <= last[1]) last[1] = Math.max(last[1], y);
      else merged.push([x, y]);
    }
    return merged;
  }

  function cleanBucket(classification, fine) {
    const { app, place, category, userRule } = classification;
    if (place?.kind === 'home' || place?.kind === 'shorts') return 'drift';
    if (['X', 'WhatsApp', 'WhatsApp Business', 'YouTube'].includes(app)) {
      if (place?.kind === 'search' || place?.kind === 'profile') return 'clean';
      if (!userRule) return 'unverified';
    }
    if (['building', 'writing', 'designing', 'learning', 'reading', 'listening', 'messaging'].includes(category)) return 'clean';
    if (['games', 'adult'].includes(fine.id)) return 'drift';
    return 'unverified';
  }

  /** Screen time per fine category and per app, across all selected devices. */
  function summarizeFine(sources, from, to, { userRules = null, cache = null } = {}) {
    const cats = Object.create(null);
    const catOff = Object.create(null);
    const apps = Object.create(null);
    let total = 0;
    let totalOff = 0;
    const score = { clean: 0, drift: 0, unverified: 0 };
    for (const src of sources) {
      let lastPlace = null;
      const h = src.history;
      const boundTo = Math.min(to, h.now || to);
      const offs = mergeIntervals((h.pauses || []).map((p) => [p.t, Math.min(p.end, boundTo)]));
      const offDuring = (a, b) => offs.reduce((n, [x, y]) => n + overlap(a, b, x, y), 0);
      for (const s of h.spans || []) {
        if (s.label === 'FocusDNS') continue;
        const a = Math.max(s.s, from);
        const b = Math.min(s.e, boundTo);
        if (b <= a) continue;
        const c = classifySpan(s, src.kind, userRules);
        const f = fineCategory(s, c, src.kind, cache);
        // An "ignore" rule must not hide a site the cache explicitly categorizes (no silent deletions).
        const explicit = cache?.items?.[`app:${c.app}`];
        if (c.noise && !explicit) continue;
        const d = b - a;
        const o = Math.min(d, offDuring(a, b));
        total += d;
        totalOff += o;
        cats[f.id] = (cats[f.id] || 0) + d;
        catOff[f.id] = (catOff[f.id] || 0) + o;
        const entry = (apps[f.app] ??= { app: f.app, ms: 0, off: 0, cats: Object.create(null), devices: Object.create(null), items: Object.create(null), webMs: 0, driftMs: 0, details: null });
        // Website = seen as a URL/domain; native apps and desktop programs are not.
        if (c.matchType === 'host' || s.domain || (s.source === 'chromium_extension' && s.browser?.url)) entry.webMs += d;
        entry.ms += d;
        entry.off += o;
        entry.cats[f.id] = (entry.cats[f.id] || 0) + d;
        const dev = src.kind === 'phone' ? 'Phone' : src.kind === 'tv' ? 'TV' : src.name;
        entry.devices[dev] = (entry.devices[dev] || 0) + d;
        const bucket = cleanBucket(c, f);
        score[bucket] += d;
        if (bucket === 'drift') entry.driftMs += d;
        if (f.app === 'X' && c.place && ['home', 'search', 'profile'].includes(c.place.kind)) {
          const { kind, label, handle } = c.place;
          const details = (entry.details ??= { coveredMs: 0, homeMs: 0, search: new Map(), profile: new Map() });
          details.coveredMs += d;
          if (kind === 'home') {
            details.homeMs += d;
            lastPlace = null;
          } else {
            const key = handle || label;
            const item = details[kind].get(key) || { label, handle, visits: 0 };
            const browser = s.browser;
            const session = `${browser.host_pid}:${browser.window_id}:${browser.tab_id}`;
            if (lastPlace?.kind !== kind || lastPlace.key !== key || lastPlace.end !== a || lastPlace.session !== session) item.visits++;
            details[kind].set(key, item);
            lastPlace = { kind, key, end: b, session };
          }
        } else {
          lastPlace = null;
        }
        if (c.itemTitle) {
          const item = (entry.items[c.itemTitle] ??= { title: c.itemTitle, ms: 0, off: 0 });
          item.ms += d;
          item.off += o;
        }
      }
    }
    const top = (o) => Object.entries(o).sort((x, y) => y[1] - x[1]);
    const catList = top(cats).map(([id, ms]) => ({ ...FINE_MAP[id], ms, off: catOff[id] || 0 }));
    const groups = Object.create(null);
    for (const c of catList) groups[c.group] = (groups[c.group] || 0) + c.ms;
    const appList = Object.values(apps)
      .map((e) => {
        const cat = FINE_MAP[top(e.cats)[0][0]];
        return {
          app: e.app,
          ms: e.ms,
          off: e.off,
          offRatio: e.ms ? Math.min(1, e.off / e.ms) : 0,
          category: cat.id,
          categoryLabel: cat.label,
          group: cat.group,
          color: cat.color,
          devices: top(e.devices).map(([name, ms]) => ({ name, ms })),
          web: e.webMs >= e.ms * 0.9,
          details: e.details,
          driftMs: e.driftMs,
          items: Object.values(e.items).sort((x, y) => y.ms - x.ms),
        };
      })
      .sort((x, y) => y.ms - x.ms);
    return { total, totalOff, categories: catList, groups, apps: appList, score };
  }

  /** Build the compact agent-readable inbox written to %LOCALAPPDATA%\FocusDNS\uncategorized.json. */
  function buildUncategorizedInbox(summary, now = Date.now()) {
    const items = (summary.uncategorized || [])
      .filter((a) => a.ms >= 15e3)
      .slice(0, 50)
      .map((a) => ({
        match: a.matchType,
        value: a.matchValue,
        app: a.app,
        ms: a.ms,
        minutes: Math.round((a.ms / MIN) * 10) / 10,
        devices: a.devices.map((d) => d.name),
        sample_titles: a.items.slice(0, 3).map((i) => i.title),
      }));
    return {
      version: 1,
      updated_at: now,
      hint: 'Add matching entries to rules.json in this folder with category: building | writing | designing | watching | browsing | messaging | listening | reading | learning | system | ignore.',
      items,
    };
  }

  return {
    CATEGORIES,
    CATEGORY_MAP,
    VALID_RULE_CATEGORIES,
    classifySpan,
    summarizeClassified,
    classifyPause,
    dayStripClassified,
    upsertUserRule,
    buildUncategorizedInbox,
    FINE,
    fineCategory,
    summarizeFine,
  };
})();

if (typeof window !== 'undefined') window.ActivityClassifier = ActivityClassifier;
export default ActivityClassifier;
