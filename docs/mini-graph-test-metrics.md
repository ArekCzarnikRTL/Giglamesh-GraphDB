# Worum geht es bei GraphMesh Was bedeuten die Werte?

Diese Artikel erklaert die Verarbeitung von einen Text in GraphMesh System,
ohne dass man die Code-Interna kennen muss.
Bezugspunkt ist immer ein einzelnes Mini-Dokument (5 Saetze):

Arctic Research Glossary

Dr. Elena Vasquez leads the Arctic climate research team at the Oslo Institute.
Permafrost is ground that remains frozen for at least two consecutive years.
A methane release zone refers to an area where trapped methane escapes from thawing soil.
The team uses satellite imagery from NASA to monitor the Svalbard archipelago.
GraphMesh is a knowledge graph platform that turns field reports into a searchable graph.

## Worum geht es ueberhaupt?

Das GraphMesh System nimmt einen Text und baut daraus zwei Dinge auf, die spaeter die
Suche/Antworten stuetzen:

1. **Einen Wissensgraphen.** Stell dir eine Mind-Map vor: jeder Begriff (z. B.
   *Permafrost*, *NASA*, *Vasquez*) ist ein Knoten, und Linien dazwischen
   beschreiben Beziehungen ("Vasquez *leitet* Team", "Team *nutzt* Satelliten").
   So eine Linie nennen wir **Triple** — drei Bestandteile:
   *Wer (Subject)* — *Was tut/ist (Predicate)* — *Wem/Was (Object)*.

2. **Eine semantische Suche.** Jeder Textabschnitt (Chunk) wird in einen
   "Bedeutungsfingerabdruck" (Embedding) umgewandelt und in einer Vektor-DB
   abgelegt. So findet die Suche aehnliche Inhalte, auch wenn die Frage
   andere Worte verwendet.

Die Metriken zeigen, wie reichhaltig der Graph nach der Verarbeitung ist und
ob die Suche darauf zugreifen kann.

---

## Metriken der Verarbeitung des Dokuments

```
Triples gesamt:            98
Topic-Triples:             5
Definition-Triples:        6
Vector-Treffer:            1
Entitaeten gefunden:       6/7
Definitionen gefunden:     2/3
Graph-RAG Edges:           51
```

---

## 1. Triples gesamt — `98`

**Was es ist:** Die Gesamtzahl aller Verbindungen (Linien) im Wissensgraphen
fuer dieses Dokument.

**Was dahintersteckt:** Mehrere AI-Agent arbeiten parallel an deinem Text:
Einer findet Personen und Beziehungen, einer erkennt Themen, einer findet
Definitionen, dazu kommen Etiketten (Klartext-Namen) und Herkunftsangaben
("welcher KI-Lauf hat das wann erzeugt"). Jeder dieser Helfer schreibt
Triples in den Graphen.

**Warum wichtig fuer die Suche:** Mehr Triples = dichteres Netz = mehr
Verbindungen, denen die Suche folgen kann. Wenn du fragst "Wer leitet das
Team?", wandert die Suche an Knoten und Linien entlang. Sind kaum Linien
da, findet sie nichts. 50–150 Triples pro 5-Satz-Dokument sind gesund.
Unter 10 ist verdaechtig — wahrscheinlich ist ein Helfer ausgefallen.

---

## 2. Topic-Triples — `5`

**Was es ist:** Wieviele *Themen-Etiketten* der Text bekommen hat.

**Was dahintersteckt:** Ein AI-Agent ("TopicExtractor") liest den Text
und vergibt Themen-Tags wie *"Klimaforschung"*, *"Polarregion"*,
*"Satellitendaten"*. Pro Thema werden zwei Eintraege gespeichert: das Thema
selbst und ein Konfidenzwert (wie sicher die KI ist). 5 Triples ≈ 2–3
erkannte Themen.

**Warum wichtig fuer die Suche:** Themen sind die "Ordner-Etiketten"
deiner Dokumente. Sie helfen z. B. bei
- **Filterung:** "Zeig mir alles zu Klimaforschung."
- **Cluster-Ansichten:** "Welche Themen kommen in dieser Sammlung am haeufigsten vor?"
- **Disambiguierung:** Bei mehreren Treffern bevorzugt die Suche thematisch passende.

Ohne Topic-Triples geht das Dokument in jedem Themenfilter unter.

---

## 3. Definition-Triples — `6`

**Was es ist:** Wieviele *Begriffsdefinitionen* aus dem Text extrahiert wurden
(Eintraege der Form "X bedeutet Y").

