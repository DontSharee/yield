"use strict";

/*
 * Yield Analytics dashboard. One page per hash route; every page is built
 * from the JSON API with DOM nodes (never innerHTML with data), and redrawn
 * on a timer while auto refresh is on.
 */

const PAGES = {
  overview: { title: "Overview", refresh: 15, render: renderOverview },
  live: { title: "Live zones", refresh: 5, render: renderLive },
  players: { title: "Players & retention", refresh: 60, render: renderPlayers },
  tutorial: { title: "Tutorial funnel", refresh: 60, render: renderTutorial },
  progression: { title: "Progression", refresh: 60, render: renderProgression },
  averages: { title: "Averages", refresh: 60, render: renderAverages },
  economy: { title: "Economy & activity", refresh: 60, render: renderEconomy },
  pets: { title: "Pets", refresh: 60, render: renderPets },
  performance: { title: "Performance", refresh: 10, render: renderPerformance },
  health: { title: "Server health", refresh: 10, render: renderHealth },
  lookup: { title: "Player lookup", refresh: 0, render: renderLookup },
};

const COLORS = ["#4bd9ff", "#55e28b", "#ffc83d", "#ff9f43", "#b15cff", "#ff5f5f", "#2a7fff", "#ff6fb5", "#8bd3a0", "#c9a7ff"];
const state = { token: null, page: "overview", charts: [], timer: null, openZones: new Set(), showBots: false, filter: "", ranges: {} };

// ------------------------------------------------------------------ boot

document.addEventListener("DOMContentLoaded", () => {
  state.token = storage("get", "yieldAnalyticsToken");
  document.getElementById("login-form").addEventListener("submit", (event) => {
    event.preventDefault();
    state.token = document.getElementById("token-input").value.trim();
    storage("set", "yieldAnalyticsToken", state.token);
    start();
  });
  document.getElementById("logout").addEventListener("click", () => {
    storage("remove", "yieldAnalyticsToken");
    state.token = null;
    showLogin();
  });
  document.getElementById("auto-refresh").addEventListener("change", schedule);
  // Chart.js loads on its own time; redraw once it's here so charts appear.
  const chartScript = document.getElementById("chartjs");
  if (chartScript && !window.Chart) {
    chartScript.addEventListener("load", () => {
      if (!document.getElementById("app").hidden) render(false);
    });
  }
  window.addEventListener("hashchange", () => navigate());
  if (state.token) {
    start();
  } else {
    showLogin();
  }
});

function storage(op, key, value) {
  try {
    if (op === "get") return window.localStorage.getItem(key);
    if (op === "set") window.localStorage.setItem(key, value);
    if (op === "remove") window.localStorage.removeItem(key);
  } catch (e) {
    // Storage blocked - the token just won't be remembered.
  }
  return null;
}

function showLogin(message) {
  document.getElementById("app").hidden = true;
  document.getElementById("login").hidden = false;
  const error = document.getElementById("login-error");
  error.hidden = !message;
  error.textContent = message || "";
}

async function start() {
  try {
    await api("overview");
  } catch (e) {
    showLogin(e.message);
    return;
  }
  document.getElementById("login").hidden = true;
  document.getElementById("app").hidden = false;
  navigate();
}

async function api(path) {
  const response = await fetch("/api/" + path, { headers: { Authorization: "Bearer " + state.token } });
  if (response.status === 401) {
    throw new Error("That token wasn't accepted.");
  }
  if (response.status === 429) {
    throw new Error("Too many attempts - wait a minute.");
  }
  if (!response.ok) {
    let message = "Request failed (" + response.status + ")";
    try { message = (await response.json()).error || message; } catch (e) { /* not json */ }
    throw new Error(message);
  }
  return response.json();
}

function navigate() {
  const page = (location.hash || "#overview").slice(1);
  state.page = PAGES[page] ? page : "overview";
  document.querySelectorAll("#nav a").forEach((link) => link.classList.toggle("active", link.dataset.page === state.page));
  document.getElementById("page-title").textContent = PAGES[state.page].title;
  render(true);
}

async function render(first) {
  const page = PAGES[state.page];
  const target = document.getElementById("page");
  const next = document.createElement("div");
  next.className = "page-inner";
  next.style.display = "grid";
  next.style.gap = "20px";
  const oldCharts = state.charts;
  state.charts = [];
  if (first) {
    target.replaceChildren(h("div", { class: "empty" }, "Loading…"));
  }
  try {
    await page.render(next);
    oldCharts.forEach((chart) => chart.destroy());
    const scroll = window.scrollY;
    target.replaceChildren(next);
    window.scrollTo(0, scroll);
    document.getElementById("updated").textContent = "Updated " + new Date().toLocaleTimeString();
  } catch (e) {
    state.charts.forEach((chart) => chart.destroy());
    state.charts = oldCharts;
    if (e.message.includes("token")) {
      showLogin(e.message);
      return;
    }
    if (first) {
      target.replaceChildren(h("div", { class: "card error" }, "Couldn't load this page: " + e.message));
    }
  }
  refreshPill();
  schedule();
}

function schedule() {
  clearTimeout(state.timer);
  const seconds = PAGES[state.page].refresh;
  if (seconds > 0 && document.getElementById("auto-refresh").checked) {
    state.timer = setTimeout(() => render(false), seconds * 1000);
  }
}

async function refreshPill() {
  try {
    const live = await api("live");
    const pill = document.getElementById("server-pill");
    pill.replaceChildren(h("span", { class: "dot good" }), `${live.online} online` + (live.bots ? ` · ${live.bots} bots` : ""));
  } catch (e) {
    document.getElementById("server-pill").replaceChildren(h("span", { class: "dot bad" }), "unreachable");
  }
}

// ------------------------------------------------------------------ helpers

function h(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "style") node.style.cssText = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

