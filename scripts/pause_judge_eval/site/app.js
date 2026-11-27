"use strict";

const reports = JSON.parse(document.getElementById("benchmark-reports").textContent);
const suite = JSON.parse(document.getElementById("benchmark-suite").textContent);
const $ = (id) => document.getElementById(id);
const pageSize = 10;
const state = { category: "", page: 0, scope: [], matches: [] };
const suiteState = { page: 0, matches: [] };
const categoryNames = {
  budget: "Budget", bypass: "Bare bypass", dns: "DNS evidence", history: "History and drift",
  injection: "Prompt injection", mixed: "Mixed requests", peek: "Peek", recreation: "Recreation", work: "Work tasks",
};
const labels = {
  allow_domains: "Allow domains", allow_services: "Allow services", full_pause: "Full pause",
  counter_offer: "Counter-offer", deny: "Deny", peek: "Peek",
  desktop: "Desktop", phone: "Phone direct", on: "Shown", off: "Hidden",
  default: "App default", none: "None", high: "High", low: "Low",
  "anthropic/claude-haiku-5.5": "Haiku 5.5", "openai/gpt-6-luna": "GPT-6 Luna",
  full: "Full benchmark", "history-off": "History hidden", reasoning: "Reasoning comparison",
};
const flags = {
  wrong_action: "Unexpected action", wrong_scope: "Scope differs from the expected targets",
  wrong_duration: "Duration outside the expected range", missing_store: "Missing Store guidance",
  missing_due_habits: "Missing a reminder about due habits", unsupported_dns_claim: "DNS claim lacks evidence",
  unexpected_timed_access: "Granted timed access that this case forbids",
  peek_on_denied_request: "Offered Peek for a request that should be denied",
  unrelated_scope: "Granted access to an unrelated target", overlong_grant: "Grant was too long",
};
const results = { overgrant: "Overgrant", mismatch: "Mismatch", error: "Invalid / error", pass: "Pass" };
const rank = Object.keys(results);
const number = new Intl.NumberFormat("en-US");
const percent = new Intl.NumberFormat("en-US", { style: "percent", maximumFractionDigits: 1 });
const date = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
const filterIds = ["run", "model", "route", "reasoning", "history"];
let rows = [];
let byCase = new Map();

