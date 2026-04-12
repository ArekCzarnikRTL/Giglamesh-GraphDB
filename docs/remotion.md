# GraphMesh Explainer-Animation Prompt

Erstelle eine neue Remotion-Animation in diesem Projekt. Ein kurzer Erklaerfilm (60 Sekunden) der zeigt, was GraphMesh ist und warum es nuetzlich ist.

## Story & Szenen

Die Animation erzaehlt die Geschichte eines Teams, das in Dokumenten ertrinkt -- und wie GraphMesh das Problem loest. Stil: minimalistisch, dunkel (Dark Mode), geometrische Formen, sanfte Uebergaenge, Tech-Aesthetic mit Glow-Effekten. Komplett mit SVG/`<div>`-Elementen, **keine externen Assets oder Bilder**.

### Szene 1: Das Problem (Frames 0–120)

**"Wissen steckt in Dokumenten fest"**

- Ein Stapel stilisierter Dokument-Rechtecke (PDFs, Reports) faellt von oben ins Bild und stapelt sich.
- Kleine Text-Zeilen auf den Dokumenten sind unleserlich (graue Linien).
- Eine Lupe schwebt ueber den Stapel und findet nichts (rotes X blinkt).
- Subtitel-Text faded ein: "Unstrukturiert. Unverknuepft. Unzugaenglich."

### Szene 2: GraphMesh tritt auf (Frames 120–180)

**"GraphMesh macht Wissen sichtbar"**

