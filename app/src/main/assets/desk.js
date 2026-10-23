// Chat avec le chef de desk et ses 6 spécialistes (écran PC, page /desk, appli web, appli Android).
// DeskChat.creer(element, options) dessine : choix de l'agent, messages, missions, case de texte, micro.
// options :
//   api(chemin, corps?)   -> Promise<json>   (GET si pas de corps, POST sinon) — obligatoire
//   voix(blobWav, agent)  -> Promise<json>   (message vocal, facultatif ; sinon le micro est caché)
//   micro()               -> Promise<string> (micro de l'appli Android : renvoie le texte dit, facultatif)
//   parler(texte)                            (lit les réponses à voix haute, facultatif)
//   origine                                  ("appli" ou "ecran" : ne lit que les réponses aux messages envoyés d'ici)
// Copie identique dans l'appli Android (assets/desk.js) : la recopier si on la modifie.
(function () {
  const CSS = `
  .dk { display: flex; flex-direction: column; gap: 10px; min-width: 0; width: 100%; color: #f2e7e2; font-family: "Barlow", "Bahnschrift", "Segoe UI", sans-serif; min-height: 0; }
  .dk-agents { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 2px; scrollbar-width: none; }
  .dk-agents::-webkit-scrollbar { display: none; }
  .dk-ag { flex: none; padding: 7px 12px; border-radius: 999px; font: inherit; font-size: 14px; cursor: pointer;
    color: #d2a75a; background: rgba(210, 167, 90, .07); border: 1px solid rgba(210, 167, 90, .3); }
  .dk-ag[aria-pressed="true"] { background: #d2a75a; color: #1a0f08; border-color: #d2a75a; font-weight: 600; }
  .dk-missions { font-size: 13px; line-height: 1.45; color: #d2a75a; background: rgba(210, 167, 90, .06);
    border-left: 2px solid #d2a75a; padding: 6px 10px; border-radius: 0 8px 8px 0; }
  .dk-fil { flex: 1; min-height: 160px; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; padding: 2px; }
  .dk-msg { max-width: 86%; padding: 9px 12px; border-radius: 14px; font-size: 15px; line-height: 1.45; white-space: pre-wrap; overflow-wrap: anywhere; }
  .dk-msg.moi { align-self: flex-end; background: #3a1214; border: 1px solid rgba(227, 36, 43, .35); border-bottom-right-radius: 4px; }
  .dk-msg.eux { align-self: flex-start; background: #1a1112; border: 1px solid rgba(242, 231, 226, .1); border-bottom-left-radius: 4px; }
  .dk-msg.mission { border-color: rgba(210, 167, 90, .45); }
  .dk-qui { display: block; font-size: 12px; letter-spacing: .06em; text-transform: uppercase; color: #d2a75a; margin-bottom: 2px; }
  .dk-heure { font-size: 11px; color: rgba(242, 231, 226, .4); margin-left: 6px; text-transform: none; letter-spacing: 0; }
  .dk-pts i { display: inline-block; width: 6px; height: 6px; margin-right: 4px; border-radius: 50%; background: #d2a75a; animation: dkp 1s infinite; }
  .dk-pts i:nth-child(2) { animation-delay: .15s; } .dk-pts i:nth-child(3) { animation-delay: .3s; }
  @keyframes dkp { 50% { opacity: .2; } }
  .dk-vide { color: rgba(242, 231, 226, .6); font-size: 14px; line-height: 1.5; }
  .dk-idees { display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none; }
  .dk-idees::-webkit-scrollbar { display: none; }
  .dk-idee { flex: none; font: inherit; font-size: 13px; padding: 6px 10px; border-radius: 10px; cursor: pointer;
    color: rgba(242, 231, 226, .85); background: transparent; border: 1px solid rgba(242, 231, 226, .15); }
  .dk-saisie { display: flex; gap: 8px; align-items: center; }
  .dk-saisie input { flex: 1; min-width: 0; height: 46px; padding: 0 14px; border-radius: 23px; font: inherit; font-size: 16px;
    color: #f2e7e2; background: #1a1112; border: 1px solid rgba(242, 231, 226, .14); outline: none; }
  .dk-saisie input:focus { border-color: rgba(210, 167, 90, .6); }
  .dk-btn { flex: none; width: 46px; height: 46px; border-radius: 50%; display: grid; place-items: center; cursor: pointer;
    border: 1px solid rgba(210, 167, 90, .4); background: rgba(210, 167, 90, .1); color: #d2a75a; font-size: 18px; }
  .dk-btn.micro { background: #e3242b; border-color: #e3242b; color: #fff; }
  .dk-btn.micro.rec { animation: dkr 1.1s infinite; }
  @keyframes dkr { 50% { box-shadow: 0 0 0 9px rgba(227, 36, 43, .25); } }
  .dk-btn svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
  .dk-info { font-size: 12px; color: rgba(242, 231, 226, .5); min-height: 15px; }
  @media (prefers-reduced-motion: reduce) { .dk-pts i, .dk-btn.micro.rec { animation: none; } }`;
  const IDEES = {
    chef: ["Pourquoi cette décision ?", "Refais le point sur le gold", "Je veux prendre un trade, challenge-moi", "Quelles missions ?"],
    autre: ["Qu'est-ce que tu vois ?", "Donne-moi tes niveaux clés", "Mission : surveille ta zone et préviens-moi"],
  };
  const SVG_ENVOI = '<svg viewBox="0 0 24 24"><path d="M4 12l16-8-6 16-2-7z"/></svg>';
  const SVG_MICRO = '<svg viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/></svg>';

  function heure(t) { const d = new Date((t || 0) * 1000); return d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" }); }

  function toWav16k(data, rate) {
    const ratio = rate / 16000, n = Math.floor(data.length / ratio), pcm = new Int16Array(n);
    for (let i = 0; i < n; i++) {
      const a = Math.floor(i * ratio), b = Math.min(data.length, Math.floor((i + 1) * ratio));
      let s = 0; for (let j = a; j < b; j++) s += data[j];
      const v = Math.max(-1, Math.min(1, s / Math.max(1, b - a))); pcm[i] = v < 0 ? v * 32768 : v * 32767;
    }
    const buf = new ArrayBuffer(44 + pcm.length * 2), v = new DataView(buf);
    const w = (off, str) => { for (let i = 0; i < str.length; i++) v.setUint8(off + i, str.charCodeAt(i)); };
    w(0, "RIFF"); v.setUint32(4, 36 + pcm.length * 2, true); w(8, "WAVE"); w(12, "fmt ");
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, 16000, true); v.setUint32(28, 32000, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    w(36, "data"); v.setUint32(40, pcm.length * 2, true); new Int16Array(buf, 44).set(pcm);
    return new Blob([buf], { type: "audio/wav" });
  }

  function creer(racine, opt) {
    if (!document.getElementById("dk-style")) {
      const st = document.createElement("style"); st.id = "dk-style"; st.textContent = CSS; document.head.appendChild(st);
    }
    const peutMicro = !!(opt.micro || (opt.voix && window.isSecureContext && navigator.mediaDevices));
    racine.innerHTML = `<div class="dk">
      <div class="dk-agents" role="group" aria-label="À qui tu parles"></div>
      <div class="dk-missions" hidden></div>
      <div class="dk-fil" aria-live="polite"></div>
      <div class="dk-idees"></div>
      <form class="dk-saisie">
        <input type="text" placeholder="Écris au chef de desk…" enterkeyhint="send" autocomplete="off" aria-label="Ton message">
        <button type="submit" class="dk-btn" aria-label="Envoyer">${SVG_ENVOI}</button>
        ${peutMicro ? `<button type="button" class="dk-btn micro" aria-label="Parler">${SVG_MICRO}</button>` : ""}
      </form>
      <div class="dk-info"></div></div>`;
    const $ = (s) => racine.querySelector(s);
    const fil = $(".dk-fil"), champ = $("input"), info = $(".dk-info");
    let agent = "chef", agents = [], vus = new Set(), premier = true, minuterie = null, fini = false, rec = null;

    function dessinerAgents() {
      const box = $(".dk-agents"); box.textContent = "";
      for (const a of agents) {
        const b = document.createElement("button"); b.type = "button"; b.className = "dk-ag";
        b.textContent = a.court; b.title = a.nom; b.setAttribute("aria-pressed", a.cle === agent ? "true" : "false");
        b.addEventListener("click", () => { agent = a.cle; dessinerAgents(); dessinerIdees(); champ.focus(); });
        box.appendChild(b);
      }
      const nom = (agents.find(a => a.cle === agent) || {}).nom || "Chef de desk";
      champ.placeholder = agent === "chef" ? "Écris au chef de desk…" : `Écris à ${nom}…`;
    }
    function dessinerIdees() {
      const box = $(".dk-idees"); box.textContent = "";
      for (const t of (agent === "chef" ? IDEES.chef : IDEES.autre)) {
        const b = document.createElement("button"); b.type = "button"; b.className = "dk-idee"; b.textContent = t;
        b.addEventListener("click", () => envoyer(t)); box.appendChild(b);
      }
    }
    function dessiner(etat) {
      if (etat.agents && etat.agents.length && !agents.length) { agents = etat.agents; dessinerAgents(); }
      const mis = $(".dk-missions"), m = etat.missions || [];
      mis.hidden = !m.length;
      mis.textContent = m.length ? "Missions en cours : " + m.map(x => `${x.nom} — ${x.texte}`).join(" · ") : "";
      const bas = fil.scrollHeight - fil.scrollTop - fil.clientHeight < 60;
      fil.textContent = "";
      const msgs = etat.messages || [];
      if (!msgs.length) {
        const v = document.createElement("div"); v.className = "dk-vide";
        v.textContent = "Parle au chef de desk ou à un spécialiste. Exemples : « Pourquoi pas de trade ? », « Je veux shorter le 0.786, t'en penses quoi ? », « Mission pour Fibo : surveille le 0.705 ». Tu peux aussi dire à Jarvis : « dis au chef de desk… ».";
        fil.appendChild(v);
      }
      let attente = false;
      for (const x of msgs) {
        const d = document.createElement("div"), moi = x.de === "jabran";
        d.className = "dk-msg " + (moi ? "moi" : "eux") + (x.origine === "mission" ? " mission" : "");
        if (!moi) {
          const q = document.createElement("span"); q.className = "dk-qui"; q.textContent = x.nom;
          const h = document.createElement("span"); h.className = "dk-heure"; h.textContent = heure(x.heure); q.appendChild(h);
          d.appendChild(q);
        }
        if (x.attente) {
          attente = true;
          const p = document.createElement("span"); p.className = "dk-pts"; p.append(document.createElement("i"), document.createElement("i"), document.createElement("i"));
          d.appendChild(p); d.append(" réfléchit…");
        } else {
          d.append(x.texte);
          if (!moi && !vus.has(x.id) && !premier && opt.parler && (!opt.origine || x.origine === opt.origine)) opt.parler(x.texte);
        }
        if (!x.attente) vus.add(x.id);
        fil.appendChild(d);
      }
      premier = false;
      if (bas || attente) fil.scrollTop = fil.scrollHeight;
      return attente;
    }
    async function charger() {
      if (fini) return;
      clearTimeout(minuterie);
      let attente = false;
      try { attente = dessiner(await opt.api("/api/desk")); info.textContent = ""; }
      catch (e) { info.textContent = e.message === "pin" ? "" : "Le PC ne répond pas."; }
      if (!fini) minuterie = setTimeout(charger, attente ? 1500 : 8000);
    }
    async function envoyer(texte) {
      texte = (texte || "").trim(); if (!texte) return;
      info.textContent = "";
      try { dessiner(await opt.api("/api/desk", { texte, agent })); } catch (e) { info.textContent = "Message non envoyé : le PC ne répond pas."; }
      charger();
    }
    $("form").addEventListener("submit", (e) => { e.preventDefault(); const t = champ.value; champ.value = ""; envoyer(t); });

    // ---------- micro ----------
    const bMicro = $(".dk-btn.micro");
    async function demarrer() {
      if (opt.micro) {  // micro de l'appli Android : renvoie directement le texte
        bMicro.classList.add("rec"); info.textContent = "Je t'écoute…";
        try { const t = await opt.micro(); info.textContent = ""; if (t) envoyer(t); }
        catch (e) { info.textContent = "Je n'ai rien entendu."; }
        bMicro.classList.remove("rec"); return;
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
        const ctx = new (window.AudioContext || window.webkitAudioContext)(); await ctx.resume();
        const src = ctx.createMediaStreamSource(stream), proc = ctx.createScriptProcessor(4096, 1, 1);
        const st = { stream, ctx, src, proc, bouts: [], t0: performance.now(), der: performance.now(), entendu: false, bruit: 0.003, n: 0 };
        proc.onaudioprocess = (e) => {
          const d = e.inputBuffer.getChannelData(0); st.bouts.push(new Float32Array(d));
          let s = 0; for (let i = 0; i < d.length; i++) s += d[i] * d[i];
          const rms = Math.sqrt(s / d.length), now = performance.now();
          if (st.n++ < 4) { st.bruit = Math.max(st.bruit, rms); return; }
          if (rms > Math.max(0.012, st.bruit * 2.5)) { st.entendu = true; st.der = now; }
          if ((st.entendu && now - st.der > 1400) || now - st.t0 > 25000 || (!st.entendu && now - st.t0 > 7000)) arreter(true);
        };
        src.connect(proc); proc.connect(ctx.destination);
        rec = st; bMicro.classList.add("rec"); info.textContent = "Je t'écoute… (touche encore pour arrêter)";
      } catch (e) { info.textContent = "Je n'ai pas accès au micro."; }
    }
    async function arreter(envoi) {
      const st = rec; if (!st) return; rec = null; bMicro.classList.remove("rec");
      try { st.proc.disconnect(); st.src.disconnect(); } catch (e) {}
      st.stream.getTracks().forEach(t => t.stop());
      const rate = st.ctx.sampleRate; st.ctx.close();
      if (!envoi || !st.entendu) { info.textContent = ""; return; }
      let len = 0; st.bouts.forEach(c => len += c.length);
      const tout = new Float32Array(len); let o = 0; st.bouts.forEach(c => { tout.set(c, o); o += c.length; });
      info.textContent = "Je transcris…";
      try {
        const r = await opt.voix(toWav16k(tout, rate), agent);
        info.textContent = r && r.entendu ? "" : "Je n'ai rien entendu, réessaie.";
        if (r && r.messages) dessiner(r);
      } catch (e) { info.textContent = "Le PC ne répond pas."; }
      charger();
    }
    if (bMicro) bMicro.addEventListener("click", () => rec ? arreter(true) : demarrer());

    dessinerIdees();
    charger();
    return {
      envoyer,
      rafraichir: charger,
      detruire() { fini = true; clearTimeout(minuterie); if (rec) arreter(false); racine.textContent = ""; },
    };
  }

  window.DeskChat = { creer };
})();
