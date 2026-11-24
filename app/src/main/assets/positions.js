// Onglet « Positions » (appli 5.7, demandé par Jabran le 24/11) : les traders de l'université sont-ils en position,
// et quels sont leurs résultats. LECTURE SEULE : aucun bouton n'envoie d'ordre, c'est de l'argent fictif (démo).
// Données : /api/trading et /api/demo de l'université (relayées par Jarvis sur /universite/k/<jeton>/).
// Positions.dessiner(element, trading, demo, prixGold) — trading obligatoire, demo et prixGold facultatifs.
(function () {
  const CSS = `
  .ps { color: var(--os, #f2e7e2); font-variant-numeric: tabular-nums; }
  .ps-etat { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-radius: 16px; margin-bottom: 14px;
    background: rgba(242, 231, 226, .04); border: 1px solid rgba(242, 231, 226, .08); font-size: 15.5px; line-height: 1.35; }
  .ps-etat b { font-weight: 600; }
  .ps-etat.oui { border-color: rgba(92, 197, 140, .45); background: rgba(92, 197, 140, .07); }
  .ps-led { flex: none; width: 10px; height: 10px; border-radius: 50%; background: var(--cendre, #a48f8b); }
  .ps-etat.oui .ps-led { background: var(--gain, #5cc58c); box-shadow: 0 0 8px var(--gain, #5cc58c); animation: psp 1.6s ease-in-out infinite; }
  @keyframes psp { 50% { opacity: .35; } }
  .ps h3 { font-size: 13px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: var(--cendre, #a48f8b); margin: 18px 0 8px; }
  .ps-pos { padding: 12px 14px; border-radius: 16px; margin-bottom: 8px; background: rgba(242, 231, 226, .045);
    border: 1px solid rgba(242, 231, 226, .08); border-left: 3px solid var(--gain, #5cc58c); }
  .ps-pos.vente { border-left-color: var(--braise-vive, #ff4a3d); }
  .ps-haut { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
  .ps-qui { font-size: 15px; font-weight: 600; }
  .ps-qui small, .ps-sub { display: block; font-size: 12.5px; font-weight: 400; color: var(--cendre, #a48f8b); }
  .ps-sens { font-family: "Barlow Condensed", sans-serif; font-size: 17px; letter-spacing: .05em; color: var(--gain, #5cc58c); white-space: nowrap; }
  .ps-pos.vente .ps-sens { color: var(--braise-vive, #ff4a3d); }
  .ps-niv { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-top: 10px; }
  .ps-niv div { font-size: 11.5px; color: var(--cendre, #a48f8b); }
  .ps-niv b { display: block; font-family: "Barlow Condensed", sans-serif; font-weight: 400; font-size: 17px; color: var(--os, #f2e7e2); }
  .ps-latent { margin-top: 8px; font-size: 14px; }
  .ps-latent b { font-family: "Barlow Condensed", sans-serif; font-weight: 500; font-size: 20px; }
  .ps-tr { display: grid; grid-template-columns: 1fr auto; gap: 2px 10px; align-items: center; padding: 10px 0;
    border-bottom: 1px solid rgba(210, 167, 90, .1); }
  .ps-tr:last-child { border-bottom: 0; }
  .ps-nom { font-size: 14.5px; line-height: 1.3; }
  .ps-res { text-align: right; font-family: "Barlow Condensed", sans-serif; font-size: 19px; }
  .ps-pastille { display: inline-block; margin-left: 6px; padding: 1px 7px; border-radius: 999px; font-size: 11.5px; vertical-align: 1px;
    color: var(--cendre, #a48f8b); border: 1px solid rgba(242, 231, 226, .14); }
  .ps-pastille.oui { color: #0c1a12; background: var(--gain, #5cc58c); border-color: var(--gain, #5cc58c); font-weight: 600; }
  .ps-hist { display: grid; grid-template-columns: auto 1fr auto; gap: 10px; align-items: baseline; padding: 7px 0; font-size: 14px;
    border-bottom: 1px solid rgba(210, 167, 90, .08); }
  .ps-hist:last-child { border-bottom: 0; }
  .ps-hist time { color: var(--cendre, #a48f8b); font-size: 12.5px; white-space: nowrap; }
  .ps-hist .ps-res { font-size: 17px; }
  .up { color: var(--gain, #5cc58c); } .down { color: var(--braise-vive, #ff4a3d); }
  .ps-vide { padding: 4px 0 8px; color: rgba(242, 231, 226, .7); font-size: 14px; line-height: 1.45; }
  .ps-note { margin-top: 16px; font-size: 12.5px; line-height: 1.45; color: var(--cendre, #a48f8b); }
  @media (max-width: 340px) { .ps-niv { grid-template-columns: repeat(2, 1fr); } }
  @media (prefers-reduced-motion: reduce) { .ps-etat.oui .ps-led { animation: none; } }`;
  const POINT = 10;  // micro or (MGC) : 1 point = 10 $ par contrat

  function el(tag, cls, texte) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (texte != null) e.textContent = texte;
    return e;
  }
  function px(x) { return x == null ? "—" : Number(x).toLocaleString("fr-FR", { minimumFractionDigits: 1, maximumFractionDigits: 1 }); }
  function dollars(v) {
    if (v == null || isNaN(v)) return "—";
    const n = Math.round(v);
    return (n > 0 ? "+" : n < 0 ? "−" : "") + Math.abs(n).toLocaleString("fr-FR") + " $";
  }
  function signe(v) { return v > 0 ? "up" : v < 0 ? "down" : ""; }
  function contrats(c) { return c == null ? "" : (Number.isInteger(c) ? c : Number(c).toLocaleString("fr-FR", { maximumFractionDigits: 3 })) + (c > 1 ? " contrats" : " contrat"); }
  function achat(sens) { return sens === "haussier" || sens === "long" || sens === "achat"; }
  function depuis(t) {
    if (!t) return "";
    const min = Math.max(0, Math.round((Date.now() / 1000 - t) / 60));
    if (min < 1) return "à l'instant";
    if (min < 60) return "depuis " + min + " min";
    const h = Math.floor(min / 60), m = min % 60;
    return "depuis " + h + " h" + (m ? String(m).padStart(2, "0") : "");
  }
  function heure(t) {
    const d = new Date((t || 0) * 1000), auj = new Date();
    const hm = d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    return d.toDateString() === auj.toDateString() ? hm : d.toLocaleDateString("fr-FR", { day: "numeric", month: "short" }) + " " + hm;
  }
  // gain ou perte en cours, estimé avec le prix du gold affiché dans l'appli (pas les frais)
  function latent(p, prix) {
    if (!prix || p.entree == null || p.contrats == null) return null;
    return (prix - p.entree) * (achat(p.sens) ? 1 : -1) * POINT * p.contrats;
  }
  const ETAGES = { principal: "1er étage", asie: "Étage Asie", londres: "Étage Londres" };
  const MOTIF = { stop: "stop touché", objectif: "objectif touché", stop_suiveur: "stop suiveur", temps: "temps écoulé" };

  // Toutes les positions ouvertes et tous les traders, depuis /api/trading (sans rien inventer)
  function lire(trading) {
    const etages = (trading && trading.etages) || [];
    const traders = [];
    for (const e of etages) for (const t of (e.traders || [])) traders.push(Object.assign({ etage: e.nom, etageCle: e.cle, en_session: e.en_session }, t));
    if (!etages.length) for (const t of ((trading && trading.traders) || [])) traders.push(t);
    return { traders, positions: (trading && trading.positions) || [] };
  }

  function carte(p, prix) {
    const c = el("div", "ps-pos" + (achat(p.sens) ? "" : " vente"));
    const haut = el("div", "ps-haut");
    const qui = el("div", "ps-qui", p.trader || p.compte || "Trader");
    qui.appendChild(el("small", "", [ETAGES[p.etage] || p.etage || "", depuis(p.ouvert), contrats(p.contrats)].filter(Boolean).join(" · ")));
    haut.append(qui, el("span", "ps-sens", achat(p.sens) ? "▲ ACHAT" : "▼ VENTE"));
    const niv = el("div", "ps-niv");
    const suiveur = p.stop_suiveur != null && p.sl != null && Math.abs(p.stop_suiveur - p.sl) > 1e-6 ? p.stop_suiveur : null;
    [["Entrée", p.entree], ["SL", p.sl], ["Stop suiveur", suiveur], ["TP", p.tp]].forEach(([n, v]) => {
      const d = el("div", "", n); d.appendChild(el("b", "", v == null ? "—" : px(v))); niv.appendChild(d);
    });
    c.append(haut, niv);
    const l = latent(p, prix);
    const ligne = el("div", "ps-latent");
    if (l != null) {
      ligne.append("En ce moment : ", el("b", signe(l), dollars(l)));
      ligne.appendChild(el("span", "ps-sub", "estimé avec le gold à " + px(prix) + " (sans les frais)"));
    } else if (p.risque_dollars != null) {
      ligne.append("Risque : ", el("b", "", Math.round(p.risque_dollars).toLocaleString("fr-FR") + " $"));
    }
    if (ligne.childNodes.length) c.appendChild(ligne);
    return c;
  }

  function ligneTrader(t, ouverts) {
    const r = el("div", "ps-tr");
    const nom = el("div", "ps-nom", t.nom || t.compte);
    const enPos = ouverts.has(t.compte) || !!t.en_cours;
    nom.appendChild(el("span", "ps-pastille" + (enPos ? " oui" : ""), enPos ? "en position" : "pas en position"));
    const bits = [];
    if (t.trades) bits.push(t.trades + (t.trades > 1 ? " trades" : " trade") + (t.reussite != null ? " · " + t.reussite + " % gagnés" : ""));
    else bits.push("aucun trade fermé");
    const etat = t.etat || {};
    if (etat.solde != null) bits.push("solde " + Math.round(etat.solde).toLocaleString("fr-FR") + " $");
    if (etat.phase) bits.push(etat.phase === "finance" ? "compte financé" : etat.phase === "evaluation" ? "en évaluation" : String(etat.phase));
    nom.appendChild(el("span", "ps-sub", bits.join(" · ")));
    r.append(nom, el("div", "ps-res " + signe(t.resultat || 0), t.trades ? dollars(t.resultat) : "—"));
    return r;
  }

  function dessiner(racine, trading, demo, prix) {
    if (!document.getElementById("ps-style")) {
      const st = document.createElement("style"); st.id = "ps-style"; st.textContent = CSS; document.head.appendChild(st);
    }
    const { traders, positions } = lire(trading);
    const box = el("div", "ps");
    // 1. en position ou pas
    const n = positions.length;
    const etat = el("div", "ps-etat" + (n ? " oui" : ""));
    etat.setAttribute("role", "status");
    etat.appendChild(el("i", "ps-led"));
    const txt = el("div");
    if (n) {
      const b = el("b", "", n === 1 ? "1 position ouverte" : n + " positions ouvertes");
      txt.append(b, " : " + [...new Set(positions.map(p => (p.trader || p.compte).replace(/\s*\(.*\)\s*$/, "") + (ETAGES[p.etage] && p.etage !== "principal" ? " (" + ETAGES[p.etage] + ")" : "")))].join(", "));
    } else {
      txt.append(el("b", "", "Personne n'est en position."));
      txt.appendChild(el("span", "ps-sub", "Les traders attendent le vote de l'équipe."));
    }
    etat.appendChild(txt);
    box.appendChild(etat);
    // 2. positions ouvertes
    if (n) {
      box.appendChild(el("h3", "", "Positions ouvertes"));
      positions.slice().sort((a, b) => (b.ouvert || 0) - (a.ouvert || 0)).forEach(p => box.appendChild(carte(p, prix)));
    }
    // 3. résultat de chaque trader, par étage
    const ouverts = new Set(positions.map(p => p.compte));
    const parEtage = new Map();
    for (const t of traders) { const k = t.etage || "Traders"; if (!parEtage.has(k)) parEtage.set(k, []); parEtage.get(k).push(t); }
    if (!traders.length) { box.appendChild(el("h3", "", "Résultats")); box.appendChild(el("p", "ps-vide", "L'université n'a pas encore de traders.")); }
    for (const [etage, liste] of parEtage) {
      const enSession = liste[0] && liste[0].en_session;
      box.appendChild(el("h3", "", "Résultats · " + etage + (enSession === false ? " (hors session)" : "")));
      liste.forEach(t => box.appendChild(ligneTrader(t, ouverts)));
    }
    // 4. derniers trades fermés (trader série du 1er étage, fourni par /api/demo)
    const fermes = ((demo && demo.historique) || []).filter(t => t.statut === "ferme").slice(0, 8);
    if (fermes.length) {
      box.appendChild(el("h3", "", "Derniers trades fermés · trader série"));
      for (const t of fermes) {
        const r = el("div", "ps-hist");
        const tm = el("time", "", heure(t.ferme)); r.appendChild(tm);
        r.appendChild(el("span", "", (achat(t.sens) ? "Achat " : "Vente ") + px(t.entree) + " → " + px(t.sortie) + (MOTIF[t.motif] ? " · " + MOTIF[t.motif] : "")));
        r.appendChild(el("span", "ps-res " + signe(t.pnl), dollars(t.pnl)));
        box.appendChild(r);
      }
    }
    box.appendChild(el("p", "ps-note", "Démo seulement : argent fictif, aucun ordre n'est passé. Cet onglet ne fait que regarder, il se met à jour tout seul toutes les 20 secondes."));
    racine.textContent = "";
    racine.appendChild(box);
  }

  window.Positions = { dessiner, lire, latent };
})();
