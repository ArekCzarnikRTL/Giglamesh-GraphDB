# Feature 17: MCP Tool Interface — Design Spec

## Status

Completion of an existing implementation. The MCP server and all 4 tools are already implemented using Spring AI's `@McpTool` annotation approach. This spec covers the remaining work: proper unit tests.

## Existing Implementation

### Infrastructure (no changes needed)

- **Dependency**: `spring-ai-starter-mcp-server-webmvc` handles the full MCP protocol (initialize, tools/list, tools/call, SSE transport, JSON-RPC)
- **Config**: `application.yml` defines `spring.ai.mcp.server` with name `graphmesh`, version `1.0.0`, protocol `SSE`
- **Tools class**: `GraphMeshMcpTools.kt` — single `@Service` with 4 `@McpTool`-annotated methods

### Tools implemented

| Tool | Method | Dependencies |
|------|--------|-------------|
| `knowledgeQuery` | Graph RAG query with formatted sources | `GraphRagService` |
| `documentQuery` | Document RAG query with source citations | `DocumentRagService` |
| `collectionList` | List collections, optional tag filter | `CollectionService` |
| `documentSearch` | Search docs by collection, optional title filter | `LibrarianService` |

### Deferred: Bearer token authentication

Auth was explicitly deferred. When needed, a servlet filter on `/mcp/**` checking `Authorization: Bearer <token>` against a `graphmesh.mcp.auth-token` config property is the recommended approach.

## Work to be done

### Rewrite `GraphMeshMcpToolsTest.kt`

Replace the current test (which duplicates formatting logic in private helpers) with MockK-based unit tests that exercise the actual `GraphMeshMcpTools` service.

**Setup**: Instantiate `GraphMeshMcpTools` with 4 mocked dependencies.

**Test cases**:

#### knowledgeQuery (3 tests)
- Formats answer with sources (subject, predicate, object, reasoning, edge counts)
- Uses default `maxEdges=150` when parameter is null
- Passes custom `maxEdges` to `GraphRagQuery`

#### documentQuery (3 tests)
- Formats answer with sources (title, page, score, snippet)
- Uses default `topK=10` when parameter is null
- Passes custom `topK` to `DocumentRagQuery`

#### collectionList (3 tests)
- Formats collections with name, ID, description, tags
- Returns `"No collections found."` on empty list
- Filters by tags (passes parsed tag set to service)

#### documentSearch (3 tests)
- Formats documents with title, ID, type, state
- Returns `"No documents found."` on empty list
- Filters by `titleFilter` (case-insensitive contains)

### Acceptance criteria coverage

| Criterion | Status |
|-----------|--------|
| MCP Server responds to `initialize` | Handled by Spring AI starter |
| `tools/list` returns all 4 tools | Handled by `@McpTool` auto-discovery |
| Typed arguments with JSON Schema | Handled by `@McpToolParam` |
| `tools/call` executes with validated arguments | Handled by Spring AI starter |
| Missing required arguments rejected | Handled by Spring AI starter |
| Bearer token auth | Deferred |
| SSE endpoint at `/mcp/sse` | Handled by starter config |
| Messages endpoint at `/mcp/messages` | Handled by starter config |
| Unknown methods return error -32601 | Handled by starter |
| Tool results include `isError` flag | Handled by starter |