const SUFFIXES = ["", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"];
function fmt(value, digits = 1) {
  if (value == null || Number.isNaN(value)) return "–";
  const n = Number(value);
  if (Math.abs(n) < 1000) return Number.isInteger(n) ? n.toLocaleString() : n.toFixed(digits);
  let tier = Math.floor(Math.log10(Math.abs(n)) / 3);
  tier = Math.min(tier, SUFFIXES.length - 1);
  const scaled = n / Math.pow(1000, tier);
  return (scaled >= 100 ? scaled.toFixed(0) : scaled.toFixed(digits)).replace(/\.0$/, "") + SUFFIXES[tier];
}
function plural(n, word) {
  return fmt(n) + " " + word + (n === 1 ? "" : "s");
}
function titleCase(text) {
  return text ? String(text).replaceAll("_", " ").replace(/\b\w/g, (c) => c.toUpperCase()) : "–";
}
function pct(value, digits = 0) {
  return value == null ? "–" : (value * 100).toFixed(digits) + "%";
}
function duration(ms) {
  if (ms == null) return "–";
  const minutes = Math.floor(ms / 60000);
  if (minutes < 60) return minutes + "m";
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return hours + "h " + (minutes % 60) + "m";
  return Math.floor(hours / 24) + "d " + (hours % 24) + "h";
}
function ago(timestamp) {
  if (!timestamp) return "never";
  const seconds = Math.max(0, (Date.now() - timestamp) / 1000);
  if (seconds < 60) return "just now";
  if (seconds < 3600) return Math.floor(seconds / 60) + "m ago";
  if (seconds < 86400) return Math.floor(seconds / 3600) + "h ago";
  return Math.floor(seconds / 86400) + "d ago";
}
function hourLabel(t) {
  const d = new Date(t);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function card(title, ...children) {
  return h("div", { class: "card" }, title ? h("h2", {}, title) : null, ...children);
}
function kpi(label, value, sub, cls) {
  return h("div", { class: "card kpi" }, h("div", { class: "label" }, label), h("div", { class: "value " + (cls || "") }, value), sub ? h("div", { class: "sub" }, sub) : null);
}
function empty(text) {
  return h("div", { class: "empty" }, text);
}
function table(columns, rows) {
  if (!rows.length) return empty("Nothing yet.");
  return h("div", { class: "table-wrap" }, h("table", {},
    h("thead", {}, h("tr", {}, columns.map((c) => h("th", { class: c.num ? "num" : "" }, c.label)))),
    h("tbody", {}, rows.map((row) => h("tr", {}, columns.map((c) => {
      const value = c.value(row);
      return h("td", { class: c.num ? "num" : "" }, value instanceof Node ? value : value == null ? "–" : String(value));
    }))))));
}
function barCell(value, max, text) {
  const width = max > 0 ? Math.max(1, (value / max) * 100) : 0;
  return h("div", { class: "bar-cell" }, h("div", { class: "bar", style: `width:${width}%` }), h("span", {}, text));
}
function segmented(key, options, fallback) {
  const current = state.ranges[key] ?? fallback;
  return h("div", { class: "segmented" }, options.map(([value, label]) =>
    h("button", { class: value === current ? "active" : "", onclick: () => { state.ranges[key] = value; render(false); } }, label)));
}
function range(key, fallback) {
  return state.ranges[key] ?? fallback;
}

// ------------------------------------------------------------------ charts

function chart(canvasHolderClass, config) {
  const holder = h("div", { class: "chart-box " + (canvasHolderClass || "") });
  if (!window.Chart) {
    holder.append(empty("Charts need cdn.jsdelivr.net - tables still work."));
    return holder;
  }
  const canvas = h("canvas");
  holder.append(canvas);
  const defaults = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    interaction: { mode: "index", intersect: false },
    plugins: {
      legend: { labels: { color: "#8b95a7", boxWidth: 10, boxHeight: 10, usePointStyle: true } },
      tooltip: { backgroundColor: "#0f1421", borderColor: "#1f2937", borderWidth: 1, titleColor: "#e5e7eb", bodyColor: "#e5e7eb" },
    },
    scales: {
      x: { ticks: { color: "#5b6577", maxRotation: 0, autoSkip: true, maxTicksLimit: 10 }, grid: { color: "rgba(255,255,255,0.03)" } },
      y: { ticks: { color: "#5b6577", callback: (v) => fmt(v) }, grid: { color: "rgba(255,255,255,0.05)" }, beginAtZero: true },
    },
  };
  const merged = deepMerge(defaults, config.options || {});
  state.charts.push(new window.Chart(canvas, { type: config.type, data: config.data, options: merged }));
  return holder;
}
function deepMerge(base, extra) {
  const out = Array.isArray(base) ? [...base] : { ...base };
  for (const [key, value] of Object.entries(extra)) {
    out[key] = value && typeof value === "object" && !Array.isArray(value) && base[key] && typeof base[key] === "object"
      ? deepMerge(base[key], value) : value;
  }
  return out;
}
function line(labels, datasets, options) {
  return {
    type: "line",
    data: { labels, datasets: datasets.map((d, i) => ({ borderWidth: 2, pointRadius: 0, tension: 0.25, fill: d.fill ?? false,
      borderColor: d.color || COLORS[i % COLORS.length], backgroundColor: (d.color || COLORS[i % COLORS.length]) + "22", ...d })) },
    options,
  };
}
function bars(labels, datasets, options) {
  return {
    type: "bar",
    data: { labels, datasets: datasets.map((d, i) => ({ borderRadius: 4, backgroundColor: d.color || COLORS[i % COLORS.length], ...d })) },
    options,
  };
}

/** A stat's value: durations (kept in minutes) as "3h 20m", everything else as a short number. */
function statValue(stat, value) {
  return stat.unit === "duration" ? minutesText(value) : fmt(value);
}
function minutesText(minutes) {
  if (minutes == null || Number.isNaN(minutes)) return "–";
  if (minutes < 1) return Math.round(minutes * 60) + "s";
  return duration(Math.round(minutes) * 60000);
}
/** A card title with its unit, unless the unit is already in the title or shown by the values. */
function statTitle(stat) {
  const unit = stat.unit && stat.unit !== "duration" && !stat.label.toLowerCase().includes(stat.unit) ? ` (${stat.unit})` : "";
  return stat.label + unit;
}

function histogramCard(stat, extra) {
  const labels = (stat.histogram || []).map((b) => b.label);
  const counts = (stat.histogram || []).map((b) => b.count);
  return h("div", { class: "card stat-card" },
    h("div", { class: "card-head" }, h("h3", {}, statTitle(stat)), h("span", { class: "muted small" }, fmt(stat.count) + " players")),
    h("div", { class: "stat-row" },
      h("div", {}, h("span", {}, "Average"), h("span", {}, statValue(stat, stat.avg))),
      h("div", {}, h("span", {}, "Median"), h("span", {}, statValue(stat, stat.median))),
      h("div", {}, h("span", {}, "Top 10%"), h("span", {}, statValue(stat, stat.p90))),
      h("div", {}, h("span", {}, "Max"), h("span", {}, statValue(stat, stat.max)))),
    extra || null,
    labels.length ? chart("short", bars(labels, [{ label: "Players", data: counts }],
      { plugins: { legend: { display: false } }, scales: { x: { ticks: { maxTicksLimit: 8 } } } })) : empty("No data yet."));
}

// ------------------------------------------------------------------ pages

async function renderOverview(root) {
  const [data, checks] = await Promise.all([api("overview"), api("diagnostics").catch(() => null)]);
  const problems = ((checks && checks.findings) || []).filter((f) => f.severity !== "info");
  if (problems.length) {
    const critical = problems.some((f) => f.severity === "critical");
    root.append(h("a", { class: "banner " + (critical ? "critical" : "warn"), href: "#health" },
      h("strong", {}, `${problems.length} health ${problems.length === 1 ? "warning" : "warnings"}: `),
      problems.slice(0, 2).map((f) => f.title).join(" · "), h("span", { class: "banner-more" }, "View →")));
  }
  const health = data.health || {};
  const today = data.today || {};
  const players = data.players || {};
  const tpsClass = health.tps1m >= 19.5 ? "good" : health.tps1m >= 17 ? "warn" : "bad";
  root.append(h("div", { class: "grid kpis" },
    kpi("Online now", fmt(data.online), data.bots ? `${data.bots} bots not counted` : `uptime ${duration((data.uptimeSeconds || 0) * 1000)}`),
    kpi("Peak today", fmt(data.peakToday), `all-time peak ${fmt(data.allTimePeak)}`),
    kpi("Players, all time", fmt(players.total), `${fmt(players.new7d)} new this week`),
    kpi("Daily / weekly / monthly", `${fmt(data.dau)} / ${fmt(data.wau)} / ${fmt(data.mau)}`, data.mau ? `stickiness ${pct(data.dau / data.mau)}` : "active players"),
    kpi("New today", fmt(today.newPlayers || 0), `${fmt(today.joins || 0)} joins`),
    kpi("Average session today", minutesText(data.avgSessionMinutesToday), `${duration(today.playtimeMs || 0)} played in total`),
    kpi("Tutorial completion", pct(data.tutorialCompletionRate), "of players who started it"),
    kpi("Server TPS", (health.tps1m ?? 0).toFixed(1), `${(health.msptAvg ?? 0).toFixed(1)}ms a tick · p95 ${(health.msptP95 ?? 0).toFixed(1)}ms`, tpsClass)));

  const series = data.onlineSeries || [];
  root.append(card("Players online, last 24 hours",
    series.length ? chart("", line(series.map((p) => hourLabel(p.t)), [{ label: "Online", data: series.map((p) => p.v), fill: true }],
      { plugins: { legend: { display: false } } })) : empty("Samples start a minute after the server does.")));

  const activity = [
    ["Cubes broken", today.cubeKills], ["Eggs hatched", today.eggsHatched], ["Rebirths", today.rebirths],
    ["Zones unlocked", today.zoneUnlocks], ["Boss kills", today.bossKills], ["Pets fused", today.petsFused],
    ["Ores mined", today.oresMined], ["Coins from cubes", today.coinsFromCubes], ["Diamonds from cubes", today.diamondsFromCubes],
    ["Huge pets hatched", today.hugeHatched], ["Shiny pets hatched", today.shinyHatched], ["Tutorials finished", today.tutorialCompleted],
  ];
  root.append(card("Today so far (UTC)", h("div", { class: "grid kpis" }, activity.map(([label, value]) =>
    h("div", { class: "kpi" }, h("div", { class: "label" }, label), h("div", { class: "value", style: "font-size:20px" }, fmt(value || 0)))))));
}

async function renderLive(root) {
  const data = await api("live");
  const zones = (data.zones || []).map((zone) => ({
    ...zone,
    shown: (zone.players || []).filter((p) => (state.showBots || !p.bot) && (!state.filter || p.name.toLowerCase().includes(state.filter))),
  }));
  const max = Math.max(1, ...zones.map((z) => z.shown.length));
  const total = zones.reduce((sum, z) => sum + z.shown.length, 0);

  const search = h("input", { type: "search", placeholder: "Filter by name…", value: state.filter });
  search.addEventListener("input", () => { state.filter = search.value.toLowerCase(); render(false); });
  const bots = h("input", { type: "checkbox" });
  bots.checked = state.showBots;
  bots.addEventListener("change", () => { state.showBots = bots.checked; render(false); });
  root.append(h("div", { class: "toolbar" }, search,
    h("label", { class: "toggle small" }, bots, " Show load-test bots"),
    h("button", { onclick: () => { zones.forEach((z) => state.openZones.add(z.id)); render(false); } }, "Expand all"),
    h("button", { onclick: () => { state.openZones.clear(); render(false); } }, "Collapse all"),
    h("span", { class: "muted small" }, `${total} player${total === 1 ? "" : "s"} shown`)));

  root.append(h("div", { class: "grid cards zone-grid" }, zones.map((zone) => {
    const open = state.openZones.has(zone.id);
    const head = h("button", { class: "zone-head", onclick: () => {
      if (state.openZones.has(zone.id)) state.openZones.delete(zone.id); else state.openZones.add(zone.id);
      render(false);
    } },
      h("span", { class: "chev" }, "▶"),
      h("span", { class: "zone-name" }, zone.name),
      h("span", { class: "zone-count" }, zone.shown.length));
    return h("div", { class: "card zone-card" + (open ? " open" : "") },
      head,
      h("div", { class: "zone-bar" }, h("div", { style: `width:${(zone.shown.length / max) * 100}%` })),
      open ? h("div", { class: "zone-players" }, zone.shown.length ? zone.shown.map(playerRow) : empty("Nobody here.")) : null);
  })));
}

function playerRow(player) {
  const meta = [duration(player.sessionSeconds * 1000) + " on"];
  if (player.rebirths != null) meta.push(`R${player.rebirths}`);
  if (player.coins) meta.push(player.coins + " coins");
  if (player.equipped != null) meta.push(`${player.equipped} pets out`);
  return h("div", { class: "player-row" },
    h("img", { src: `https://mc-heads.net/avatar/${encodeURIComponent(player.name)}/24`, alt: "", loading: "lazy" }),
    h("div", { class: "who" },
      h("div", { class: "who-line" },
        h("a", { class: "name", href: "#lookup", onclick: () => { state.lookup = player.name; } }, player.name),
        player.bot ? h("span", { class: "tag bot" }, "bot") : null,
        player.tutorial && player.tutorial !== "done" ? h("span", { class: "tag" }, "tutorial " + player.tutorial) : null),
      h("div", { class: "meta" }, meta.join(" · "))));
}

async function renderPlayers(root) {
  const stats = await api("stats");
  const activity = await api("activity?hours=" + range("sessions", 168));
  const retention = stats.retention || {};
  const days = retention.days || [];
  const p = stats.players || {};

  root.append(h("div", { class: "grid kpis" },
    kpi("Players, all time", fmt(p.total)),
    kpi("Active today", fmt(p.activeToday)),
    kpi("Active this week", fmt(p.active7d)),
    kpi("Active this month", fmt(p.active30d)),
    kpi("New this week", fmt(p.new7d)),
    kpi("In a team", p.total ? pct(p.inTeam / p.total) : "–")));

  root.append(card("Daily players (UTC)", days.length ? chart("", bars(days.map((d) => d.day.slice(5)), [
    { label: "Returning", data: days.map((d) => d.returning), color: "#4bd9ff", stack: "a" },
    { label: "New", data: days.map((d) => d.new), color: "#55e28b", stack: "a" },
  ], { scales: { x: { stacked: true }, y: { stacked: true } } })) : empty("Fills in as days go by.")));

  const cohorts = (retention.cohorts || []).slice(-21).reverse();
  const keys = ["d1", "d3", "d7", "d14", "d30"];
  root.append(card("Retention by first-join day - share who came back", table(
    [{ label: "First joined", value: (r) => r.day }, { label: "Players", num: true, value: (r) => r.size },
      ...keys.map((k) => ({ label: "Day " + k.slice(1), num: true, value: (r) => heat(r[k]) }))],
    cohorts)));

  const totals = activity.totals || {};
  const buckets = [["lt5", "< 5m"], ["lt15", "5–15m"], ["lt30", "15–30m"], ["lt60", "30–60m"], ["lt120", "1–2h"], ["lt240", "2–4h"], ["ge240", "4h+"]];
  root.append(h("div", { class: "grid cols-2" },
    h("div", { class: "card" }, h("div", { class: "card-head" }, h("h2", {}, "Session lengths"),
      segmented("sessions", [[24, "24h"], [168, "7d"], [720, "30d"]], 168)),
      chart("", bars(buckets.map((b) => b[1]), [{ label: "Sessions", data: buckets.map((b) => totals["sessionLen_" + b[0]] || 0) }],
        { plugins: { legend: { display: false } } }))),
    histogramCard(findStat(stats, "playtime") || { ...emptyStat("Playtime"), unit: "duration" })));

  const leaders = stats.leaders || {};
  const boards = [["coins", "Coins"], ["rebirths", "Rebirths"], ["playtime", "Playtime"], ["eggs", "Eggs hatched"], ["cubeKills", "Cubes broken"]];
  root.append(h("div", { class: "grid cols-3" }, boards.map(([key, label]) => card("Top " + label.toLowerCase(),
    table([{ label: "#", value: (r) => r.rank }, { label: "Player", value: (r) => r.name }, { label, num: true, value: (r) => r.value }],
      (leaders[key] || []).map((r, i) => ({ ...r, rank: i + 1 })))))));
}

function heat(value) {
  if (value == null) return h("span", { class: "faint" }, "·");
  const alpha = Math.min(0.85, 0.12 + value * 0.9);
  return h("div", { class: "heat", style: `background: rgba(75,217,255,${alpha}); color:${value > 0.45 ? "#051018" : "#e5e7eb"}; padding:2px 6px` }, pct(value));
}

function findStat(stats, key) {
  return (stats.numbers || []).find((n) => n.key === key);
}
function emptyStat(label) {
  return { label, count: 0, avg: 0, median: 0, p90: 0, max: 0, histogram: [] };
}

async function renderTutorial(root) {
  const data = await api("tutorial");
  if (!data.stepCount) {
    root.append(card(null, empty("No tutorial found - is yield-tutorial installed?")));
    return;
  }
  root.append(h("div", { class: "grid kpis" },
    kpi("Started", fmt(data.started), "every new player"),
    kpi("Completed", fmt(data.completed), pct(data.completionRate) + " of starters", "good"),
    kpi("Skipped", fmt(data.skipped), data.started ? pct(data.skipped / data.started) : ""),
    kpi("Still in it", fmt(data.inProgress), "played in the last " + data.leftAfterDays + "d"),
    kpi("Left before finishing", fmt(data.leftDuring), "gone " + data.leftAfterDays + "+ days, unfinished", data.leftDuring ? "bad" : ""),
    kpi("Median time to finish", minutesText(data.medianMinutesToComplete))));

  const steps = data.steps || [];
  const top = Math.max(1, data.started);
  root.append(card("Where players get to",
    h("div", { class: "funnel" }, steps.map((step, i) => {
      const previous = i === 0 ? step.reached : steps[i - 1].reached;
      const drop = previous > 0 ? 1 - step.reached / previous : 0;
      return h("div", { class: "funnel-row" },
        h("div", { class: "funnel-label" }, `${i < data.stepCount ? i + 1 + ". " : ""}${step.label}`),
        h("div", { class: "funnel-track" }, h("div", { class: "funnel-fill", style: `width:${Math.max(0.5, (step.reached / top) * 100)}%` }, fmt(step.reached))),
        h("div", { class: "funnel-meta", title: i > 0 ? `${pct(drop)} of the previous step's players did not get here` : "" },
          pct(step.share), i > 0 && drop > 0.005 ? h("span", { class: "drop" }, ` −${pct(drop)}`) : null));
    }))));

  root.append(h("div", { class: "grid cols-2" },
    card("Players who left at each step", table(
      [{ label: "Step", value: (r) => r.label }, { label: "Left here", num: true, value: (r) => barCell(r.leftHere, Math.max(1, ...steps.map((s) => s.leftHere)), fmt(r.leftHere)) }],
      steps.filter((s) => s.index < data.stepCount))),
    card("Last 7 days", h("div", { class: "kv" },
      h("div", {}, h("span", {}, "Started"), h("span", {}, fmt(data.last7Days?.started))),
      h("div", {}, h("span", {}, "Completed"), h("span", {}, fmt(data.last7Days?.completed))),
      h("div", {}, h("span", {}, "Skipped"), h("span", {}, fmt(data.last7Days?.skipped))),
      h("div", {}, h("span", {}, "Avg time to finish"), h("span", {}, minutesText(data.last7Days?.avgMinutesToComplete)))))));
}

async function renderProgression(root) {
  const stats = await api("stats");
  const zones = stats.zones || [];
  const maxReached = Math.max(1, ...zones.map((z) => z.reached));
  root.append(card("How far through the zones players get", zones.length ? chart("", bars(zones.map((z) => z.name), [
    { label: "Reached", data: zones.map((z) => z.reached), color: "#4bd9ff" },
    { label: "Unlocked", data: zones.map((z) => z.unlocked), color: "#55e28b" },
    { label: "Furthest zone", data: zones.map((z) => z.furthest), color: "#ffc83d" },
  ])) : empty("No zones.")));
  root.append(card("Zones", table([
    { label: "Zone", value: (z) => z.name },
    { label: "Players reached", num: true, value: (z) => barCell(z.reached, maxReached, fmt(z.reached)) },
    { label: "Unlocked", num: true, value: (z) => fmt(z.unlocked) },
    { label: "Furthest for", num: true, value: (z) => fmt(z.furthest) },
    { label: "Median time to reach", num: true, value: (z) => z.medianHoursToReach == null ? "–" : duration(z.medianHoursToReach * 3600000) },
  ], zones)));
  const keys = ["rebirths", "prestiges", "level", "zonesUnlocked", "upgradeLevels", "blocktreeTiers", "skillLevels", "achievements", "loginStreak"];
  root.append(h("div", { class: "grid cards" }, keys.map((key) => findStat(stats, key)).filter(Boolean).map((stat) => histogramCard(stat))));
}

async function renderAverages(root) {
  const stats = await api("stats");
  if (!stats.ready) {
    root.append(card(null, empty("The first statistics run is still going - give it a moment.")));
    return;
  }
  root.append(h("p", { class: "muted" }, `Across every player who has ever joined. Recomputed ${ago(stats.generatedAt)} in ${stats.computeMs}ms.`));
  root.append(card("Every stat at a glance", table([
    { label: "Stat", value: (s) => statTitle(s) },
    { label: "Average", num: true, value: (s) => statValue(s, s.avg) },
    { label: "Median", num: true, value: (s) => statValue(s, s.median) },
    { label: "Top 10%", num: true, value: (s) => statValue(s, s.p90) },
    { label: "Max", num: true, value: (s) => statValue(s, s.max) },
    { label: "Total", num: true, value: (s) => s.unit === "duration" ? minutesText(s.total) : fmt(s.total) },
    { label: "At zero", num: true, value: (s) => pct(s.zeroShare) },
  ], stats.numbers || [])));
  root.append(h("div", { class: "grid cards" }, (stats.numbers || []).map((stat) => histogramCard(stat))));
}

async function renderEconomy(root) {
  const hours = range("economy", 48);
  const [activity, stats] = await Promise.all([api("activity?hours=" + hours), api("stats")]);
  const rows = activity.rows || [];
  const labels = rows.map((r) => r.hour.slice(5).replace("T", " ") + ":00");
  const series = (key) => rows.map((r) => r.c[key] || 0);
  const totals = activity.totals || {};

  root.append(h("div", { class: "toolbar" }, h("span", { class: "muted" }, "Range"),
    segmented("economy", [[24, "24h"], [48, "48h"], [168, "7d"], [720, "30d"]], 48)));
  root.append(h("div", { class: "grid kpis" },
    kpi("Cubes broken", fmt(totals.cubeKills || 0)),
    kpi("Coins from cubes", fmt(totals.coinsFromCubes || 0)),
    kpi("Diamonds from cubes", fmt(totals.diamondsFromCubes || 0)),
    kpi("Eggs hatched", fmt(totals.eggsHatched || 0)),
    kpi("Rebirths", fmt(totals.rebirths || 0)),
    kpi("Boss kills", fmt(totals.bossKills || 0)),
    kpi("Ores mined", fmt(totals.oresMined || 0)),
    kpi("Hours played", fmt((totals.playtimeMs || 0) / 3600000))));

  root.append(h("div", { class: "grid cols-2" },
    card("Players doing things, per hour", rows.length ? chart("", line(labels, [
      { label: "Joins", data: series("joins") }, { label: "New players", data: series("newPlayers") },
      { label: "Zone unlocks", data: series("zoneUnlocks") }, { label: "Rebirths", data: series("rebirths") },
    ])) : empty("No activity recorded in this range.")),
    card("Grinding, per hour", rows.length ? chart("", line(labels, [
      { label: "Cubes broken", data: series("cubeKills") }, { label: "Eggs hatched", data: series("eggsHatched") },
      { label: "Pet level-ups", data: series("petLevelUps") },
    ])) : empty("No activity recorded in this range."))));
  root.append(card("Coins and diamonds entering the economy, per hour", rows.length ? chart("", line(labels, [
    { label: "Coins from cubes", data: series("coinsFromCubes") }, { label: "Coins from mining", data: series("coinsFromMining") },
    { label: "Coins from bosses", data: series("coinsFromBosses") },
  ])) : empty("No activity recorded in this range.")));

  const kills = Object.entries(totals).filter(([k]) => k.startsWith("kills_")).sort((a, b) => b[1] - a[1]);
  const eggs = Object.entries(totals).filter(([k]) => k.startsWith("egg_")).sort((a, b) => b[1] - a[1]).slice(0, 12);
  root.append(h("div", { class: "grid cols-2" },
    card("Cubes broken by block", table([{ label: "Block", value: (r) => r[0].slice(6).replaceAll("_", " ") },
      { label: "Broken", num: true, value: (r) => barCell(r[1], kills[0]?.[1] || 1, fmt(r[1])) }], kills)),
    card("Most hatched eggs", table([{ label: "Egg", value: (r) => r[0].slice(4).replaceAll("_", " ") },
      { label: "Hatched", num: true, value: (r) => barCell(r[1], eggs[0]?.[1] || 1, fmt(r[1])) }], eggs))));
  root.append(h("div", { class: "grid cards" }, ["coins", "diamonds", "lifetimeCoins", "cubeKills", "eggsHatched", "bossDamage"]
    .map((key) => findStat(stats, key)).filter(Boolean).map((stat) => histogramCard(stat))));
}

async function renderPets(root) {
  const hours = range("pets", 168);
  const [stats, activity] = await Promise.all([api("stats"), api("activity?hours=" + hours)]);
  const rarities = stats.rarities || [];
  const totals = activity.totals || {};
  root.append(h("div", { class: "grid kpis" },
    kpi("Pets owned", fmt(rarities.reduce((s, r) => s + r.owned, 0))),
    kpi("Shiny pets", fmt(rarities.reduce((s, r) => s + r.shiny, 0))),
    kpi("Huge pets", fmt(rarities.reduce((s, r) => s + r.huge, 0))),
    kpi("Hatched (range)", fmt(totals.eggsHatched || 0)),
    kpi("Huge hatched (range)", fmt(totals.hugeHatched || 0)),
    kpi("Fused (range)", fmt(totals.petsFused || 0))));
  root.append(h("div", { class: "toolbar" }, h("span", { class: "muted" }, "Hatch range"),
    segmented("pets", [[24, "24h"], [168, "7d"], [720, "30d"]], 168)));
  root.append(h("div", { class: "grid cols-2" },
    card("Owned pets by rarity", rarities.length ? chart("", bars(rarities.map((r) => r.name), [
      { label: "Owned", data: rarities.map((r) => r.owned), backgroundColor: rarities.map((r) => r.color || "#4bd9ff") },
    ], { plugins: { legend: { display: false } }, scales: { y: { type: "logarithmic", ticks: { callback: (v) => fmt(v) } } } })) : empty("No rarities.")),
    card("Hatched by rarity (range)", rarities.length ? chart("", bars(rarities.map((r) => r.name), [
      { label: "Hatched", data: rarities.map((r) => totals["hatch_" + r.id] || 0), backgroundColor: rarities.map((r) => r.color || "#4bd9ff") },
    ], { plugins: { legend: { display: false } }, scales: { y: { type: "logarithmic", ticks: { callback: (v) => fmt(v) } } } })) : empty("No rarities."))));
  root.append(h("div", { class: "grid cols-2" },
    card("Rarities", table([
      { label: "Rarity", value: (r) => h("span", { style: `color:${r.color}` }, r.name) },
      { label: "Owned", num: true, value: (r) => fmt(r.owned) },
      { label: "Players with one", num: true, value: (r) => fmt(r.players) },
      { label: "Shiny", num: true, value: (r) => fmt(r.shiny) },
      { label: "Huge", num: true, value: (r) => fmt(r.huge) },
    ], rarities)),
    card("Luckiest pulls ever", table([
      { label: "Player", value: (r) => r.player }, { label: "Pet", value: (r) => r.item },
      { label: "Odds", num: true, value: (r) => "1 in " + fmt(r.oneIn) },
    ], stats.luckiest || []))));
  const popular = stats.popularPets || [];
  root.append(card("Most owned pets", table([
    { label: "Pet", value: (r) => r.name }, { label: "Rarity", value: (r) => r.rarity },
    { label: "Owned", num: true, value: (r) => barCell(r.count, popular[0]?.count || 1, fmt(r.count)) },
  ], popular)));
  root.append(h("div", { class: "grid cards" }, ["petsOwned", "petsEquipped"].map((key) => findStat(stats, key)).filter(Boolean).map((s) => histogramCard(s))));
}

async function renderPerformance(root) {
  const hours = range("perf", 6);
  const data = await api("performance?hours=" + hours);
  const health = data.health || {};
  const samples = data.samples || [];
  const labels = samples.map((s) => hourLabel(s.t));
  root.append(h("div", { class: "grid kpis" },
    kpi("TPS (1m / 5m / 15m)", `${(health.tps1m ?? 0).toFixed(1)}`, `${(health.tps5m ?? 0).toFixed(1)} / ${(health.tps15m ?? 0).toFixed(1)}`, health.tps1m >= 19.5 ? "good" : health.tps1m >= 17 ? "warn" : "bad"),
    kpi("Tick time", `${(health.msptAvg ?? 0).toFixed(1)}ms`, `p95 ${(health.msptP95 ?? 0).toFixed(1)}ms · max ${(health.msptMax ?? 0).toFixed(1)}ms`, health.msptAvg < 25 ? "good" : health.msptAvg < 45 ? "warn" : "bad"),
    kpi("Memory", `${fmt(health.heapUsedMb)} MB`, `of ${fmt(health.heapMaxMb)} MB`),
    kpi("Plugin packets", `${fmt(health.pluginPacketsPerSecond)}/s`, `${(health.trackedMsPerTick ?? 0).toFixed(2)}ms/tick tracked`),
    kpi("Database", `${health.dbQueued ?? 0} queued`, `${health.dbActive ?? 0} running · ${health.writesInFlight ?? 0} writes`),
    kpi("Records in memory", fmt(health.cachedRecords))));
  root.append(h("div", { class: "toolbar" }, h("span", { class: "muted" }, "Range"),
    segmented("perf", [[1, "1h"], [6, "6h"], [24, "24h"], [168, "7d"]], 6)));
  root.append(h("div", { class: "grid cols-2" },
    card("Tick time (ms)", samples.length ? chart("", line(labels, [
      { label: "Average", data: samples.map((s) => s.mspt) }, { label: "p95", data: samples.map((s) => s.msptP95), color: "#ffc83d" },
      { label: "Worst", data: samples.map((s) => s.msptMax), color: "#ff5f5f", borderDash: [4, 4] },
    ])) : empty("Samples start a minute after the server does.")),
    card("TPS and players online", samples.length ? chart("", line(labels, [
      { label: "TPS", data: samples.map((s) => s.tps), yAxisID: "y" },
      { label: "Online", data: samples.map((s) => s.online), color: "#55e28b", yAxisID: "y1" },
    ], { scales: { y: { min: 0, max: 20 }, y1: { position: "right", beginAtZero: true, grid: { display: false }, ticks: { color: "#5b6577" } } } })) : empty("No samples yet."))));
  root.append(h("div", { class: "grid cols-2" },
    card("Memory (MB)", samples.length ? chart("short", line(labels, [
      { label: "Used", data: samples.map((s) => s.heapMb), fill: true }, { label: "Max", data: samples.map((s) => s.heapMaxMb), color: "#5b6577" },
    ])) : empty("No samples yet.")),
    card("Plugin packets per second", samples.length ? chart("short", line(labels, [
      { label: "Packets/s", data: samples.map((s) => s.pluginPps), fill: true, color: "#b15cff" },
    ], { plugins: { legend: { display: false } } })) : empty("No samples yet."))));
  const systems = data.systems || [];
  const maxMs = Math.max(0.001, ...systems.map((s) => s.msPerTick));
  root.append(card("Where the tick goes (last 60s)", table([
    { label: "System", value: (s) => h("span", { class: "mono" }, s.system) },
    { label: "ms per tick", num: true, value: (s) => barCell(s.msPerTick, maxMs, s.msPerTick.toFixed(3)) },
    { label: "avg per run", num: true, value: (s) => s.avgMsPerRun.toFixed(3) + "ms" },
    { label: "worst run", num: true, value: (s) => s.maxMsPerRun.toFixed(2) + "ms" },
    { label: "runs", num: true, value: (s) => fmt(s.runs) },
  ], systems)));
  const packetTable = (rows) => {
    const max = Math.max(1, ...rows.map((r) => r.perSecond));
    return table([{ label: "Name", value: (r) => h("span", { class: "mono" }, r.name) },
      { label: "per second", num: true, value: (r) => barCell(r.perSecond, max, fmt(r.perSecond)) },
      { label: "since boot", num: true, value: (r) => fmt(r.total) }], rows);
  };
  root.append(h("div", { class: "grid cols-2" },
    card("Plugin packets by system", packetTable(data.packetsBySystem || [])),
    card("Plugin packets by type", packetTable(data.packetsByType || []))));
}

// ------------------------------------------------------------------ health

function findingsCard(findings) {
  if (!findings.length) {
    return card("Health checks", h("div", { class: "all-good" }, "✔ No problems found",
      h("span", { class: "muted small" }, " - lag spikes, memory, leaks and saves all look normal")));
  }
  return card("Health checks", h("div", { class: "findings" }, findings.map((f) =>
    h("div", { class: "finding " + f.severity },
      h("span", { class: "sev" }, f.severity === "critical" ? "Critical" : f.severity === "warn" ? "Warning" : "Note"),
      h("div", {}, h("div", { class: "finding-title" }, f.title), h("div", { class: "muted small" }, f.detail))))));
}

function spikeRow(spike) {
  const cls = spike.ms >= 1000 ? "bad" : spike.ms >= 250 ? "warn" : "";
  const body = [];
  if (spike.culprits.length) {
    body.push(h("div", { class: "spike-section" }, h("h4", {}, `Running during the slow part (${spike.samples} samples)`),
      spike.culprits.map((c) => h("div", { class: "culprit" },
        h("span", { class: "share" }, pct(c.share)), h("span", { class: "mono" }, c.where),
        h("span", { class: "muted small" }, ` [${c.plugin}] in ${c.inside}`)))));
  }
  if (spike.systems.length) {
    body.push(h("div", { class: "spike-section" }, h("h4", {}, "Timed systems in this tick"),
      spike.systems.map((s) => h("div", { class: "culprit" }, h("span", { class: "share" }, s.ms.toFixed(1) + "ms"), h("span", { class: "mono" }, s.system)))));
  }
  if (spike.gcMs > 0) body.push(h("div", { class: "spike-section muted small" }, `Garbage collection during this tick: ${spike.gcMs.toFixed(0)} ms`));
  if (spike.stack.length) body.push(h("div", { class: "spike-section" }, h("h4", {}, "Stack"), h("pre", { class: "stack" }, spike.stack.join("\n"))));
  if (!body.length) body.push(h("div", { class: "muted small" }, "The slow part ended before sampling started, and no timed system stood out."));
  return h("details", { class: "spike" },
    h("summary", {},
      h("span", { class: "muted small mono" }, new Date(spike.at).toLocaleTimeString()),
      h("span", { class: "spike-ms " + cls }, spike.ms.toFixed(0) + " ms"),
      h("span", { class: "spike-cause" }, spike.cause),
      h("span", { class: "muted small" }, `${spike.online} online`)),
    ...body);
}

async function renderHealth(root) {
  const data = await api("diagnostics");
  if (!data.t) {
    root.append(card(null, empty("The first health check runs a few seconds after the server starts.")));
    return;
  }
  const spikes = data.spikes || [];
  const recent = spikes.filter((s) => s.at >= Date.now() - 10 * 60000);
  const worst = recent.reduce((m, s) => Math.max(m, s.ms), 0);
  const mem = data.memory || {};
  const leaks = data.leaks || {};
  const saves = data.saves || {};
  root.append(findingsCard(data.findings || []));
  root.append(h("div", { class: "grid kpis" },
    kpi("Lag spikes, last 10 min", fmt(recent.length), `ticks over ${data.spikeThresholdMs} ms · ${fmt(data.spikesTotal)} since start`, recent.length >= 10 ? "warn" : "good"),
    kpi("Worst tick, last 10 min", worst ? worst.toFixed(0) + " ms" : "–", "a healthy tick is under 50 ms", worst >= 1000 ? "bad" : worst >= 250 ? "warn" : "good"),
    kpi("Memory kept", mem.liveMb >= 0 ? fmt(mem.liveMb) + " MB" : "–", `still in use after garbage collection, of ${fmt(mem.maxMb)} MB`),
    kpi("Memory trend", mem.enoughData ? (mem.mbPerHour >= 0 ? "+" : "") + fmt(mem.mbPerHour) + " MB/h" : "measuring",
      mem.enoughData ? `rising ${mem.risingBuckets} × 15 min in a row` : "needs about 75 minutes of uptime", mem.enoughData && mem.risingBuckets >= 4 && mem.mbPerHour > 0 ? "warn" : ""),
    kpi("Leak suspects", fmt((leaks.suspects || []).length), leaks.at ? `last scan ${ago(leaks.at)} · ${fmt(leaks.objects)} objects` : "first scan 2 min after start", (leaks.suspects || []).length ? "warn" : "good"),
    kpi("Failed saves", fmt(saves.failedTotal || 0), "since start", saves.failedTotal ? "bad" : "good")));

  root.append(card(`Lag spikes - ticks over ${data.spikeThresholdMs} ms, newest first`,
    spikes.length ? h("div", { class: "spikes" }, spikes.slice(0, 40).map(spikeRow))
      : empty("None yet - every tick has been under " + data.spikeThresholdMs + " ms."),
    h("p", { class: "muted small" }, "Click a spike for what the server thread was doing. For a full CPU profile, run Paper's built-in /spark profiler in game.")));

  const minutes = (mem.minutes || []).filter((m) => m.liveMb != null || m.collections);
  root.append(h("div", { class: "grid cols-2" },
    card("Memory kept after garbage collection (MB)", minutes.length ? chart("", line(minutes.map((m) => hourLabel(m.t)), [
      { label: "Kept", data: minutes.map((m) => m.liveMb), fill: true, spanGaps: true },
      { label: "Players", data: minutes.map((m) => m.online), color: "#55e28b", yAxisID: "y1" },
    ], { scales: { y: { suggestedMax: mem.maxMb }, y1: { position: "right", beginAtZero: true, grid: { display: false }, ticks: { color: "#5b6577" } } } }))
      : empty("Fills in a minute at a time.")),
    card("Garbage-collection pauses per minute (ms)", minutes.length ? chart("", bars(minutes.map((m) => hourLabel(m.t)), [
      { label: "Paused", data: minutes.map((m) => m.pauseMs), color: "#ff9f43" },
      { label: "Longest", data: minutes.map((m) => m.longestPauseMs), color: "#ff5f5f" },
    ])) : empty("Fills in a minute at a time."))));

  const suspects = leaks.suspects || [];
  root.append(card("Leak scan",
    h("p", { class: "muted small" }, leaks.at
      ? `Every 5 minutes, every Yield plugin's data is checked for entries left behind by players who quit, players who left but are still referenced, and collections that keep growing. Last scan ${ago(leaks.at)}: ${fmt(leaks.objects)} objects in ${leaks.durationMs} ms${leaks.complete ? "" : " (stopped at the size limit)"}. Growth needs a few scans to show.`
      : "The first scan runs 2 minutes after the server starts."),
    suspects.length ? h("div", { class: "findings" }, suspects.map((s) => h("div", { class: "finding warn" },
      h("span", { class: "sev" }, "Suspect"),
      h("div", {}, h("div", { class: "finding-title" }, `${s.problem}: `, h("span", { class: "mono" }, s.path), h("span", { class: "muted small" }, ` [${s.plugin}]`)),
        h("div", { class: "muted small" }, s.detail))))) : h("div", { class: "all-good" }, "✔ No leaks found")));

  const holderTable = (rows, withStale) => {
    const max = Math.max(1, ...rows.map((r) => r.size));
    const cols = [
      { label: "Where", value: (r) => h("div", {}, h("div", { class: "mono path" }, r.path), h("div", { class: "muted small" }, r.plugin)) },
      { label: "Entries", num: true, value: (r) => barCell(r.size, max, fmt(r.size)) },
    ];
    if (withStale) cols.push({ label: "Players who left", num: true, value: (r) => {
      const n = r.stale + r.leftPlayers;
      return h("span", { class: n ? "warn-text" : "good-text" }, fmt(n));
    } });
    return table(cols, rows);
  };
  root.append(h("div", { class: "grid cols-2" },
    card("Per-player data", holderTable((leaks.perPlayer || []).slice(0, 15), true)),
    card("Largest collections", holderTable(leaks.largest || [], false))));

  const gauges = Object.entries(leaks.gauges || {}).filter((g) => g[1] > 0).sort((a, b) => b[1] - a[1]).slice(0, 15);
  root.append(h("div", { class: "grid cols-2" },
    card("Scheduled tasks, entities and chunks", table([
      { label: "What", value: (g) => h("span", { class: "mono" }, g[0].replace(":", " · ")) },
      { label: "Count", num: true, value: (g) => fmt(g[1]) }], gauges)),
    card("Failed saves", table([
      { label: "When", value: (f) => new Date(f.at).toLocaleString() },
      { label: "Data", value: (f) => f.store },
      { label: "Player", value: (f) => h("span", { class: "mono small" }, f.player) },
      { label: "Why", value: (f) => f.reason }], saves.recent || []))));
}

async function renderLookup(root) {
  const input = h("input", { type: "search", placeholder: "Player name…", autocomplete: "off", value: state.lookup || "" });
  const suggestions = h("div", { class: "suggestions", hidden: true });
  const result = h("div", { class: "grid" });
  let timer = null;
  let query = 0; // a suggestion answer that arrives after the lookup ran is dropped
  input.addEventListener("input", () => {
    clearTimeout(timer);
    const asked = ++query;
    const q = input.value.trim();
    if (!/^[A-Za-z0-9_]{1,16}$/.test(q)) { suggestions.hidden = true; return; }
    timer = setTimeout(async () => {
      const found = await api("players?q=" + encodeURIComponent(q)).catch(() => []);
      if (asked !== query) return;
      suggestions.replaceChildren(...found.map((p) => h("button", { onclick: () => { input.value = p.name; suggestions.hidden = true; show(p.name); } },
        p.name, h("span", { class: "muted small" }, "  last seen " + ago(p.lastSeen)))));
      suggestions.hidden = found.length === 0;
    }, 200);
  });
  input.addEventListener("keydown", (event) => { if (event.key === "Enter") { suggestions.hidden = true; show(input.value.trim()); } });

  async function show(name) {
    query++;
    clearTimeout(timer);
    suggestions.hidden = true;
    state.lookup = name;
    if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) return;
    result.replaceChildren(empty("Loading…"));
    try {
      const p = await api("player?name=" + encodeURIComponent(name));
      result.replaceChildren(profileView(p));
    } catch (e) {
      result.replaceChildren(card(null, empty(e.message.includes("404") || e.message.includes("No such") ? "No player called " + name + "." : e.message)));
    }
  }

  root.append(card("Find a player", h("div", { class: "lookup-bar" }, input, suggestions)), result);
  if (state.lookup) show(state.lookup);
}

