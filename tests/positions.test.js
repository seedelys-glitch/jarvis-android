// Lancer : node tests/positions.test.js — entraide entre agents de l'onglet Positions (27/11)
global.window = {};
require("../app/src/main/assets/positions.js");
const P = window.Positions;
let ko = 0;
function vrai(b, quoi) { if (!b) { ko++; console.log("RATE " + quoi); } }
const m = [{ id: 1, ts: 10, de: 14, a: 1, genre: "lecon", contenu: "a" }, { id: 2, ts: 30, de: 9, a: 14, genre: "alerte", contenu: "b" },
  { id: 3, ts: 20, de: 14, a: 4, genre: "reponse", contenu: "" }, { id: 4, ts: 40, de: null, a: 1, genre: "x", contenu: "c" }];
vrai(JSON.stringify(P.echanges(m).map(x => x.id)) === "[2,1]", "tri récent d'abord, vides et incomplets écartés");
vrai(P.echanges(m, 1).length === 1, "maximum respecté");
vrai(P.echanges(null).length === 0 && P.echanges({}).length === 0, "réponse bizarre = liste vide");
vrai(P.prenom(14) === "Arthur" && P.prenom("1") === "Sacha" && P.prenom(42) === "Agent 42", "prénoms");
console.log("positions.test.js : " + (ko ? ko + " raté(s)" : "tout est bon"));
process.exit(ko ? 1 : 0);