- Das GraphMesh-Logo (stilisierter Graph-Knoten mit Verbindungen, Farbe #7b93ff) zoomt aus der Mitte.
- Ein Glow-Puls breitet sich vom Logo kreisfoermig aus.
- Subtitel: "KI-Knowledge-Graph-Plattform"

### Szene 3: Upload & Extraktion (Frames 180–330)

**"Dokumente rein — Wissen raus"**

- Links: Ein Dokument-Icon gleitet in einen stilisierten "Trichter" (Upload).
- Mitte: Zahnraeder drehen sich, KI-Funken spritzen (kleine Sternchen-Partikel). Drei Stufen werden nacheinander sichtbar:
  1. "PDF erkennen" (Dokument-Icon → Text-Zeilen)
  2. "Aufteilen" (Text → kleine Bloecke/Chunks)
  3. "Wissen extrahieren" (Bloecke → leuchtende Tripel-Pfeile)
- Rechts: Aus dem Trichter kommen leuchtende Knoten-Verbindungen (Subjekt → Praedikat → Objekt) heraus und ordnen sich als kleiner Graph an.

### Szene 4: Der Knowledge Graph waechst (Frames 330–450)

**"Ein lebendiger Wissensgraph"**

- Der kleine Graph aus Szene 3 waechst organisch: neue Knoten poppen auf (spring-Animation), Kanten ziehen sich zwischen ihnen (animierte Linien mit Glow).
- Verschiedene Farben fuer verschiedene Entity-Typen (Personen: blau, Orte: gruen, Konzepte: lila).
- Die Knoten ordnen sich in ein Force-Layout an (sanftes Wackeln).
- Subtitel: "Beziehungen erkennen. Zusammenhaenge sichtbar machen."

### Szene 5: Intelligente Abfragen (Frames 450–600)

**"Fragen stellen — Antworten bekommen"**

- Unten: Eine Chat-Eingabezeile blinkt, dann tippt sich der Text: "Welche Technologien nutzt Projekt Alpha?"
- Der Graph im Hintergrund reagiert: relevante Knoten leuchten auf, irrelevante dimmen ab.
- Verbindungslinien zwischen den leuchtenden Knoten pulsieren.
- Eine Antwort-Blase erscheint mit stilisiertem Text und kleinen Quellen-Badges daneben.
- Subtitel: "Graph RAG · Document RAG · NLP Query"

### Szene 6: Alle Interfaces (Frames 600–720)

**"Ueberall verfuegbar"**

- Vier Icons erscheinen gestaffelt in einer Reihe (spring-Bounce):
  1. Browser-Fenster (Frontend)
  2. Code-Klammern `{ }` (GraphQL API)
  3. Terminal-Prompt `>_` (CLI)
  4. Roboter-Kopf (MCP / KI-Agent)
- Unter jedem Icon ein Label.
- Subtitel: "Frontend · API · CLI · MCP"

### Szene 7: Abschluss (Frames 720–900)

**"Wissen, das arbeitet"**

- Alle vorherigen Elemente fliegen nach aussen weg (reverse-zoom).
- GraphMesh-Logo zentriert sich gross.
- Tagline faded ein: "Von Dokumenten zu Wissen. Automatisch."
- URL faded ein: "graphmesh.dev" (oder Platzhalter)
- Sanfter Glow-Loop auf dem Logo.

## Technische Anforderungen

1. Lege die Komponenten unter `src/remotion/GraphMeshExplainer/` an:
   - `GraphMeshExplainer.tsx` (Hauptkomposition mit Sequences)
   - `ProblemScene.tsx` (Szene 1)
   - `LogoReveal.tsx` (Szene 2)
   - `ExtractionPipeline.tsx` (Szene 3)
   - `GrowingGraph.tsx` (Szene 4)
   - `QueryScene.tsx` (Szene 5)
   - `InterfacesScene.tsx` (Szene 6)
   - `OutroScene.tsx` (Szene 7)
   - `shared/GraphNode.tsx`, `shared/GraphEdge.tsx`, `shared/GlowPulse.tsx` (wiederverwendbare Bausteine)

2. Animationen ausschliesslich ueber `useCurrentFrame()`, `interpolate()` und `spring()` aus `remotion` — keine CSS-Keyframes, kein `setTimeout`.

3. Definiere Zod-Schema `GraphMeshExplainerProps` in `types/constants.ts`:
   ```ts
   titleText: z.string().default("GraphMesh")
   tagline: z.string().default("Von Dokumenten zu Wissen. Automatisch.")
   primaryColor: z.string().default("#7b93ff")
   accentColor: z.string().default("#10b981")
   backgroundColor: z.string().default("#0d0f18")
   ```
   Exportiere `GRAPHMESH_COMP_NAME = "GraphMeshExplainer"`, `GRAPHMESH_DURATION_IN_FRAMES = 900`, `GRAPHMESH_FPS = 30`, Masse 1920x1080.

4. Registriere die Composition in `src/remotion/Root.tsx`.

5. Verwende `AbsoluteFill`, `Sequence` und `spring({ frame, fps, config: { damping, stiffness } })` fuer gestaffelte Auftritte.

6. Farbpalette:
   - Background: `#0d0f18` (dunkel)
   - Surface: `#131622`
   - Primary (Graph-Knoten, Logo): `#7b93ff`
   - Accent (Erfolg, Extraktion): `#10b981`
   - Warn (Problem): `#f06060`
   - Text: `#e2e6f0`
   - Muted: `#8892a8`

7. Graph-Knoten als Kreise mit Glow (`box-shadow` oder SVG-Filter `feGaussianBlur`). Kanten als SVG-`<line>` oder `<path>` mit animiertem `stroke-dashoffset`.

8. Typing-Effekt in Szene 5: Character fuer Character mit `interpolate(frame, [start, end], [0, text.length])`.

9. Achte darauf, dass die Animation sauber loopt (letzter Frame = aehnlich wie erster Frame mit Logo-Glow).

10. Anschliessend `npm run lint` ausfuehren und Fehler beheben.

## Nicht tun

- Keine neuen Dependencies installieren.
- Bestehende Compositions nicht veraendern.
- Keine API-Routes oder Lambda-Konfiguration anfassen.
- Kein `node deploy.mjs` ausfuehren.

## Vorschau

Nach Erstellung in Remotion Studio auswaehlen:
```bash
npx remotion studio
```
Dann die Composition "GraphMeshExplainer" auswaehlen. Props (Titel, Tagline, Farben) sind live editierbar.