function profileView(p) {
  const tutorial = p.tutorial || {};
  const tutorialText = tutorial.outcome === "completed" ? "Completed" : tutorial.skipped ? "Skipped" : `On step ${tutorial.step + 1}`;
  const facts = [
    ["Status", p.online ? "Online now" + (p.live?.zoneName ? " · " + p.live.zoneName : "") : "Last seen " + ago(p.lastSeen)],
    ["First joined", p.firstSeen ? new Date(p.firstSeen).toLocaleDateString() : "–"],
    ["Playtime", duration(p.playtimeMs)], ["Sessions", fmt(p.sessions)], ["Longest session", duration(p.longestSessionMs)],
    ["Coins", p.coins], ["Diamonds", p.diamonds], ["Credits", p.credits], ["Coins earned", p.coinsEarned],
    ["Rebirths", fmt(p.rebirths)], ["Prestiges", fmt(p.prestiges)], ["Level", fmt(p.level)],
    ["Eggs hatched", fmt(p.eggsHatched)], ["Cubes broken", fmt(p.cubesBroken)], ["Pets owned", fmt(p.petsOwned)],
    ["Zones unlocked", (p.zonesUnlocked || []).length], ["Login streak", plural(p.loginStreak || 0, "day")],
    ["Tutorial", tutorialText], ["Donor rank", p.donorRank || "none"], ["Team", p.inTeam ? "Yes" : "No"],
  ];
  if (p.bestLuck) facts.push(["Luckiest pull", `${p.bestLuck.item} (1 in ${fmt(p.bestLuck.oneIn)})`]);
  const reached = Object.entries(p.zoneReachedAt || {}).sort((a, b) => a[1] - b[1]);
  return h("div", { class: "grid" },
    h("div", { class: "card" },
      h("div", { class: "profile-head" },
        h("img", { src: `https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/64`, alt: "" }),
        h("div", {}, h("h3", {}, p.name), h("div", { class: "muted small mono" }, p.uuid))),
      h("div", { class: "kv", style: "margin-top:16px" }, facts.map(([k, v]) => h("div", {}, h("span", {}, k), h("span", {}, v))))),
    h("div", { class: "grid cols-2" },
      card("Equipped pets", table([{ label: "Pet", value: (r) => r.name + (r.shiny ? " ✦" : "") }, { label: "Rarity", value: (r) => titleCase(r.rarity) },
        { label: "Level", num: true, value: (r) => r.level }], p.equipped || [])),
      card("Zones reached", table([{ label: "Zone", value: (r) => (p.zoneNames || {})[r[0]] || r[0] },
        { label: "After joining", num: true, value: (r) => p.firstSeen ? duration(r[1] - p.firstSeen) : "–" }], reached))));
}
