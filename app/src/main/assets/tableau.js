// Tableau de bord du journal de trading : ce qui marche, ce qui coûte.
// renderTableau(element, données de /api/journal) — utilisé par le PC (/tableau) et l'appli Android.
(function () {
  const CSS = `
  .tb { color: var(--os, #f2e7e2); font-variant-numeric: tabular-nums; }
  .tb-periods { display: flex; gap: 6px; margin-bottom: 18px; }
  .tb-periods button { flex: 1; padding: 9px 0; border-radius: 10px; border: 1px solid rgba(210,167,90,.25);
    background: transparent; color: var(--cendre, #8c7774); font: inherit; font-size: 14px; }
  .tb-periods button[aria-pressed="true"] { background: rgba(210,167,90,.16); color: var(--or-clair, #f0d296); border-color: rgba(210,167,90,.6); }
  .tb-figs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px 12px; padding-bottom: 18px;
    border-bottom: 1px solid rgba(210,167,90,.16); }
  .tb-fig b { display: block; font-family: "Barlow Condensed", sans-serif; font-weight: 400; font-size: 28px; line-height: 1.05; }
  .tb-fig span { font-size: 12.5px; color: var(--cendre, #8c7774); }
  .tb-sec { padding: 18px 0 6px; border-bottom: 1px solid rgba(210,167,90,.1); }
  .tb-sec h3 { font-size: 15px; font-weight: 600; color: var(--or, #d2a75a); margin-bottom: 4px; }
  .tb-sec p.tb-note { font-size: 13.5px; color: rgba(242,231,226,.75); margin-bottom: 10px; line-height: 1.4; }
  .tb-row { display: grid; grid-template-columns: minmax(7.5em, 11em) 1fr 5.2em; align-items: center; gap: 10px; padding: 5px 0; font-size: 14px; }
  .tb-row small { display: block; font-size: 11.5px; color: var(--cendre, #8c7774); }
  .tb-bar { position: relative; height: 10px; }
  .tb-bar::before { content: ""; position: absolute; left: 50%; top: -3px; bottom: -3px; width: 1px; background: rgba(242,231,226,.18); }
  .tb-bar i { position: absolute; top: 0; bottom: 0; border-radius: 2px; }
  .tb-bar i.p { left: 50%; background: var(--gain, #5cc58c); }
  .tb-bar i.n { right: 50%; background: var(--braise-vive, #ff4a3d); }
  .tb-val { text-align: right; font-family: "Barlow Condensed", sans-serif; font-size: 17px; }
  .up { color: var(--gain, #5cc58c); } .down { color: var(--braise-vive, #ff4a3d); }
  .tb-curve { width: 100%; height: 150px; display: block; margin-top: 6px; }
  .tb-empty { padding: 30px 4px; color: rgba(242,231,226,.8); line-height: 1.5; }
  `;
  let styled = false;
  const money = (v) => (v > 0 ? "+" : v < 0 ? "−" : "") + Math.abs(Math.round(v)).toLocaleString("fr-FR") + " $";
  const mk = (tag, cls, text, parent) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; if (parent) parent.appendChild(e); return e; };

  function bars(parent, groups, sortFn) {
    const entries = Object.entries(groups || {});
    if (sortFn) entries.sort(sortFn);
    const max = Math.max(1, ...entries.map(([, g]) => Math.abs(g.pnl)));
    for (const [name, g] of entries) {
      const row = mk("div", "tb-row", null, parent);
      const lab = mk("div", null, name, row); mk("small", null, `${g.n} trade${g.n > 1 ? "s" : ""}, ${g.winrate} % gagnants`, lab);
      const bar = mk("div", "tb-bar", null, row), i = mk("i", g.pnl >= 0 ? "p" : "n", null, bar);
      i.style.width = (Math.abs(g.pnl) / max * 50) + "%";
      mk("div", "tb-val " + (g.pnl > 0 ? "up" : g.pnl < 0 ? "down" : ""), money(g.pnl), row);
    }
  }

  function curve(parent, pts) {
    if (!pts || pts.length < 2) return;
    const W = 600, H = 150, pad = 6, vals = pts.map(p => p[1]).concat([0]);
    const lo = Math.min(...vals), hi = Math.max(...vals), span = (hi - lo) || 1;
    const x = (i) => pad + i / (pts.length - 1) * (W - 2 * pad), y = (v) => pad + (hi - v) / span * (H - 2 * pad);
    const line = pts.map((p, i) => (i ? "L" : "M") + x(i).toFixed(1) + " " + y(p[1]).toFixed(1)).join(" ");
    const last = pts[pts.length - 1][1], color = last >= 0 ? "#d2a75a" : "#ff4a3d";
    const ns = "http://www.w3.org/2000/svg", svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", `0 0 ${W} ${H}`); svg.setAttribute("preserveAspectRatio", "none"); svg.setAttribute("class", "tb-curve");
    svg.setAttribute("role", "img"); svg.setAttribute("aria-label", "Courbe des gains et pertes cumulés");
    const add = (tag, attrs) => { const e = document.createElementNS(ns, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); svg.appendChild(e); return e; };
    add("line", { x1: 0, x2: W, y1: y(0), y2: y(0), stroke: "rgba(242,231,226,.25)", "stroke-dasharray": "4 4", "vector-effect": "non-scaling-stroke" });
    add("path", { d: line + ` L${x(pts.length - 1)} ${y(0)} L${x(0)} ${y(0)} Z`, fill: color, opacity: .12 });
    add("path", { d: line, fill: "none", stroke: color, "stroke-width": 2, "vector-effect": "non-scaling-stroke", "stroke-linejoin": "round" });
    parent.appendChild(svg);
  }

  function insights(st) {
    const out = [], ck = st.checklist || {}, nw = st.news || {};
    if (ck["complète"] && ck["incomplète"])
      out.push(`Checklist complète : ${ck["complète"].winrate} % gagnants. Incomplète : ${ck["incomplète"].winrate} %.`);
    if (nw["près d'une annonce"] && nw["près d'une annonce"].n)
      out.push(`Près d'une annonce : ${money(nw["près d'une annonce"].pnl)} sur ${nw["près d'une annonce"].n} trades.`);
    const s = Object.entries(st.by_session || {}).filter(([, g]) => g.n >= 2).sort((a, b) => b[1].pnl - a[1].pnl);
    if (s.length > 1) out.push(`Ta meilleure session : ${s[0][0]}. La plus chère : ${s[s.length - 1][0]}.`);
    return out;
  }

  window.renderTableau = function (root, data) {
    if (!styled) { const st = document.createElement("style"); st.textContent = CSS; document.head.appendChild(st); styled = true; }
    let period = "d60";
    function draw() {
      root.textContent = "";
      const box = mk("div", "tb", null, root);
      const per = mk("div", "tb-periods", null, box);
      for (const [k, label] of [["d7", "7 jours"], ["d60", "60 jours"], ["all", "Tout"]]) {
        const b = mk("button", null, label, per); b.type = "button"; b.setAttribute("aria-pressed", String(k === period));
        b.onclick = () => { period = k; draw(); };
      }
      const st = data[period];
      if (!st || !st.n) {
        mk("div", "tb-empty", "Pas encore de trade clôturé sur cette période. Après un trade, dis « je suis sorti de ma position », " +
          "ou télécharge ton export Tradovate : Jarvis l'importe tout seul.", box);
        return;
      }
      const figs = mk("div", "tb-figs", null, box);
      const fig = (v, label, cls) => { const f = mk("div", "tb-fig", null, figs); mk("b", cls || "", v, f); mk("span", null, label, f); };
      fig(money(st.total), "résultat", st.total >= 0 ? "up" : "down");
      fig(st.winrate + " %", "trades gagnants");
      fig(String(st.n), "trades");
      fig(money(st.avg_win), "gain moyen", "up");
      fig(money(st.avg_loss), "perte moyenne", "down");
      fig(st.profit_factor == null ? "—" : String(st.profit_factor).replace(".", ","), "facteur de profit");

      let sec = mk("div", "tb-sec", null, box); mk("h3", null, "Courbe", sec);
      mk("p", "tb-note", `Pire creux ${money(st.max_drawdown)}, plus longue série de pertes : ${st.max_losing_streak}.`, sec);
      curve(sec, st.curve);
      const ins = insights(st);
      if (ins.length) { sec = mk("div", "tb-sec", null, box); mk("h3", null, "À retenir", sec); for (const t of ins) mk("p", "tb-note", t, sec); }
      sec = mk("div", "tb-sec", null, box); mk("h3", null, "Par session", sec); bars(sec, st.by_session, (a, b) => b[1].pnl - a[1].pnl);
      sec = mk("div", "tb-sec", null, box); mk("h3", null, "Par jour de la semaine", sec); bars(sec, st.by_weekday);
      sec = mk("div", "tb-sec", null, box); mk("h3", null, "Par heure d'entrée", sec); bars(sec, st.by_hour);
      sec = mk("div", "tb-sec", null, box); mk("h3", null, "Checklist Fibo", sec); bars(sec, st.checklist);
      sec = mk("div", "tb-sec", null, box); mk("h3", null, "Annonces économiques", sec); bars(sec, st.news);
    }
    draw();
  };
})();