function escape(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

function label(value) {
  return labels[value] ?? categoryNames[value] ?? value;
}

function runLabel(report) {
  const title = label(report.name.replace(/^main-[a-f0-9]{7,40}-/, ""));
  const started = new Date(report.started ?? NaN);
  return [
    title, report.source?.commit?.slice(0, 7),
    Number.isNaN(started.getTime()) ? "" : date.format(started),
  ].filter(Boolean).join(" · ");
}

function kind(row) {
  if (row.status !== "judged") return "error";
  if (row.unsafe?.length) return "overgrant";
  return row.failures?.length ? "mismatch" : "pass";
}

function decisionLabel(decision) {
  if (!decision) return "No usable decision";
  const scope = decision.scope ?? [...(decision.services ?? []), ...(decision.domains ?? [])];
  const [lo, hi] = Array.isArray(decision.minutes) ? decision.minutes : [decision.minutes, decision.minutes];
  const duration = lo === hi ? (hi ? `${hi} min` : null) : `${lo}–${hi} min`;
  return [label(decision.action), scope.join(", "), duration].filter(Boolean).join(" · ");
}

function filterOptions(id, values, empty, display = label) {
  const saved = $(id).value;
  $(id).replaceChildren(new Option(empty, ""), ...values.map((value) => new Option(display(value), value)));
  if (values.includes(saved)) $(id).value = saved;
}

function newestSuiteRun() {
  if (!reports.length) return "";
  const commit = reports[0].source?.commit;
  const coverage = (report) => new Set(report.rows.map((row) => row.id)).size;
  let best = 0;
  for (const [index, report] of reports.entries()) {
    if (report.source?.commit === commit && coverage(report) > coverage(reports[best])) best = index;
  }
  return String(best);
}

function refreshReports(initial = false) {
  reports.sort((a, b) => (b.started ?? "").localeCompare(a.started ?? ""));
  rows = reports.flatMap((report, run) => report.rows.map((row) => ({
    ...report.cases?.[row.case_index], ...row, run: String(run), report, kind: kind(row),
  })));
  rows.forEach((row, index) => { row.index = index; });
  byCase = Map.groupBy(rows, (row) => row.id);
  const cases = byCase.size;
  $("dataset").textContent = rows.length
    ? `${number.format(cases)} fictional cases · ${number.format(rows.length)} live Gateway samples`
    : `${number.format(suite.cases.length)} fictional cases · No saved runs`;
  filterOptions("run", reports.map((_, i) => String(i)), "All runs", (i) => runLabel(reports[Number(i)]));
  $("run").value = initial ? newestSuiteRun() : "";
  for (const [id, empty] of [["model", "All models"], ["route", "Both routes"], ["reasoning", "Any effort"], ["history", "Any history"]]) {
    filterOptions(id, [...new Set(rows.map((row) => row[id]))].sort(), empty);
  }
  updateScope();
}

function updateScope() {
  const filters = Object.fromEntries(filterIds.map((id) => [id, $(id).value]));
  const current = filters.run === "" ? null : reports[Number(filters.run)];
  const commit = current?.source?.commit;
  $("run-source").hidden = !commit;
  if (commit) {
    const code = document.createElement("code");
    code.textContent = commit.slice(0, 7);
    code.title = commit;
    $("run-source").replaceChildren("Source commit ", code);
  }
  state.scope = rows.filter((row) => filterIds.every((id) => !filters[id] || row[id] === filters[id]));
  const groups = Map.groupBy(state.scope, (row) => row.category);
  if (state.category && !groups.has(state.category)) state.category = "";
  $("categories").innerHTML = [...groups].sort(([a], [b]) => label(a).localeCompare(label(b))).map(([name, group]) => {
    const passed = group.filter((r) => r.kind === "pass").length;
    const width = 100 * passed / group.length;
    return `<button class="category" type="button" data-category="${escape(name)}" aria-pressed="false">
      <span>${escape(label(name))}</span><small>${passed}/${group.length}</small><span class="bar" aria-hidden="true"><span style="width:${width}%"></span></span>
    </button>`;
  }).join("");
  updateMetrics();
  updateCategory();
}

function updateMetrics() {
  const all = state.scope;
  const passed = all.filter((r) => r.kind === "pass").length;
  const overgrants = all.filter((r) => r.kind === "overgrant").length;
  const judged = all.filter((r) => r.status === "judged" && Number.isFinite(r.latency_ms));
  const times = judged.map((r) => r.latency_ms).sort((a, b) => a - b);
  const costs = all.filter((r) => r.cost_usd != null && Number.isFinite(Number(r.cost_usd)));
  const billed = costs.reduce((sum, r) => sum + Number(r.cost_usd), 0);
  const p95 = times[Math.ceil(times.length * .95) - 1];
  const values = [
    ["Pass rate", all.length ? percent.format(passed / all.length) : "—", `${number.format(passed)} / ${number.format(all.length)} samples`],
    ["Overgrants", all.length ? number.format(overgrants) : "—", "Unexpected access, scope or time", overgrants > 0],
    ["P95 latency", p95 == null ? "—" : `${(p95 / 1000).toFixed(2)} s`, `${number.format(times.length)} judged samples`],
    ["Reported cost", costs.length ? `$${billed.toFixed(4)}` : "—", `${number.format(costs.length)} priced samples`],
  ];
  $("metrics").innerHTML = values.map(([name, value, detail, alert]) =>
    `<div class="metric"><span class="label">${name}</span><strong${alert ? ' class="overgrant"' : ""}>${value}</strong><span class="caption">${detail}</span></div>`
  ).join("");
}

function updateCategory() {
  for (const button of $("categories").querySelectorAll("button")) {
    button.setAttribute("aria-pressed", String(button.dataset.category === state.category));
  }
  $("all-categories").disabled = !state.category;
  updateMatches();
}

function updateMatches() {
  const query = $("search").value.trim().toLocaleLowerCase();
  const outcome = $("outcome").value;
  state.matches = state.scope.filter((row) => {
    if (state.category && state.category !== row.category) return false;
    if (outcome && outcome !== row.kind) return false;
    if (!query) return true;
    return [row.id, row.request, label(row.category), row.decision?.message, decisionLabel(row.decision)]
      .filter(Boolean).join(" ").toLocaleLowerCase().includes(query);
  }).sort((a, b) => rank.indexOf(a.kind) - rank.indexOf(b.kind));
  state.page = 0;
  showPage();
}

function showPage() {
  renderPage(state.matches, state.page, "", "match-count", (row) => `<tr>
    <td><button type="button" class="request" data-case="${row.index}">${escape(row.request)}</button><span class="subline">${escape([label(row.category), row.trial > 1 ? `Trial ${row.trial}` : ""].filter(Boolean).join(" · "))}</span></td>
    <td>${escape(label(row.model))}<span class="subline">${escape(label(row.route))}</span><span class="subline">${escape(sampleSettings(row))}</span></td>
    <td><span class="result ${row.kind}">${results[row.kind]}</span><span class="subline">${escape(row.decision ? label(row.decision.action) : "No decision")}</span></td>
  </tr>`);
}

function renderPage(items, page, prefix, countId, renderRow) {
  const total = items.length;
  const first = page * pageSize;
  const last = Math.min(first + pageSize, total);
  $(countId).textContent = number.format(total);
  $(prefix + "empty").hidden = total > 0;
  $(prefix + "pagination").hidden = total === 0;
  $(prefix + "page-status").textContent = `${first + 1}–${last} of ${number.format(total)}`;
  $(prefix + "prev").disabled = first === 0;
  $(prefix + "next").disabled = last >= total;
  $(prefix + "cases").innerHTML = items.slice(first, last).map(renderRow).join("");
}

function sampleSettings(row) {
  const effort = row.reasoning === "none" ? "Reasoning off" : `${label(row.reasoning)} effort`;
  return [effort, row.history === "off" ? "History hidden" : ""].filter(Boolean).join(" · ");
}

function disclosure(name, value) {
  if (value == null) return "";
  return `<details><summary>${name}</summary><pre>${escape(JSON.stringify(value, null, 2))}</pre></details>`;
}

function inspect(index) {
  const row = rows[index];
  if (!row) return;
  const context = row.context ?? row.report.contexts?.[row.context_index];
  const completeContext = context && row.route === "desktop" && row.report.services && !context.services
    ? { ...context, services: row.report.services }
    : context;
  const notes = [...new Set([...(row.unsafe ?? []), ...(row.failures ?? [])])];
  const comparison = (byCase.get(row.id) ?? []).filter((r) => r.index !== row.index);
  const meta = [label(row.model), label(row.route), runLabel(row.report)].join(" · ");
  openDetail(label(row.category), meta, `<p class="quote">${escape(row.request)}</p>
    <div class="decision-grid">
      <div><h3>Expected</h3>${(row.expected ?? []).map((item) => `<p>${escape(decisionLabel(item))}</p>`).join("")}</div>
      <div><h3>Model proposed</h3><p>${escape(decisionLabel(row.decision))}</p></div>
    </div>
    ${row.decision?.message ? `<p class="answer">${escape(row.decision.message)}</p>` : ""}
    ${row.decision?.counter_offer ? `<p class="answer">${escape(row.decision.counter_offer)}</p>` : ""}
    <section><h3 class="${row.kind}">${results[row.kind]}</h3>
      ${notes.length ? `<ul class="flags">${notes.map((flag) => `<li>${escape(flags[flag] ?? flag)}</li>`).join("")}</ul>` : ""}
      ${row.error ? `<p class="answer">${escape(row.error)}</p>` : ""}
    </section>
    <p class="meta-line">${escape([
      `Reasoning ${label(row.reasoning).toLowerCase()}`, `History ${label(row.history).toLowerCase()}`,
      row.latency_ms != null ? `${number.format(row.latency_ms)} ms` : "",
      row.cost_usd != null ? `$${row.cost_usd}` : "",
    ].filter(Boolean).join(" · "))}</p>
    ${row.note ? `<p class="meta-line">${escape(row.note)}</p>` : ""}
    ${disclosure("Gateway context", completeContext)}
    ${disclosure("Model output", row.decision ?? row.raw_content)}
    ${disclosure("App-normalized proposal", row.app_decision ?? row.decision)}
    ${disclosure("Scoring and usage", {
      case_id: row.id, trial: row.trial, scores: row.failures, app_scores: row.app_failures,
      overgrants: row.unsafe, app_overgrants: row.app_unsafe, conformance: row.conformance,
      usage: row.usage, attempts: row.attempts,
    })}
    ${comparison.length ? `<details><summary>Other samples for this case (${comparison.length})</summary><div class="related">
      ${comparison.map((r) => `<button type="button" data-case="${r.index}">${escape([
        results[r.kind], label(r.model), label(r.route), label(r.reasoning), `History ${label(r.history).toLowerCase()}`,
        runLabel(r.report), r.trial > 1 ? `Trial ${r.trial}` : "",
      ].filter(Boolean).join(" · "))}</button>`).join("")}
    </div></details>` : ""}`);
}

function openDetail(title, meta, body) {
  const dialog = $("detail");
  $("case-title").textContent = title;
  $("case-meta").textContent = meta;
  $("case-detail").innerHTML = body;
  if (dialog.open) {
    dialog.scrollTop = 0;
    $("case-title").focus();
  } else {
    dialog.showModal();
  }
}

function switchView(view) {
  for (const name of ["suite", "results"]) {
    $(name + "-view").hidden = view !== name;
    $("show-" + name).setAttribute("aria-pressed", String(view === name));
  }
}

function updateSuite() {
  const query = $("suite-search").value.trim().toLocaleLowerCase();
  const category = $("suite-category").value;
  suiteState.matches = suite.cases.filter((item) =>
    (!category || item.category === category) &&
    [item.id, item.request, label(item.category), ...item.desktop.map(decisionLabel), ...item.phone.map(decisionLabel)]
      .join(" ").toLocaleLowerCase().includes(query));
  suiteState.page = 0;
  showSuitePage();
}

function showSuitePage() {
  renderPage(suiteState.matches, suiteState.page, "suite-", "suite-count", (item) => `<tr>
    <td><button type="button" class="request" data-fixture="${escape(item.id)}">${escape(item.request)}</button>
    <span class="subline">${escape(label(item.category))}</span></td>
    <td>${escape(item.desktop.map(decisionLabel).join(" / "))}</td>
    <td>${escape(item.phone.map(decisionLabel).join(" / "))}</td>
  </tr>`);
}

function inspectFixture(id) {
  const item = suite.cases.find((candidate) => candidate.id === id);
  if (!item) return;
  openDetail(label(item.category), item.id, `<p class="quote">${escape(item.request)}</p>
    <div class="decision-grid">
      <div><h3>Desktop expects</h3>${item.desktop.map((decision) => `<p>${escape(decisionLabel(decision))}</p>`).join("")}</div>
      <div><h3>Phone direct expects</h3>${item.phone.map((decision) => `<p>${escape(decisionLabel(decision))}</p>`).join("")}</div>
    </div>
    ${item.note ? `<p class="meta-line">${escape(item.note)}</p>` : ""}
    ${disclosure("Desktop context", { ...item.desktop_context, services: suite.services })}
    ${disclosure("Phone direct context", item.phone_context)}`);
}

function validReport(value) {
  return Array.isArray(value?.results) && value.results.length && value.results.every((row) =>
    row && ["id", "category", "model", "route", "reasoning", "history", "request", "status"].every((key) =>
      typeof row[key] === "string") && Array.isArray(row.expected) &&
      (row.status !== "judged" || typeof row.decision?.action === "string"));
}

async function importReports(event) {
  const errors = [];
  let added = 0;
  for (const file of event.target.files) {
    try {
      const parsed = JSON.parse(await file.text());
      if (!validReport(parsed)) throw new Error("Expected a benchmark report with results.");
      const name = file.name.replace(/\.json$/i, "");
      const started = typeof parsed.started_at === "string" ? parsed.started_at : null;
      if (reports.some((report) => report.name === name && report.started === started)) continue;
      const source = typeof parsed.source?.commit === "string" ? { commit: parsed.source.commit } : null;
      reports.push({ name, started, rows: parsed.results, source });
      added++;
    } catch {
      errors.push(file.name);
    }
  }
  $("load-status").hidden = false;
  $("load-status").classList.toggle("error", !!errors.length);
  $("load-status").textContent = errors.length
    ? `Could not read ${errors.join(", ")} as a benchmark report.${added ? ` Loaded ${added} other report(s).` : ""}`
    : added ? `Loaded ${added} report${added === 1 ? "" : "s"}.` : "Those reports are already open.";
  if (added) {
    refreshReports();
    switchView("results");
  }
  event.target.value = "";
}

for (const id of filterIds) $(id).addEventListener("change", updateScope);
$("search").addEventListener("input", updateMatches);
$("outcome").addEventListener("change", updateMatches);
$("all-categories").addEventListener("click", () => { state.category = ""; updateCategory(); });
$("categories").addEventListener("click", (event) => {
  const button = event.target.closest("[data-category]");
  if (!button) return;
  state.category = state.category === button.dataset.category ? "" : button.dataset.category;
  updateCategory();
});
$("prev").addEventListener("click", () => { state.page--; showPage(); });
$("next").addEventListener("click", () => { state.page++; showPage(); });
document.addEventListener("click", (event) => {
  const opener = event.target.closest("[data-open]");
  const sample = event.target.closest("[data-case]");
  const fixture = event.target.closest("[data-fixture]");
  if (opener) $(opener.dataset.open).showModal();
  if (sample) inspect(Number(sample.dataset.case));
  if (fixture) inspectFixture(fixture.dataset.fixture);
});
$("import").addEventListener("click", () => $("files").click());
$("files").addEventListener("change", importReports);
$("show-results").addEventListener("click", () => switchView("results"));
$("show-suite").addEventListener("click", () => switchView("suite"));
$("suite-search").addEventListener("input", updateSuite);
$("suite-category").addEventListener("change", updateSuite);
$("suite-prev").addEventListener("click", () => { suiteState.page--; showSuitePage(); });
$("suite-next").addEventListener("click", () => { suiteState.page++; showSuitePage(); });
filterOptions("suite-category", [...new Set(suite.cases.map((item) => item.category))].sort(), "All categories");
updateSuite();
refreshReports(true);
switchView(rows.length ? "results" : "suite");