**Was dahintersteckt:** Ein AI-Agent ("DefinitionExtractor") sucht gezielt
nach erklaerenden Saetzen ("Permafrost ist Boden, der dauerhaft gefroren
bleibt"). Diese Definitionen werden im Graphen mit einem Standard-Praedikat
(`rdfs:comment`) am jeweiligen Begriff abgelegt — wie ein Glossareintrag.

**Warum wichtig fuer die Suche:**
- **Antwortqualitaet:** Wenn jemand fragt "Was ist Permafrost?", kann das
  System die Definition direkt liefern, statt nur einen Textausschnitt.
- **Tooltips/Erklaerungen:** In der UI lassen sich diese Definitionen als
  Kontext-Hilfe einblenden.
- **Ranking:** Begriffe mit Definition wirken "wichtiger" und werden in
  Antworten haeufiger als Anker verwendet.

Bei einem Glossar-aehnlichen Text (3 Definitionen drinnen) sind 6 Triples
realistisch — manche Definitionen erzeugen mehrere Eintraege (z. B. Synonyme).
Bei rein narrativen Texten ohne "X ist Y"-Saetze ist 0 normal und kein Bug.

---

## 4. Vector-Treffer — `1`

**Was es ist:** Wieviele Textabschnitte (Chunks) die semantische Suche fuer
eine Beispielanfrage gefunden hat.

**Was dahintersteckt:** Der Text wird in Stuecke geschnitten (hier: 1 Chunk,
weil 5 Saetze in eines passen). Jeder Chunk bekommt einen "Bedeutungs-
fingerabdruck" und wird in einer spezialisierten Datenbank (Qdrant) abgelegt.
Bei einer Anfrage wird die Frage genauso fingerabdruck-codiert und mit den
gespeicherten verglichen — die aehnlichsten kommen zurueck.

**Warum wichtig fuer die Suche:**
- **Synonym-Toleranz:** Frage "polare Eisschmelze" findet auch "Arctic ice
  melting" — der Wissensgraph allein wuerde da scheitern.
- **Kontext fuer das Antwort-LLM:** Der wirkliche Original-Textausschnitt
  (nicht nur die abstrakten Triples) wird der KI als Beleg mitgegeben.
- **Volltextsuche-Fallback:** Wenn der Graph keine passende Entitaet hat,
  kann die Vektorsuche trotzdem inhaltlich passende Stellen zeigen.

1 Treffer ist hier korrekt (es gibt nur 1 Chunk). 0 waere Alarm: dann hat
entweder der Embedding-Helfer noch nicht geschrieben, oder die Vektor-DB
ist falsch konfiguriert.

---

## 5. Entitaeten gefunden — `6/7`

**Was es ist:** Stichprobenkontrolle — wir haben uns 7 Begriffe ueberlegt,
die im Text vorkommen (z. B. *Vasquez*, *NASA*, *Permafrost*), und schauen,
ob sie irgendwo im Graphen auftauchen.

**Was dahintersteckt:** Wenn ein Begriff im Graph fehlt, hat ihn keiner der
AI-Agent als wichtig erkannt. Das passiert bei kleinen oder schwachen
LLM-Modellen oder wenn der Begriff nur beilaeufig erwaehnt wird.

**Warum wichtig fuer die Suche:** Was nicht im Graph steht, kann die Suche
nicht finden — auch wenn es im Originaltext steht. Eine Frage nach "Hat NASA
mit dem Team zusammengearbeitet?" scheitert, wenn "NASA" nie als Knoten
gespeichert wurde. 6/7 ist gut, ein bis zwei Aussetzer sind normal. Unter
4/7 sollte man das LLM-Modell oder den Prompt anschauen.

---

## 6. Definitionen gefunden — `2/3`

**Was es ist:** Aehnlich wie oben, aber speziell fuer drei Begriffe, fuer
die wir wirklich eine **Definition** im Graph erwarten (im Test:
*Permafrost*, *methane*, *GraphMesh*).

**Was dahintersteckt:** Ein Begriff kann im Graph als Knoten existieren
(zaehlt unter "Entitaeten gefunden"), aber **ohne** Erklaerungstext. Hier
zaehlen wir nur die, die wirklich eine Definition haben.

**Warum wichtig fuer die Suche:** Eine erkannte Entitaet ist gut, aber eine
**erklaerte** Entitaet ist Gold:
- Bei "Was ist X?"-Fragen bekommt der Nutzer eine echte Antwort.
- Im Frontend kann ein Hover-Tooltip die Bedeutung zeigen.
- Bei mehrdeutigen Begriffen (z. B. "Java" = Insel oder Sprache?) hilft die
  Definition beim Disambiguieren.

2/3 ist akzeptabel. Bei 0/3 hat der Definition-Helfer ein Problem (Modell zu
schwach, oder Prompt liefert ungueltiges Format).

---

## 7. Graph-RAG Edges — `51`

**Was es ist:** Wieviele Linien (Kanten) im Wissensgraphen die KI bei der
Test-Frage "Who leads the Arctic climate research team?" als Kontext
herangezogen hat.

**Was dahintersteckt:** Wenn du eine Frage stellst, geht GraphMesh so vor:
1. Findet relevante Anker-Knoten im Graph (z. B. *Vasquez*, *Arctic team*).
2. Sammelt Nachbarn dieser Knoten — und ggf. deren Nachbarn (sog. *Hops*).
3. Reicht alle gesammelten Linien als Kontext an das Antwort-LLM.

51 Kanten = der Retriever hat den Vasquez-Subgraphen plus angrenzendes Umfeld
plus Herkunftsangaben gezogen.

**Warum wichtig fuer die Suche:** Diese Zahl ist ein Indikator fuer die
**Antwortqualitaet**:
- **Zu wenig (z. B. 0–3):** Die KI bekommt kaum Kontext, antwortet vage oder
  sagt "Ich weiss es nicht". Dann ist entweder der Graph zu duenn, oder die
  Frage trifft keinen Anker (zu vage / Sprache zu weit weg).
- **Sehr viel (z. B. > 200):** Die KI bekommt Rauschen, antwortet evtl.
  abschweifend oder verwechselt Dinge.
- **5–80 ist der Sweet-Spot** fuer fokussierte, korrekte Antworten.

---

## Verdict-Logik

Der Test sagt am Ende **"OK"** wenn:

```
Triples > 5  &&  Vector-Treffer > 0  &&
Entitaeten >= 4/7  &&  Definitionen >= 2/3
```

Anders gesagt: Der Graph muss mehr als nur Skelett haben, die Suche muss
funktionieren, und die wichtigsten Begriffe wie Definitionen muessen wirklich
verstanden worden sein. Sonst ist die spaetere Nutzererfahrung schlecht
("ich finde nix", "Antworten sind unbrauchbar").

---

## Alltags-Sicht: was kann der Endnutzer mit guten Werten?

Wenn alle Werte im gruenen Bereich sind, kann ein Nutzer im Frontend:

| Was er tut                                | Was im Hintergrund hilft           |
| ----------------------------------------- | ---------------------------------- |
| "Wer leitet das Team?"                    | Triples + Graph-RAG Edges          |
| "Was bedeutet Permafrost?"                | Definition-Triples                 |
| "Zeig mir alles zu Klimaforschung."       | Topic-Triples                      |
| "Such was zu Methan in der Arktis."       | Vector-Treffer                     |
| Auf einen Knoten im Graph klicken         | Triples gesamt (Nachbarschaft)     |
| Hover ueber Begriff zeigt Erklaerung      | Definition-Triples                 |
| Filtern nach Themen-Sidebar               | Topic-Triples                      |

Wenn ein Wert auf 0 steht, faellt die zugehoerige Funktion im Frontend aus
oder wird unbrauchbar. Deshalb gibt der Mini-Test bei jedem Wert einen
Daumen hoch oder runter — er prueft, ob die "Bausteine" der Suche
ueberhaupt da sind.

---

## Schnelldiagnose nach Symptom

| Symptom                             | Was du dem Nutzer sagen wuerdest                                          |
| ----------------------------------- | ------------------------------------------------------------------------- |
| Triples gesamt < 10                 | "Der Graph ist fast leer — Antworten werden generisch sein."              |
| Topic-Triples = 0                   | "Themen-Filter funktioniert fuer dieses Dokument nicht."                  |
| Definition-Triples = 0              | "'Was-ist-X'-Fragen werden hier keine Definition liefern."                |
| Vector-Treffer = 0                  | "Volltextaehnliche Suche findet nichts in diesem Dokument."               |
| Entitaeten ≪ erwartet               | "Wichtige Begriffe fehlen — die KI hat sie nicht als wichtig erkannt."    |
| Definitionen 0/3                    | "Es gibt keine Begriffserklaerungen — Glossar/Tooltips sind leer."        |
| Graph-RAG Edges = 0                 | "Frage trifft keinen Anker im Graph — entweder zu vage oder Graph leer."  |
