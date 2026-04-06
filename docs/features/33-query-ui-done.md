# Feature 33: Query UI — Done

## Zusammenfassung

Chat-basierte Query-Oberflaeche unter `/query` mit vier Modi (Auto/NLP, Graph
RAG, Document RAG, Agent Streaming). Jeder Modus nutzt einen dedizierten Hook
und eine modus-spezifische Source-Komponente. Streaming gibt es nur im
Agent-Modus ueber die `agentStream`-Subscription. Apollo-Wrapper wurde um einen
WebSocket-Link via `graphql-ws` erweitert. Query-History wird im LocalStorage
gehalten (max 100 Eintraege).

## Implementierte Komponenten

### Hooks
- `useGraphRag`, `useDocumentRag`, `useNlpQuery` (useLazyQuery)
- `useAgentStream` (useSubscription mit manuellem Variables-Gate)

### Komponenten
- `QueryChat` — Orchestrator
- `QueryInput`, `QueryMessage`, `ModeSelector`, `ErrorBubble`
- `SourcePanel` — Mode-Dispatcher
- `QueryHistory` — LocalStorage-Sidebar
- `sources/GraphRagSources`, `DocumentRagSources`, `NlpQueryMeta`,
  `AgentStreamTimeline`

### Page
- `app/query/layout.tsx`, `app/query/page.tsx`

### Infrastruktur
- `lib/query-history.ts`
- `graphql/query.ts`
- `types/query.ts`
- `lib/apollo-wrapper.tsx` erweitert um `GraphQLWsLink`
- `package.json`: `graphql-ws` Dependency

## Tests

Vitest + @testing-library/react, MockedProvider fuer alle Hooks und Komponenten.
Alle 35 Tests gruen (20 Test-Dateien). `pnpm build` erfolgreich.

## Abweichungen vom Feature-Dokument

1. **Streaming nur im Agent-Modus** — `graphRag`/`documentRag`/`nlpQuery` sind
   non-streaming Queries; das Backend liefert keine Token-Streams dafuer.
2. **Vier Modi statt drei** — neuer Modus "Agent (Streaming)" fuer
   `agentStream`.
3. **Source-Felder** folgen dem tatsaechlichen Schema (`snippet`/`pageNumber`,
   `selectedEdges`).
4. **NLP-Modus** zeigt zusaetzlich `detectedIntent`/`effectiveQuestion`/
   `wasReformulated`.
5. **Kein `explainabilityChain`** (Feature 30 hat dafuer ein eigenes Schema).
6. **CollectionSelector** ist props-los und nutzt den globalen
   `useActiveCollection`-Store.
7. **Keine generische Source-Adapter-Ebene** — pro Modus eigene Komponente.

## Abweichungen vom Plan (waehrend Implementierung entdeckt)

- **`useGraphRag`/`useDocumentRag`/`useNlpQuery`**: `.execute(...)` musste den
  von Apollo v4 geworfenen Promise-Rejection mit `.catch(() => {})` schlucken,
  da Vitest sonst die Test-Datei mit "unhandled rejection" fehlschlaegt. Die
  Fehler bleiben im `error`-Feld der Hook-Rueckgabe verfuegbar.
- **`QueryChat.finalize()`**: Die urspruengliche Fassung setzte
  `pendingIdRef.current = null` synchron nach `setMessages(...)`. Da React 18
  die Funktional-Updater-Callback asynchron ausfuehrt, war die Ref schon `null`,
  wenn der Updater lief, und keine Nachricht wurde gefunden. Fix: Ref-Wert vor
  `setMessages` in eine lokale Variable kopieren und im Updater verwenden.
- **`reset` nicht in Apollo v4 `useLazyQuery`** — Apollo v4 entfernte `reset`
  aus dem `useLazyQuery`-Result-Typ. Die drei Hooks (`useGraphRag`,
  `useDocumentRag`, `useNlpQuery`) geben kein `reset` mehr zurueck; die
  optionalen `reset?.()` Aufrufe in `QueryChat` wurden entfernt. Der
  `useAgentStream`-Hook hat weiterhin sein eigenes `reset()`, da es dort manuell
  implementiert ist.
- **ESLint/tsc-Fehler im Build** — `useGraphRag.test.tsx` verwendete `any[]` als
  Wrapper-Typ und eine anonyme Funktion ohne `displayName`. Beides wurde beim
  Production Build von `next/typescript` ESLint abgelehnt. Fix: Typ auf
  `Parameters<typeof MockedProvider>[0]["mocks"]` umgestellt, benannte
  Komponente mit `displayName` vergeben.

## Offene Punkte / Tech Debt

- `QueryChatWithSensor` in `app/query/page.tsx` ist eine pragmatische
  Vereinfachung fuer den "hat Messages?"-Sensor des Wechsel-Dialogs. Wenn das
  Verhalten in der Praxis nicht ausreicht, sollte `messages` in die Page
  hochgezogen werden.
- Re-Run einer History-Frage ist nicht automatisiert — der Nutzer tippt sie
  erneut. Bewusste YAGNI-Entscheidung.
- WebSocket-Verbindung hat keine sichtbare Reconnect-/Heartbeat-Anzeige.
- Pre-existing tsc errors in den Document-UI-Tests (`addTypename`-Prop, `.reset`
  fehlt) sind nicht von dieser Feature verursacht und wurden nicht behoben.
