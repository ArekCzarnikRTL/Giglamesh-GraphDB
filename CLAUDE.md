# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes.Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.


Kurzleitfaden fuer Claude Code in diesem Repository. Die folgenden fuenf Abschnitte beantworten alles, was vor jeder Aktion im Kopf sein sollte.

## 1. Worum geht es?

**GraphMesh** ist eine Enterprise Knowledge Graph Platform (Spring Boot + Kotlin). Sie extrahiert via LLMs RDF/SPO-Triples aus Dokumenten (PDF/Markdown/Text), speichert sie im Graph und beantwortet NLP-Fragen ueber Graph-RAG, Document-RAG und MCP-Tools. End-to-end-Pipeline:

```
Document → Decode → Chunk → LLM-Extract → RDF-Triples → Cassandra + Qdrant → GraphQL/MCP → RAG
```

## 2. Wie ist das Projekt organisiert?

- **Monorepo**: Backend (`src/main/kotlin/com/agentwork/graphmesh/...`), Frontend (`frontend/`, Next.js 14 + Apollo + shadcn/Tailwind), Docs (`docs/`), Infra (`docker-compose.yaml`).
- **Spring Modulith**: jedes Feature ist ein Package unter `com.agentwork.graphmesh`, _keine_ Submodule.
- **Features**: `docs/features/NN-name.md` = Spec, `NN-name-done.md` = Abschlussdoku. Uebersicht in `docs/features/00-feature-set-overview.md`.
- **Tech-Stack**: Kotlin auf Java 21, Gradle 9.4.1, Spring Boot 4.0.5, Spring Modulith 2.0.5, Spring AI 2.0.0-M4 (MCP-Server), **Koog 0.7.3** (LLM-Calls), Apache Cassandra + Qdrant + S3/MinIO + Kafka (Avro).
- **Tests**: JUnit 5 via `kotlin-test-junit5`. Integration-Tests brauchen laufendes `docker-compose up` (keine Testcontainers).

## 3. Wichtigste Befehle

```bash
./gradlew build                      # Compile + Test
./gradlew test                       # Nur Tests
./gradlew test --tests "com.agentwork.graphmesh.SomeTest"
./gradlew bootRun                    # App starten (Port 8083)
./gradlew generateJava               # GraphQL-Client-Codegen aus src/main/resources/graphql-client/

docker-compose up -d                 # Cassandra/Qdrant/Kafka/MinIO hochfahren
./tests/smoke-test.sh                # End-to-end Smoke-Test (braucht laufendes Backend + Infra)
./tests/mcp-smoke-test.sh            # MCP-Server Smoke-Test

cd frontend && pnpm dev              # Frontend (Port 3002)
cd frontend && pnpm test             # Vitest
```

## Key Conventions
- Use allway superpowers for new features and code example research with context7.
- Feature specs go in `docs/features/NN-feature-name.md` (German, numbered, kebab-case)
- Design specs go in `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`
- Implementation plans go in `docs/superpowers/plans/YYYY-MM-DD-<feature-name>.md`
- Always write the feature doc BEFORE implementing code
- `@Lazy` is used on `ScaffoldContextBuilder` injection in `ProjectService` to break a circular dependency

## 4. Konventionen

- **Kotlin-Compiler**: `-Xjsr305=strict`, `-Xannotation-default-target=param-property`.
- **LLM-Calls**: **immer** ueber `resolveLlmModel(name)` aus `com.agentwork.graphmesh.llm.ModelResolver` — Koog braucht Capabilities, rohe `LLModel(...)` knallt zur Laufzeit.
- **Kafka-Envelopes**: Avro-Schemas in `src/main/resources/avro/`, Consumer via `@KafkaListener`, Content-Type fest `application/avro`.
- **Commits**: direkt auf `main`, keine PRs, **niemals `git push` ohne explizite Freigabe**.
- **Doku**: neue Features erst als Spec in `docs/features/`, nach Abschluss eine `-done.md` nachziehen (siehe Muster `46-skos-taxonomy-done.md`, `49-markdown-document-support-done.md`).
- **Sprache in Docs und Commits**: Deutsch ok, Umlaute vermeiden (ae/oe/ue/ss).
- **Simplicity first**: keine Abstraktions-Layer ohne Not, YAGNI — direkt gegen Spring/Koog bauen.

## 5. Einschraenkungen — nicht uebersehen

- **Feature-Docs luegen teilweise**: aeltere Specs nennen falsche Packages oder Libs (z. B. `com.graphmesh.*`, alte Service-Namen). Immer gegen den echten Code gegenpruefen, bevor du darauf planst.
- **Qdrant: Dimension im Collection-Namen**. Wechsel des Embedding-Modells = neue Collection, sonst Dim-Mismatch.
- **Embedding-Context-Limits**: `multilingual-e5` nur 512 Tokens, `nomic-embed-text` 2048, `bge-m3` 8192. Chunk-Size in `application.yml` muss passen.
- **MCP-Transport**: aktuell `spring.ai.mcp.server.protocol=STREAMABLE` am `/mcp`-Endpoint. Der alte SSE-Pfad (`/sse`) sendet kein `endpoint`-Event — nicht dort andocken.
- **Koog-Beans-Konflikt**: `MultiLLMAutoConfiguration` muss in `application.yml` excluded bleiben, sonst kollidiert der `PromptExecutor`-Bean mit Ollama.
- **Bekannte Build-Issues** (pre-existing, nicht "fixen"): ambiguer `mainClass`, Koog-Bean-Konflikt beim parallelen Provider, Integration-Tests brauchen docker-compose.
- **Keine Testcontainers**, keine Submodule — Infra laeuft lokal via `docker-compose up`.
- **Git**: direkt auf `main` committen ist Konvention; aber **nie** `git push`, `git reset --hard`, `--force`, Branch-Deletes oder `--no-verify` ohne ausdrueckliche Anweisung.
