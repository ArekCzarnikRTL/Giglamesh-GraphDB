# Cartoon-Animation Prompt

Erstelle eine neue Remotion-Cartoon-Animation in diesem Projekt. Halte dich strikt an die bestehende Architektur (siehe `CLAUDE.md`).

## Inhalt der Animation

Eine fröhliche Cartoon-Szene: Eine lachende Sonne geht über sanften Hügeln auf, ein kleiner Cartoon-Vogel fliegt mit flatternden Flügeln von links nach rechts durchs Bild, im Vordergrund wackelt ein Cartoon-Baum im Wind, und am Ende erscheint ein Text-Titel mit Bounce-Effekt. Stil: flach, kräftige Farben, dicke Outlines, freundliche Proportionen — komplett mit SVG/`<div>`-Elementen, **keine externen Assets oder Bilder**.

## Technische Anforderungen

1. Lege die Komponenten unter `src/remotion/Cartoon/` an (z. B. `Cartoon.tsx`, `Sun.tsx`, `Bird.tsx`, `Tree.tsx`, `Hills.tsx`, `Title.tsx`).
2. Animationen ausschließlich über `useCurrentFrame()`, `interpolate()` und `spring()` aus `remotion` — keine CSS-Keyframes, kein `setTimeout`.
3. Definiere ein neues Zod-Schema `CartoonProps` in `types/constants.ts` mit mindestens `titleText: z.string()` und `backgroundColor: z.string()`. Exportiere `defaultCartoonProps` und ergänze `CARTOON_COMP_NAME`, `CARTOON_DURATION_IN_FRAMES` (z. B. 240), `CARTOON_FPS` (30), Maße 1280×720.
4. Registriere die neue Composition in `src/remotion/Root.tsx` mit `id={CARTOON_COMP_NAME}`, `defaultProps={defaultCartoonProps}` und passe den Schema-Import an, sodass Studio Props bearbeiten kann.
5. Verwende `AbsoluteFill`, `Sequence` und `spring({ frame, fps, config: { damping } })` für den Auftritt jedes Elements gestaffelt (Sonne 0–30, Hügel 10–40, Baum 20–50, Vogel-Flug 30–180, Titel 150–240).
6. Achte darauf, dass die Animation looping-fähig wirkt und die Komponente reine Funktionskomponenten ohne Side-Effects nutzt.
7. Anschließend `npm run lint` ausführen und Fehler beheben.

## Nicht tun

- Keine neuen Dependencies installieren.
- `MyComp` und die `NextLogo`-Composition nicht verändern.
- Keine API-Routes oder Lambda-Konfiguration anfassen — nur Remotion-Seite + `types/constants.ts`.
- Kein `node deploy.mjs` ausführen (das macht der Nutzer manuell).

Zeige am Ende, wie ich die Composition in Remotion Studio (`npx remotion studio`) auswählen und die Props live editieren kann.
