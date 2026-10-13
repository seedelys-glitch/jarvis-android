// Ruban de la journée de Jarvis : les 24 h d'un coup d'œil.
//  - sessions de marché (Asie, Londres, New York), le chevauchement Londres + New York plus marqué
//  - heures de prière, grosses annonces américaines du jour, et l'heure qu'il est
// Utilisé par l'écran Jarvis du PC, l'appli Android et l'appli web.
(function () {
  const CSS = `
  .rb { position: relative; width: 100%; font-variant-numeric: tabular-nums; user-select: none; }
  .rb-top, .rb-bottom { position: relative; height: var(--rb-label, 15px); }
  .rb-lane { position: relative; height: var(--rb-lane, 14px); border-radius: 3px; overflow: hidden;
    background: rgba(242, 231, 226, .05); box-shadow: inset 0 0 0 1px rgba(210, 167, 90, .14); }
  .rb-seg { position: absolute; top: 0; bottom: 0; background: rgba(210, 167, 90, .2); }
  .rb-seg.s2 { background: rgba(210, 167, 90, .42); }
  .rb-seg.on { background: rgba(240, 210, 150, .38); }
  .rb-seg.s2.on { background: rgba(240, 210, 150, .62); }
  .rb-past { position: absolute; top: 0; bottom: 0; left: 0; background: rgba(5, 2, 3, .5); }
  .rb-name { position: absolute; top: 50%; transform: translate(-50%, -50%); white-space: nowrap;
    font-size: var(--rb-font, 11px); color: rgba(242, 231, 226, .78); letter-spacing: .02em; }
  .rb-name.on { color: #f7e9d0; }
  .rb-now { position: absolute; top: calc(-1 * var(--rb-label, 15px)); bottom: calc(-1 * var(--rb-label, 15px));
    width: 2px; margin-left: -1px; background: var(--braise, #e3242b); box-shadow: 0 0 8px rgba(255, 74, 61, .8); }
  .rb-now-t { position: absolute; top: 0; transform: translateX(-50%); font-size: var(--rb-font, 11px);
    color: #ff6a5c; white-space: nowrap; }
  .rb-ev { position: absolute; bottom: 0; width: 0; height: 0; margin-left: -4px;
    border-left: 4px solid transparent; border-right: 4px solid transparent; border-top: 6px solid rgba(227, 36, 43, .75); }
  .rb-ev.next { border-top-color: #ff4a3d; }
  .rb-ev-t { position: absolute; bottom: 7px; transform: translateX(-50%); font-size: var(--rb-font, 11px);
    color: #ff6a5c; white-space: nowrap; }
  .rb-pr { position: absolute; top: 0; width: 1px; height: 5px; background: rgba(242, 231, 226, .55); }
  .rb-pr-t { position: absolute; top: 4px; transform: translateX(-50%); font-size: var(--rb-font, 11px);
    color: var(--cendre, #8c7774); white-space: nowrap; }
  .rb-pr-t.next { color: var(--or-clair, #f0d296); }
  .rb-h { position: absolute; top: 4px; font-size: var(--rb-font, 11px); color: rgba(140, 119, 116, .7); }
  .rb-closed { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
    font-size: var(--rb-font, 11px); color: var(--cendre, #8c7774);
    background: repeating-linear-gradient(135deg, rgba(242,231,226,.04) 0 6px, transparent 6px 12px); }
  `;
  let styled = false;
  const pct = (min) => (Math.max(0, Math.min(1440, min)) / 1440 * 100) + "%";
  const toMin = (hhmm) => { const [h, m] = String(hhmm || "").split(":").map(Number); return isNaN(h) ? null : h * 60 + (m || 0); };
  const fmt = (min) => Math.floor(min / 60) + "h" + (min % 60 ? String(min % 60).padStart(2, "0") : "");
  const el = (cls, parent, text) => { const e = document.createElement("div"); e.className = cls; if (text != null) e.textContent = text; parent.appendChild(e); return e; };

  window.Ruban = function (root, options) {
    const compact = !!(options && options.compact);
    if (!styled) { const st = document.createElement("style"); st.textContent = CSS; document.head.appendChild(st); styled = true; }
    let data = { prayers: {}, events: [], nyOpen: "15:30", holiday: "" };

    function render() {
      const now = new Date(), nowMin = now.getHours() * 60 + now.getMinutes();
      const wd = now.getDay();  // 0 = dimanche
      const closed = wd === 6 || (wd === 0 && nowMin < 23 * 60) || (wd === 5 && nowMin >= 23 * 60);
      root.textContent = "";
      const box = el("rb", root);
      const top = el("rb-top", box), lane = el("rb-lane", box), bottom = el("rb-bottom", box);

      // sessions (heures de Paris)
      const ny = toMin(data.nyOpen) ?? 930;
      const sessions = [["Asie", 60, 600], ["Londres", 540, 1050]];
      if (!data.holiday) sessions.push(["New York", ny, 1320]);
      const cuts = [...new Set(sessions.flatMap(s => [s[1], s[2]]))].sort((a, b) => a - b);
      for (let i = 0; i < cuts.length - 1; i++) {
        const a = cuts[i], b = cuts[i + 1], n = sessions.filter(s => s[1] <= a && s[2] >= b).length;
        if (!n) continue;
        const seg = el("rb-seg" + (n > 1 ? " s2" : "") + (!closed && nowMin >= a && nowMin < b ? " on" : ""), lane);
        seg.style.left = pct(a); seg.style.width = pct(b - a);
      }
      const solo = { "Asie": [60, 540], "Londres": [600, ny], "New York": [1050, 1320] };
      for (const [name] of sessions) {
        const [a, b] = solo[name]; if (b - a < (compact ? 150 : 90)) continue;
        const on = !closed && sessions.some(s => s[0] === name && nowMin >= s[1] && nowMin < s[2]);
        const t = el("rb-name" + (on ? " on" : ""), lane, compact && name === "New York" ? "NY" : name);
        t.style.left = pct((a + b) / 2);
      }
      if (data.holiday && !closed) { const t = el("rb-name", lane, "Férié US"); t.style.left = pct(1185); }
      if (closed) el("rb-closed", lane, "Marché fermé jusqu'à dimanche 23h");
      const past = el("rb-past", lane); past.style.width = pct(nowMin);

      // annonces du jour (au-dessus)
      const evs = (data.events || []).map(e => ({ ...e, m: toMin(e.t) })).filter(e => e.m != null);
      const nextEv = evs.find(e => e.m >= nowMin - 5);
      for (const e of evs) {
        const tick = el("rb-ev" + (e === nextEv ? " next" : ""), top); tick.style.left = pct(e.m);
        if (!compact && e === nextEv) { const t = el("rb-ev-t", top, fmt(e.m)); t.style.left = pct(e.m); }
      }

      // prières et heures (en dessous)
      const prs = Object.entries(data.prayers || {}).map(([k, v]) => [k, toMin(v)]).filter(p => p[1] != null);
      const nextPr = prs.find(p => p[1] > nowMin);
      for (const [k, m] of prs) {
        const tick = el("rb-pr", bottom); tick.style.left = pct(m);
        const label = compact ? k[0] : (k + " " + fmt(m));
        const t = el("rb-pr-t" + (nextPr && nextPr[0] === k ? " next" : ""), bottom, label);
        t.style.left = pct(m);
      }
      if (!compact) for (const h of [0, 12, 24]) {
        const t = el("rb-h", bottom, h + "h"); t.style.left = pct(h * 60);
        t.style.transform = h === 0 ? "none" : h === 24 ? "translateX(-100%)" : "translateX(-50%)";
        if (h === 12) t.style.display = "none";  // midi : laissé aux prières
      }

      // maintenant
      const needle = el("rb-now", lane.parentNode); needle.style.left = pct(nowMin);
      needle.style.top = "0"; needle.style.bottom = "0";
      if (!compact) {
        const t = el("rb-now-t", top, fmt(nowMin)); t.style.left = pct(nowMin);
        const pxPerMin = (root.clientWidth || 800) / 1440;  // pas de chevauchement avec l'heure de l'annonce
        if (nextEv && Math.abs(nextEv.m - nowMin) * pxPerMin < 70) t.style.display = "none";
      }
    }

    render();
    setInterval(render, 30000);
    return {
      update(d) { data = Object.assign({}, data, d || {}); render(); },
      next() {  // prochaine annonce et prochaine prière (pour les textes autour du ruban)
        const now = new Date(), nowMin = now.getHours() * 60 + now.getMinutes();
        const ev = (data.events || []).map(e => ({ ...e, m: toMin(e.t) })).find(e => e.m != null && e.m >= nowMin - 5);
        const pr = Object.entries(data.prayers || {}).map(([k, v]) => ({ name: k, m: toMin(v) })).find(p => p.m != null && p.m > nowMin);
        return { event: ev ? { ...ev, inMin: ev.m - nowMin } : null, prayer: pr ? { ...pr, inMin: pr.m - nowMin } : null };
      },
    };
  };
  window.Ruban.fmt = fmt;
  window.Ruban.duree = (min) => min < 60 ? min + " min" : Math.floor(min / 60) + " h " + String(min % 60).padStart(2, "0");
})();
