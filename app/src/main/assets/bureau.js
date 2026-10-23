// Caméra du bureau des agents IA : une « caméra de surveillance » qui filme la Salle des marchés.
// Les 6 spécialistes du desk (SMC, Fibo, Ichimoku…) et le chef de desk travaillent à leur bureau,
// et en bas l'équipe de Jarvis (stratégie, actus, Telegram…) avec un voyant par agent.
// Partagé par l'écran du PC (ui/index.html), l'appli web (ui/app.html), la page /bureau et l'appli Android.
//
// Utilisation : BureauCam.creer(conteneur, { charger: async () => donnéesDe/api/bureau, liste: élément facultatif })
// Tout est dessiné par le code (aucune image) : scène en petits pixels, puis textes et effets caméra en net.

(function () {
  "use strict";
  const LW = 320, LH = 180;                 // taille de la scène en « pixels de caméra »
  const FPS = 12;                           // image un peu saccadée, comme une vraie caméra (et léger pour le PC)
  const COUL = {
    mur: "#1b1012", mur2: "#140b0d", plinthe: "#2c1a17", sol: "#0f0809", sol2: "#130a0b",
    bois: "#3d2b22", bois2: "#271910", bord: "#5a4030", ecranOff: "#0b0f12", cadre: "#16181b",
    or: "#d2a75a", orClair: "#f0d296", rouge: "#ff4a3d", vert: "#5cc58c", gris: "#8c7774", bleu: "#3ea6ff",
  };
  const BIAIS = { haussier: COUL.vert, baissier: COUL.rouge, neutre: COUL.or };
  // allure de chaque spécialiste (chemise, cheveux, peau)
  const LOOK = {
    smc: ["#3b5b8c", "#1a1310", "#c99a74"], fibo: ["#8c3b3b", "#3a2416", "#e0b48c"],
    ichimoku: ["#2f6b55", "#0d0d0f", "#d8a982"], tendance: ["#6b4f8c", "#5a3a1c", "#b88560"],
    orderflow: ["#8c6a2f", "#222222", "#8f6446"], calendrier: ["#4a6d7a", "#6b5a4a", "#e6c09c"],
  };
  const LOOK_AUTRES = [["#5a5f6b", "#2a1d14", "#cf9f78"], ["#7a3f5a", "#111", "#a87454"]];
  const ETAT_AG = { ok: "en marche", pause: "en pause", panne: "EN PANNE", attente: "bientôt" };

  function px(c, x, y, w, h, col) { c.fillStyle = col; c.fillRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h)); }
  function hash(s) { let h = 7; for (const ch of String(s)) h = (h * 31 + ch.charCodeAt(0)) >>> 0; return h; }
  function heure(ts) { const d = ts ? new Date(ts * 1000) : new Date(); return d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" }); }

  // ------------------------------------------------------------------ ce que fait chacun
  function lireEtat(data) {
    const desk = data && data.salle && data.salle.desk;
    const ouverte = !!(data && data.salle && data.salle.ouverte);
    const enCours = !!(desk && desk.etat && desk.etat !== "fini");
    const recent = !!(desk && desk.etat === "fini" && desk.fin && Date.now() / 1000 - desk.fin < 3 * 3600);
    const actif = enCours || recent;
    const sps = (desk && desk.specialistes && desk.specialistes.length) ? desk.specialistes
      : Object.keys(LOOK).map(k => ({ cle: k, nom: { smc: "SMC / ICT", fibo: "Fibonacci", ichimoku: "Ichimoku", tendance: "Tendance", orderflow: "Order flow", calendrier: "Macro & calendrier" }[k], etat: "repos" }));
    const analystes = sps.slice(0, 6).map(sp => {
      let mode = "repos";
      if (actif) {
        if (sp.erreur || sp.etat === "erreur") mode = "erreur";
        else if (enCours && sp.etat === "attente") mode = "travaille";
        else if (enCours && sp.etat === "debat") mode = "debat";
        else if (sp.etat === "fini" || sp.etat === "debat") mode = "fini";
      }
      return Object.assign({}, sp, { mode });
    });
    let chef = "repos";
    if (enCours && /décision/i.test(desk.etape || "")) chef = "reflechit";
    else if (enCours) chef = "surveille";
    else if (recent && desk.decision) chef = "annonce";
    return { desk, ouverte, enCours, recent, actif, analystes, chef, agents: (data && data.agents) || [] };
  }

  function texteDecision(d) {
    if (!d) return "";
    return String(d.statut || d.decision || "").toUpperCase();
  }

  // ------------------------------------------------------------------ la caméra
  function creer(conteneur, opts) {
    opts = opts || {};
    const cv = document.createElement("canvas");
    cv.className = "bureau-cam";
    cv.setAttribute("role", "img");
    cv.setAttribute("aria-label", "Caméra du bureau des agents IA");
    cv.style.cssText = "display:block;width:100%;height:100%;background:#000;";
    conteneur.append(cv);
    const ctx = cv.getContext("2d");
    const scene = document.createElement("canvas"); scene.width = LW; scene.height = LH;
    const sc = scene.getContext("2d");
    // grain de l'image : 4 calques préparés une fois
    const grains = [0, 1, 2, 3].map(() => {
      const g = document.createElement("canvas"); g.width = 160; g.height = 90;
      const gc = g.getContext("2d"), im = gc.createImageData(160, 90);
      for (let i = 0; i < im.data.length; i += 4) { const v = Math.random() * 255; im.data[i] = im.data[i + 1] = im.data[i + 2] = v; im.data[i + 3] = 22; }
      gc.putImageData(im, 0, 0); return g;
    });
    let W = 0, H = 0, dpr = 1, lignes = null;
    let data = null, etat = lireEtat(null), erreurChargement = "";
    let t0 = performance.now(), dernier = 0, timer = null, poll = null, visible = true, marche = false;
    let bulle = { i: -1, depuis: 0 };
    const particules = [];

    function taille() {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = Math.max(1, conteneur.clientWidth); H = Math.max(1, conteneur.clientHeight || Math.round(W * 9 / 16));
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // lignes de balayage, préparées à la bonne taille
      lignes = document.createElement("canvas"); lignes.width = 2; lignes.height = 4;
      const lc = lignes.getContext("2d"); lc.fillStyle = "rgba(0,0,0,.28)"; lc.fillRect(0, 0, 2, 1); lc.fillRect(0, 2, 2, 1);
    }
    const ro = window.ResizeObserver ? new ResizeObserver(taille) : null;
    if (ro) ro.observe(conteneur); else addEventListener("resize", taille);
    taille();
    const io = window.IntersectionObserver ? new IntersectionObserver((e) => { visible = e[0].isIntersecting; }) : null;
    if (io) io.observe(cv);

    async function charger() {
      if (!opts.charger) return;
      try { data = await opts.charger(); erreurChargement = ""; etat = lireEtat(data); remplirListe(); }
      catch (e) { erreurChargement = (e && e.message === "code") ? "Code de l'appli incorrect" : "Signal perdu : Jarvis ne répond pas"; }
    }

    // ---------------------------------------------------------------- dessin de la scène (petits pixels)
    function decor(t) {
      const c = sc, sombre = !etat.ouverte && !etat.actif;
      px(c, 0, 0, LW, 46, COUL.mur);
      for (let x = 0; x < LW; x += 16) px(c, x, 0, 1, 44, COUL.mur2);
      px(c, 0, 44, LW, 3, COUL.plinthe);
      for (let y = 47; y < LH; y += 8) for (let x = (y / 8) % 2 ? 0 : 8; x < LW; x += 16) px(c, x, y, 8, 8, COUL.sol2);
      px(c, 0, 47, LW, LH - 47, "rgba(15,8,9,.55)");
      // grand écran du mur : courbe du gold
      px(c, 116, 5, 88, 35, COUL.cadre);
      px(c, 118, 7, 84, 31, sombre ? COUL.ecranOff : "#07090c");
      if (!sombre) {
        c.strokeStyle = COUL.or; c.lineWidth = 1; c.beginPath();
        for (let i = 0; i <= 80; i += 2) {
          const y = 26 - 7 * Math.sin((i + t * 6) / 11) - 3 * Math.sin((i + t * 15) / 4.3);
          if (i === 0) c.moveTo(120 + i, y); else c.lineTo(120 + i, y);
        }
        c.stroke();
      }
      px(c, 156, 40, 8, 5, COUL.cadre);
      // horloge et fenêtre (la ville la nuit)
      c.fillStyle = "#2a1d1a"; c.beginPath(); c.arc(40, 20, 8, 0, 7); c.fill();
      c.fillStyle = "#d9cfc6"; c.beginPath(); c.arc(40, 20, 6.5, 0, 7); c.fill();
      const d = new Date(), a1 = (d.getMinutes() / 60) * 6.283 - 1.571, a2 = ((d.getHours() % 12) / 12 + d.getMinutes() / 720) * 6.283 - 1.571;
      c.strokeStyle = "#1a1210"; c.beginPath(); c.moveTo(40, 20); c.lineTo(40 + Math.cos(a1) * 5.5, 20 + Math.sin(a1) * 5.5);
      c.moveTo(40, 20); c.lineTo(40 + Math.cos(a2) * 3.5, 20 + Math.sin(a2) * 3.5); c.stroke();
      px(c, 240, 6, 62, 30, "#20262d"); px(c, 242, 8, 58, 26, "#0a1220");
      for (let i = 0; i < 18; i++) {
        const h = hash("b" + i), bx = 243 + (h % 55), bh = 6 + (h >> 4) % 16;
        px(c, bx, 34 - bh, 4, bh, "#111a28");
        if (((h >> 8) + Math.floor(t / 3)) % 5) px(c, bx + 1, 34 - bh + 2, 1, 1, "#e8c46a");
      }
      px(c, 270, 8, 1, 26, "#20262d");
      // plante et machine à café
      px(c, 8, 34, 10, 12, "#3b2a20"); px(c, 6, 22, 4, 14, "#2f5c3a"); px(c, 12, 18, 4, 18, "#386d45"); px(c, 16, 25, 4, 10, "#2f5c3a");
      px(c, 302, 30, 12, 16, "#2a2c30"); px(c, 304, 33, 8, 4, etat.actif ? "#c23a2f" : "#3a1214");
    }

    function personnage(x, y, look, mode, t, i, regard) {
      const c = sc, [chemise, cheveux, peau] = look;
      const tape = mode === "travaille", dort = mode === "repos";
      const tete = dort ? 1 : (mode === "debat" ? Math.round(Math.sin(t * 3 + i) * 0.6) : 0);
      px(c, x + 13, y - 19, 14, 17, "#1c1416");                         // dossier de la chaise
      px(c, x + 15, y - 11, 10, 11, chemise);                           // buste
      px(c, x + 16, y - 20 + tete, 8, 8, peau);                         // tête
      px(c, x + 16, y - 21 + tete, 8, 3, cheveux);
      px(c, x + 15, y - 20 + tete, 1, 4, cheveux); px(c, x + 24, y - 20 + tete, 1, 4, cheveux);
      if (dort) { px(c, x + 17, y - 15 + tete, 2, 1, "#4a2c22"); px(c, x + 21, y - 15 + tete, 2, 1, "#4a2c22"); }
      else { const o = regard || 0; px(c, x + 18 + o, y - 16 + tete, 1, 2, "#1a0e0b"); px(c, x + 21 + o, y - 16 + tete, 1, 2, "#1a0e0b"); }
      // bras : on tape au clavier, ou on parle avec les mains pendant le débat
      const b1 = tape ? (Math.floor(t * 8 + i) % 2) : 0, b2 = tape ? 1 - b1 : 0;
      const g = mode === "debat" && Math.floor(t * 2 + i) % 3 === 0 ? -3 : 0;
      px(c, x + 13, y - 6 - b1 + g, 2, 6, chemise); px(c, x + 25, y - 6 - b2, 2, 6, chemise);
      px(c, x + 13, y - 1 - b1 + g, 2, 1, peau); px(c, x + 25, y - 1 - b2, 2, 1, peau);
    }

    function bureau(x, y, sp, i, t) {
      const c = sc, look = LOOK[sp.cle] || LOOK_AUTRES[i % LOOK_AUTRES.length];
      px(c, x - 2, y + 19, 46, 3, "rgba(0,0,0,.45)");
      // regard vers le voisin pendant le débat
      const regard = sp.mode === "debat" ? (Math.floor(t / 2 + i) % 2 ? 1 : -1) : 0;
      personnage(x, y, look, sp.mode, t, i, regard);
      px(c, x, y, 40, 6, COUL.bois); px(c, x, y, 40, 1, COUL.bord);
      px(c, x, y + 6, 40, 13, COUL.bois2); px(c, x + 2, y + 8, 36, 1, "#33231a");
      px(c, x + 16, y + 1, 10, 2, "#3b3e44");                           // clavier
      px(c, x + 33, y - 2, 3, 3, "#d9cfc6");                            // tasse
      // écran : son contenu dit ce que fait le spécialiste
      px(c, x + 6, y - 2, 3, 2, COUL.cadre);
      px(c, x, y - 13, 15, 11, COUL.cadre);
      const ex = x + 1, ey = y - 12;
      if (sp.mode === "repos") { px(c, ex, ey, 13, 9, COUL.ecranOff); if (Math.floor(t + i) % 4 === 0) px(c, ex + 1 + (i * 3) % 10, ey + 2 + i % 5, 1, 1, "#24303a"); }
      else if (sp.mode === "erreur") { px(c, ex, ey, 13, 9, Math.floor(t * 3) % 2 ? "#5a0c0c" : "#2a0606"); px(c, ex + 5, ey + 2, 3, 5, COUL.rouge); }
      else if (sp.mode === "travaille") {
        px(c, ex, ey, 13, 9, "#081018");
        for (let l = 0; l < 4; l++) px(c, ex + 1, ey + 1 + l * 2, 2 + ((hash(i + "l" + (l + Math.floor(t * 4))) % 10)), 1, l % 2 ? "#3ea6ff" : "#8fd0ff");
      } else {
        const col = BIAIS[sp.biais] || COUL.or;
        px(c, ex, ey, 13, 9, "#0a0c0e");
        if (sp.biais === "haussier") { px(c, ex + 6, ey + 2, 1, 6, col); px(c, ex + 4, ey + 4, 5, 1, col); px(c, ex + 5, ey + 3, 3, 1, col); }
        else if (sp.biais === "baissier") { px(c, ex + 6, ey + 1, 1, 6, col); px(c, ex + 4, ey + 5, 5, 1, col); px(c, ex + 5, ey + 6, 3, 1, col); }
        else px(c, ex + 3, ey + 4, 7, 1, col);
        c.fillStyle = col + "22"; c.fillRect(x - 3, y - 15, 21, 16);    // lueur de l'écran
      }
      if (sp.mode === "repos" && Math.random() < 0.012) particules.push({ x: x + 24, y: y - 22, vx: 0.15, vy: -0.25, vie: 40, z: true });
    }

    function chef(t) {
      const c = sc, x = 238, y = 92, mode = etat.chef;
      px(c, x - 4, y + 20, 72, 3, "rgba(0,0,0,.45)");
      const dx = mode === "reflechit" ? Math.round(Math.sin(t * 1.3) * 16) : 0;
      const bx = x + 26 + dx, by = y - 30;
      // le chef de desk, debout : costume sombre, cravate dorée
      px(c, bx + 1, by + 26, 3, 8, "#15151a"); px(c, bx + 6, by + 26, 3, 8, "#15151a");
      px(c, bx - 1, by + 10, 12, 17, "#1f2230"); px(c, bx + 4, by + 10, 2, 9, COUL.or);
      px(c, bx + 1, by + 1, 8, 9, "#c99a74"); px(c, bx + 1, by, 8, 3, "#2a2a2a");
      if (mode !== "repos") { px(c, bx + 3, by + 5, 1, 1, "#140c0a"); px(c, bx + 6, by + 5, 1, 1, "#140c0a"); }
      else { px(c, bx + 2, by + 5, 2, 1, "#4a2c22"); px(c, bx + 6, by + 5, 2, 1, "#4a2c22"); }
      if (mode === "annonce") { px(c, bx + 11, by + 3, 2, 9, "#1f2230"); px(c, bx + 11, by + 1, 2, 2, "#c99a74"); }
      else if (mode === "reflechit") { px(c, bx - 1, by + 14, 12, 3, "#262a3a"); }
      else { px(c, bx - 3, by + 11, 2, 11, "#1f2230"); px(c, bx + 11, by + 11, 2, 11, "#1f2230"); }
      // grand bureau du chef
      px(c, x, y, 64, 7, COUL.bois); px(c, x, y, 64, 1, COUL.bord);
      px(c, x, y + 7, 64, 13, COUL.bois2); px(c, x + 26, y + 10, 12, 6, "#33231a");
      px(c, x + 2, y - 12, 18, 12, COUL.cadre);
      const d = etat.desk && etat.desk.decision, col = d ? (/achat/i.test(texteDecision(d)) ? COUL.vert : /vente/i.test(texteDecision(d)) ? COUL.rouge : COUL.or) : null;
      px(c, x + 3, y - 11, 16, 9, col && mode === "annonce" ? col + "55" : (mode === "repos" ? COUL.ecranOff : "#081018"));
      if (mode !== "repos" && !col) for (let l = 0; l < 3; l++) px(c, x + 4, y - 10 + l * 3, 4 + (hash("c" + l + Math.floor(t * 2)) % 10), 1, "#8fd0ff");
      px(c, x + 50, y - 3, 8, 3, "#d2a75a"); px(c, x + 51, y - 4, 6, 1, "#f0d296");   // lingot sur le bureau
    }

    function equipe(t) {
      const c = sc, ags = etat.agents, n = ags.length;
      if (!n) return [];
      const y = 146, slot = Math.min(26, 304 / n), x0 = (LW - slot * n) / 2;
      px(c, 0, y + 10, LW, 14, "#0c0607"); px(c, 0, y + 10, LW, 1, "#2a1a17");
      const places = [];
      ags.forEach((a, i) => {
        const x = Math.round(x0 + i * slot + slot / 2 - 7);
        places.push(x + 7);
        px(c, x - 1, y + 7, 16, 3, "#2d1f18");                          // petite table
        px(c, x + 1, y - 2, 6, 6, COUL.cadre);                         // terminal
        const allume = a.etat === "ok" || a.etat === "panne";
        px(c, x + 2, y - 1, 4, 4, a.etat === "panne" ? (Math.floor(t * 4) % 2 ? "#6a0c0c" : "#2a0606") : allume ? "#0d2a3a" : COUL.ecranOff);
        if (a.etat === "attente") return;                              // place vide : agent pas encore prêt
        // le robot de l'agent
        const b = a.etat === "ok" ? Math.floor(t * 6 + i) % 2 : 0;
        px(c, x + 8, y - 1, 6, 7, "#5d6a78"); px(c, x + 8, y - 7, 6, 5, "#7c8a99");
        const yeux = a.etat === "ok" ? "#8fd0ff" : a.etat === "panne" ? COUL.rouge : "#3a4450";
        px(c, x + 9, y - 5, 1, 1, yeux); px(c, x + 12, y - 5, 1, 1, yeux);
        px(c, x + 10, y - 9, 1, 2, "#7c8a99");
        const lampe = a.etat === "ok" ? (Math.floor(t * 2 + i) % 3 ? COUL.vert : "#2b5a40") : a.etat === "pause" ? COUL.or : (Math.floor(t * 5) % 2 ? COUL.rouge : "#5a0c0c");
        px(c, x + 10, y - 10, 1, 1, lampe);
        px(c, x + 7, y + 1 - b, 1, 3, "#5d6a78");
        if (a.etat === "panne" && Math.random() < 0.25) particules.push({ x: x + 11, y: y - 9, vx: (Math.random() - .5) * .3, vy: -0.4, vie: 26, fumee: true });
        if (a.etat === "pause" && Math.random() < 0.01) particules.push({ x: x + 14, y: y - 9, vx: 0.12, vy: -0.22, vie: 34, z: true });
      });
      return places;
    }

    function dessinerParticules() {
      for (let i = particules.length - 1; i >= 0; i--) {
        const p = particules[i]; p.x += p.vx; p.y += p.vy; p.vie--;
        if (p.vie <= 0) { particules.splice(i, 1); continue; }
        if (p.z) { sc.fillStyle = "rgba(240,210,150," + Math.min(1, p.vie / 20) + ")"; sc.fillRect(p.x, p.y, 3, 1); sc.fillRect(p.x + 2, p.y + 1, 1, 1); sc.fillRect(p.x, p.y + 2, 3, 1); }
        else { sc.fillStyle = "rgba(120,120,125," + (p.vie / 60) + ")"; sc.fillRect(p.x, p.y, 2 + (26 - p.vie) / 8, 2); }
      }
      if (particules.length > 80) particules.splice(0, particules.length - 80);
    }

    const POS = [[30, 70], [92, 70], [154, 70], [30, 116], [92, 116], [154, 116]];

    // ---------------------------------------------------------------- textes et effets caméra (en net)
    function bulleTexte(c, texte, x, y, maxW, taillePolice, couleur) {
      c.font = `500 ${taillePolice}px "Barlow", "Segoe UI", sans-serif`;
      c.textAlign = "left";
      const mots = texte.split(" "), lignesT = [];
      let l = "";
      for (const m of mots) { const essai = l ? l + " " + m : m; if (c.measureText(essai).width > maxW && l) { lignesT.push(l); l = m; } else l = essai; if (lignesT.length >= 3) break; }
      if (lignesT.length < 3 && l) lignesT.push(l);
      if (lignesT.length === 3 && texte.length > lignesT.join(" ").length) lignesT[2] = lignesT[2].replace(/.?$/, "…");
      const lh = taillePolice * 1.22, bw = Math.max(...lignesT.map(s => c.measureText(s).width)) + taillePolice * 1.1, bh = lh * lignesT.length + taillePolice * 0.7;
      let bx = Math.max(4, Math.min(W - bw - 4, x - bw / 2)), by = Math.max(taillePolice * 2.2, y - bh - taillePolice * 0.6);
      c.fillStyle = "rgba(8,5,6,.88)"; c.strokeStyle = couleur; c.lineWidth = 1;
      c.beginPath(); c.roundRect ? c.roundRect(bx, by, bw, bh, 6) : c.rect(bx, by, bw, bh); c.fill(); c.stroke();
      c.beginPath(); c.moveTo(x - 4, by + bh); c.lineTo(x, by + bh + taillePolice * 0.55); c.lineTo(x + 4, by + bh); c.fill();
      c.fillStyle = "#f2e7e2";
      lignesT.forEach((s, i) => c.fillText(s, bx + taillePolice * 0.55, by + taillePolice * 0.35 + lh * (i + 0.8)));
    }

    function surcouche(t, s, ox, oy, places) {
      const c = ctx, P = (v) => v * s;
      const f = Math.max(9, Math.min(15, 3.3 * s));
      c.textBaseline = "alphabetic";
      // noms des spécialistes sous leur bureau
      c.textAlign = "center";
      etat.analystes.forEach((sp, i) => {
        const [x, y] = POS[i];
        c.font = `600 ${f}px "Barlow Condensed", "Bahnschrift", sans-serif`;
        const col = sp.mode === "erreur" ? COUL.rouge : (sp.mode === "fini" || sp.mode === "debat") && BIAIS[sp.biais] ? BIAIS[sp.biais] : "rgba(242,231,226,.75)";
        c.fillStyle = "rgba(0,0,0,.55)"; const tw = c.measureText(sp.nom || "").width + 8;
        c.fillRect(ox + P(x + 20) - tw / 2, oy + P(y + 12) - f * 0.85, tw, f * 1.15);
        c.fillStyle = col; c.fillText(sp.nom || "", ox + P(x + 20), oy + P(y + 12));
      });
      c.font = `600 ${f}px "Barlow Condensed", "Bahnschrift", sans-serif`;
      const cw = c.measureText("Chef de desk").width + 8;
      c.fillStyle = "rgba(0,0,0,.55)"; c.fillRect(ox + P(270) - cw / 2, oy + P(104) - f * 0.85, cw, f * 1.15);
      c.fillStyle = COUL.orClair; c.fillText("Chef de desk", ox + P(270), oy + P(104));
      // noms de l'équipe Jarvis (seulement si la caméra est assez grande pour les lire)
      if (s >= 2.3 && places.length) {
        c.font = `500 ${Math.max(9, 2.3 * s)}px "Barlow Condensed", "Bahnschrift", sans-serif`;
        etat.agents.forEach((a, i) => {
          c.fillStyle = a.etat === "panne" ? COUL.rouge : a.etat === "ok" ? "rgba(242,231,226,.7)" : COUL.gris;
          const nom = String(a.nom || "").split(/[ (]/)[0];
          c.fillText(nom, ox + P(places[i]), oy + P(166));
        });
      }
      // grand écran du mur : actif, étape, décision
      c.font = `600 ${Math.max(7, 3 * s)}px "Barlow Condensed", "Bahnschrift", sans-serif`;
      c.fillStyle = COUL.orClair; c.textAlign = "center";
      const d = etat.desk;
      if (d && etat.actif) {
        c.fillText(`${d.ticker || ""} · ${etat.enCours ? (d.etape || "Analyse") : "Dernier desk " + heure(d.fin)}`, ox + P(160), oy + P(13));
        if (d.decision && !etat.enCours) {
          const td = texteDecision(d.decision);
          c.fillStyle = /achat/i.test(td) ? COUL.vert : /vente/i.test(td) ? COUL.rouge : COUL.or;
          c.fillText(td, ox + P(160), oy + P(36));
        }
      } else if (etat.ouverte || etat.agents.length) {
        c.fillText(etat.ouverte ? "Desk au repos" : "Salle fermée", ox + P(160), oy + P(13));
      }
      // bulle : un spécialiste (ou le chef) dit son avis, chacun son tour toutes les 5 s
      const parlants = etat.analystes.map((sp, i) => [sp, i]).filter(([sp]) => sp.phrase && (sp.mode === "debat" || sp.mode === "fini" || sp.mode === "erreur"));
      const chefParle = etat.chef === "annonce" && d && d.decision;
      const total = parlants.length + (chefParle ? 1 : 0);
      if (total) {
        if (bulle.i < 0 || t - bulle.depuis > 5 || bulle.i >= total) { bulle.i = (bulle.i + 1) % total; bulle.depuis = t; }
        const maxW = Math.min(W * 0.42, 34 * f);
        if (bulle.i < parlants.length) {
          const [sp, i] = parlants[bulle.i], [x, y] = POS[i];
          bulleTexte(c, sp.phrase, ox + P(x + 20), oy + P(y - 22), maxW, f * 0.95, BIAIS[sp.biais] || COUL.or);
        } else {
          const dec = d.decision, txt = texteDecision(dec) + (dec.zone ? ` · zone ${dec.zone}` : "") + (dec.sl ? ` · stop ${dec.sl}` : "") + (dec.raison ? ` — ${dec.raison}` : "");
          bulleTexte(c, txt, ox + P(270), oy + P(58), maxW, f * 0.95, COUL.orClair);
        }
      } else if (etat.enCours && etat.analystes.some(sp => sp.mode === "travaille")) {
        c.font = `600 ${f * 1.4}px "Barlow", sans-serif`; c.fillStyle = "rgba(143,208,255,.85)";
        const pts = ".".repeat(1 + Math.floor(t * 2) % 3);
        etat.analystes.forEach((sp, i) => { if (sp.mode === "travaille") { const [x, y] = POS[i]; c.fillText(pts, ox + P(x + 20), oy + P(y - 24)); } });
      }
    }

    function effetsCamera(t) {
      const c = ctx, f = Math.max(9, Math.min(18, W / 40)), etroit = W < 560;
      // grain, lignes de balayage, coins sombres
      c.globalAlpha = 1; c.imageSmoothingEnabled = true;
      c.drawImage(grains[Math.floor(t * FPS) % 4], 0, 0, W, H);
      c.fillStyle = c.createPattern(lignes, "repeat"); c.fillRect(0, 0, W, H);
      const v = c.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.35, W / 2, H / 2, Math.max(W, H) * 0.75);
      v.addColorStop(0, "rgba(0,0,0,0)"); v.addColorStop(1, "rgba(0,0,0,.6)");
      c.fillStyle = v; c.fillRect(0, 0, W, H);
      if (Math.floor(t * 0.7) % 9 === 0) { c.fillStyle = "rgba(255,255,255,.05)"; c.fillRect(0, (t * 140) % H, W, 2 + (t * 7) % 4); }
      // bandeau caméra
      c.font = `600 ${f}px "Barlow Condensed", "Bahnschrift", monospace`;
      c.textAlign = "left"; c.textBaseline = "top";
      const m = Math.round(f * 0.7);
      if (Math.floor(t * 1.4) % 2) { c.fillStyle = COUL.rouge; c.beginPath(); c.arc(m + f * 0.35, m + f * 0.55, f * 0.32, 0, 7); c.fill(); }
      c.fillStyle = "rgba(242,231,226,.92)";
      c.fillText("REC", m + f * 0.9, m);
      c.fillStyle = "rgba(242,231,226,.7)";
      c.fillText(etroit ? "CAM 01" : (opts.titre || "CAM 01 · SALLE DES MARCHÉS"), m + f * 3.2, m);
      c.textAlign = "right";
      const d = new Date();
      c.fillText((etroit ? "" : d.toLocaleDateString("fr-FR") + "  ") + d.toLocaleTimeString("fr-FR"), W - m, m);
      // ligne d'état en bas
      c.textAlign = "left"; c.textBaseline = "bottom";
      let bas = "";
      const desk = etat.desk;
      if (erreurChargement) bas = erreurChargement;
      else if (etat.enCours) bas = `${(desk.etape || "Analyse").toUpperCase()} · ${Math.max(0, Math.floor((Date.now() / 1000 - desk.debut) / 60))} min · ${desk.moteur || ""}`;
      else if (etat.recent) bas = "DESK TERMINÉ · " + (desk.decision ? texteDecision(desk.decision) : "pas de décision");
      else bas = etat.ouverte ? "Salle ouverte · aucun desk en cours" : "Salle fermée · lance le desk pour les voir travailler";
      const pannes = etat.agents.filter(a => a.etat === "panne").length;
      c.fillStyle = erreurChargement ? COUL.rouge : "rgba(242,231,226,.8)";
      c.fillText(bas, m, H - m);
      if (etat.agents.length) {
        c.textAlign = "right";
        c.fillStyle = pannes ? COUL.rouge : "rgba(242,231,226,.6)";
        c.fillText(pannes ? `${pannes} agent${pannes > 1 ? "s" : ""} en panne` : `Équipe Jarvis : ${etat.agents.filter(a => a.etat === "ok").length} en marche`, W - m, H - m);
      }
    }

    function image() {
      const t = (performance.now() - t0) / 1000;
      sc.clearRect(0, 0, LW, LH);
      decor(t);
      etat.analystes.forEach((sp, i) => bureau(POS[i][0], POS[i][1], sp, i, t));
      chef(t);
      const places = equipe(t);
      dessinerParticules();
      // lumière : pièce dans la pénombre quand personne ne travaille
      if (!etat.actif) { sc.fillStyle = "rgba(0,0,10,.28)"; sc.fillRect(0, 0, LW, LH); }
      // la scène remplit la caméra (recadrée si besoin), avec un léger balancement de caméra
      const s = Math.max(W / LW, H / LH) * 1.02;
      const ox = (W - LW * s) / 2 + Math.sin(t * 0.21) * s * 1.2, oy = (H - LH * s) / 2 + Math.cos(t * 0.17) * s * 0.6;
      ctx.fillStyle = "#000"; ctx.fillRect(0, 0, W, H);
      ctx.imageSmoothingEnabled = false;
      ctx.filter = "saturate(.82) contrast(1.06)";
      ctx.drawImage(scene, ox, oy, LW * s, LH * s);
      ctx.filter = "none";
      if (erreurChargement && !data) { ctx.fillStyle = "rgba(0,0,0,.6)"; ctx.fillRect(0, 0, W, H); }
      surcouche(t, s, ox, oy, places);
      effetsCamera(t);
    }

    function boucle(now) {
      if (!marche) return;
      if (visible && !document.hidden && now - dernier >= 1000 / FPS) { dernier = now; try { image(); } catch (e) { if (!window.__bcErr) { window.__bcErr = 1; console.error("bureau-cam", e && e.stack); } } }
      timer = requestAnimationFrame(boucle);
    }

    // ---------------------------------------------------------------- liste lisible sous la caméra (téléphone)
    function remplirListe() {
      const el = opts.liste;
      if (!el) return;
      el.textContent = "";
      const ligne = (nom, etatTxt, couleur, phrase) => {
        const r = document.createElement("div"); r.className = "bc-ligne";
        const p = document.createElement("i"); p.className = "bc-pt"; p.style.background = couleur;
        const n = document.createElement("b"); n.textContent = nom;
        const e = document.createElement("span"); e.className = "bc-etat"; e.textContent = etatTxt; e.style.color = couleur;
        r.append(p, n, e);
        if (phrase) { const q = document.createElement("div"); q.className = "bc-phrase"; q.textContent = phrase; r.append(q); }
        el.append(r);
      };
      const titre = (t) => { const h = document.createElement("div"); h.className = "bc-titre"; h.textContent = t; el.append(h); };
      const d = etat.desk;
      titre(etat.enCours ? `Desk ${d.ticker} · ${d.etape || "en cours"}` : etat.recent ? `Dernier desk ${d.ticker} · ${heure(d.fin)}` : "Desk des spécialistes · au repos");
      const MODES = { repos: "au repos", travaille: "analyse…", debat: "débat", fini: "a donné son avis", erreur: "erreur" };
      for (const sp of etat.analystes) {
        const col = sp.mode === "erreur" ? COUL.rouge : BIAIS[sp.biais] && sp.mode !== "travaille" && sp.mode !== "repos" ? BIAIS[sp.biais] : sp.mode === "travaille" ? COUL.bleu : COUL.gris;
        const txt = (sp.mode === "debat" || sp.mode === "fini") && sp.biais ? `${sp.biais}${sp.confiance ? " " + sp.confiance + "/5" : ""}` : MODES[sp.mode];
        ligne(sp.nom, txt, col, sp.mode === "repos" || sp.mode === "travaille" ? "" : sp.phrase);
      }
      if (d && d.decision && etat.actif) {
        const td = texteDecision(d.decision);
        ligne("Chef de desk", td, /achat/i.test(td) ? COUL.vert : /vente/i.test(td) ? COUL.rouge : COUL.or,
          [d.decision.zone && "Zone " + d.decision.zone, d.decision.sl && "Stop " + d.decision.sl, d.decision.tp1 && "TP1 " + d.decision.tp1, d.decision.raison].filter(Boolean).join(" · "));
      } else ligne("Chef de desk", etat.chef === "reflechit" ? "décide…" : etat.chef === "surveille" ? "écoute le desk" : "au repos", etat.chef === "repos" ? COUL.gris : COUL.bleu, "");
      if (etat.agents.length) {
        titre("Équipe Jarvis");
        const C = { ok: COUL.vert, pause: COUL.or, panne: COUL.rouge, attente: COUL.gris };
        for (const a of etat.agents) ligne(a.nom, ETAT_AG[a.etat] || a.etat, C[a.etat] || COUL.gris, a.detail || "");
      }
    }

    function demarrer() {
      if (marche) return;
      marche = true; charger(); poll = setInterval(charger, opts.intervalle || 3000);
      timer = requestAnimationFrame(boucle);
    }
    function arreter() { marche = false; clearInterval(poll); cancelAnimationFrame(timer); }
    function detruire() { arreter(); if (ro) ro.disconnect(); if (io) io.disconnect(); cv.remove(); }
    if (opts.demarrer !== false) demarrer();
    return { demarrer, arreter, detruire, recharger: charger, _etat: () => etat };
  }

  // styles de la liste sous la caméra (une seule fois)
  if (!document.getElementById("bureau-cam-css")) {
    const s = document.createElement("style"); s.id = "bureau-cam-css";
    s.textContent = `
    .bc-titre { font-family: "Barlow Condensed", sans-serif; font-weight: 500; font-size: 17px; color: #d2a75a; margin: 14px 0 4px; }
    .bc-ligne { padding: 8px 0; border-bottom: 1px solid rgba(210,167,90,.12); font-size: 14px; line-height: 1.35; }
    .bc-ligne b { font-weight: 600; color: #f2e7e2; }
    .bc-pt { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 8px; vertical-align: 1px; }
    .bc-etat { margin-left: 8px; font-size: 12px; text-transform: uppercase; letter-spacing: .05em; }
    .bc-phrase { color: rgba(242,231,226,.78); font-size: 13.5px; margin: 3px 0 0 17px; overflow-wrap: anywhere; }`;
    document.head.append(s);
  }

  window.BureauCam = { creer, lireEtat };
})();
