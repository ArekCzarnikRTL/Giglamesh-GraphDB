---
title: Context Cores
nav_order: 9
---

# Context Cores & Wissensportabilitaet

## Ueberblick

Context Cores loesen das Problem der Portabilitaet und Versionierung von Wissen in GraphMesh. Waehrend die Plattform Wissen aus Dokumenten extrahiert und in einem Wissensgraphen speichert, existiert dieses Wissen normalerweise nur als verteilter Live-Zustand. Es gibt keinen einfachen Weg, einen kuratierten Wissensstand einzufrieren, zwischen Umgebungen zu transportieren oder bei Bedarf zurueckzurollen.

Ein Context Core ist ein versioniertes, portables Wissens-Bundle im ZIP-Format. Es enthaelt den vollstaendigen Stand einer Collection: alle Wissensgraph-Tripel, Vektor-Embeddings, die zugehoerige Ontologie und Retrieval-Konfigurationen. Context Cores funktionieren wie Build-Artefakte fuer Wissen -- sie koennen gebaut, getestet, getaggt und in andere Collections importiert werden.

### Typische Anwendungsfaelle

- **Reproduzierbarkeit** -- Einen Wissensstand einfrieren und spaeter exakt reproduzieren, z.B. um nachzuvollziehen warum ein RAG-Lauf bestimmte Antworten lieferte.
- **Wissenstransfer** -- Eine kuratierte Wissensdomaene (z.B. "Pharma-Ontologie mit 12.000 Studien-Tripeln") zwischen Projekten oder Mandanten teilen, ohne die gesamte Verarbeitungs-Pipeline erneut auszufuehren.
- **Promotion-Workflow** -- Wissen wie Code behandeln: in einer Testumgebung bauen und pruefen, dann per Tag in die Produktivumgebung befoerdern.

## Features

### Context Core bauen (Export)

**Problem:** Der aktuelle Wissensstand einer Collection laesst sich nicht als geschlossenes Artefakt sichern oder weitergeben.

**Loesung:** Die Build-Funktion exportiert den vollstaendigen Inhalt einer Collection als versioniertes ZIP-Bundle. Das Bundle enthaelt:

- Alle Wissensgraph-Tripel im N-Quads-Format
- Die zugehoerige Ontologie als Turtle-Datei (falls eine Ontologie zugeordnet ist)
- Vektor-Embeddings aller Chunks
- Retrieval-Konfigurationen (z.B. Top-K-Werte, Aehnlichkeitsschwellen)
- Ein Manifest mit Metadaten, Statistiken und einer SHA-256-Pruefsumme

#### Anwendung

- **Frontend:** Oeffnen Sie die Seite *Cores* ueber die Navigation. Klicken Sie auf *Core bauen*. Waehlen Sie im Dialog:
  - *Core ID* -- ein sprechender Name, z.B. `pharma-base`
  - *Version* -- Versionsnummer, z.B. `1.0.0`
  - *Quell-Collection* -- die Collection deren Wissen exportiert werden soll
  - *Ontologie* -- optional die Ontologie, die dem Wissen zugrunde liegt
  - *Tags* -- optionale Markierungen wie `stage` oder `test`

  Nach dem Build erscheint der neue Core in der Liste mit Statistiken (Anzahl Tripel, Entitaeten, Embeddings).

- **API:** GraphQL-Mutation zum Bauen eines Context Cores:

  ```graphql
  mutation {
    buildContextCore(
      coreId: "pharma-base"
      version: "1.0.0"
      sourceCollection: "meine-collection-id"
      description: "Pharma-Wissensbasis Q1 2026"
      tags: ["stage"]
      ontologyKey: "pharma-ontologie"
    ) {
      coreId
      version
      checksum
      stats {
        quadCount
        entityCount
        chunkEmbeddingCount
      }
    }
  }
  ```

#### Beispiel

1. Sie haben eine Collection "Pharma-Studien" mit importierten Forschungsdokumenten und einer zugeordneten Ontologie.
2. Oeffnen Sie *Cores* und klicken Sie *Core bauen*.
3. Vergeben Sie die Core-ID `pharma-studien`, Version `1.0.0`, waehlen Sie die Quell-Collection und die Ontologie aus.
4. Nach dem Build zeigt die Liste: 847 Tripel, 312 Entitaeten, 156 Embeddings, Pruefsumme berechnet.

---

### Context Core importieren

**Problem:** Extrahiertes Wissen aus einer Umgebung soll in eine andere Collection uebernommen werden, ohne die Dokumente erneut zu verarbeiten.

**Loesung:** Die Import-Funktion laedt ein bestehendes Context-Core-Bundle in eine Ziel-Collection. Dabei wird die Integritaet per Pruefsumme verifiziert. Drei Konfliktstrategien stehen zur Verfuegung:

- **FAIL** -- Bricht ab wenn die Ziel-Collection bereits Daten enthaelt. Schuetzt vor versehentlichem Ueberschreiben.
- **MERGE** -- Fuegt die Daten zur bestehenden Collection hinzu. Geeignet um Wissen aus mehreren Cores zusammenzufuehren.
- **REPLACE** -- Loescht alle vorhandenen Daten in der Ziel-Collection und ersetzt sie durch den Core-Inhalt.

Optional kann beim Import ein Namespace-Rewrite durchgefuehrt werden, um URI-Praefixe anzupassen (z.B. wenn Quell- und Zielsystem unterschiedliche Namensraeume verwenden).

#### Anwendung

- **Frontend:** Klicken Sie in der Core-Liste beim gewuenschten Core auf *Importieren*. Waehlen Sie im Dialog:
  - *Ziel-Collection* -- die Collection in die importiert werden soll
  - *Konfliktstrategie* -- FAIL, MERGE oder REPLACE
  - *Namespace-Rewrite* (optional) -- Quell- und Ziel-Praefix fuer URI-Umschreibung

- **API:** GraphQL-Mutation zum Importieren:

  ```graphql
  mutation {
    importContextCore(
      coreId: "pharma-base"
      version: "1.0.0"
      targetCollection: "ziel-collection-id"
      strategy: MERGE
      namespaceFrom: "http://alt.example.org/"
      namespaceTo: "http://neu.example.org/"
    ) {
      coreId
      version
      quadsImported
      embeddingsImported
    }
  }
  ```

#### Beispiel

1. Ein Kollege hat den Core `pharma-studien@1.0.0` in der Staging-Umgebung gebaut.
2. Sie erstellen in Ihrer Umgebung eine neue leere Collection "Pharma-Prod".
3. Oeffnen Sie *Cores*, klicken Sie beim Core auf *Importieren*.
4. Waehlen Sie "Pharma-Prod" als Ziel und Strategie *FAIL* (Collection ist leer).
5. Nach dem Import enthaelt "Pharma-Prod" alle 847 Tripel, 312 Entitaeten und 156 Embeddings -- sofort abfragbar.

---

### Versionierung und Tagging

**Problem:** Es gibt keinen Mechanismus um verschiedene Staende eines Wissensbestands zu unterscheiden oder bestimmte Versionen als "produktionsreif" zu kennzeichnen.

**Loesung:** Jeder Context Core traegt eine eindeutige Kombination aus Core-ID und Versionsnummer. Zusaetzlich koennen beliebige Tags vergeben werden, um Versionen zu markieren (z.B. `stage`, `prod`, `latest`). Ueber Tags laesst sich gezielt die aktuell gueltige Version einer Wissensdomaene abfragen.

#### Anwendung

- **Frontend:** In der Core-Liste werden alle Tags eines Cores angezeigt. Neue Tags koennen ueber die API vergeben werden.

- **API:**

  Tag setzen:
  ```graphql
  mutation {
    tagContextCore(
      coreId: "pharma-base"
      version: "1.0.0"
      tag: "prod"
    ) {
      coreId
      version
      tags
    }
  }
  ```

  Core ueber Tag abrufen:
  ```graphql
  query {
    contextCoreByTag(coreId: "pharma-base", tag: "prod") {
      coreId
      version
      stats { quadCount entityCount }
    }
  }
  ```

#### Beispiel

1. Sie bauen Version `1.0.0` des Cores `pharma-base` und taggen sie mit `stage`.
2. Nach erfolgreichen Tests taggen Sie dieselbe Version mit `prod`.
3. Sie bauen Version `1.1.0` mit aktualisierten Daten und taggen sie zunaechst nur mit `stage`.
4. Die Produktivumgebung verwendet weiterhin die `prod`-Version (`1.0.0`), bis `1.1.0` ebenfalls mit `prod` getaggt wird.

---

### Core auflisten und abfragen

**Problem:** Es fehlt ein Ueberblick ueber alle verfuegbaren Wissens-Bundles mit ihren Statistiken und Metadaten.

**Loesung:** Die Plattform bietet eine Uebersichtsseite und Abfrage-Endpunkte fuer alle registrierten Context Cores.

#### Anwendung

- **Frontend:** Die Seite *Cores* zeigt alle registrierten Cores als Liste mit:
  - Core-ID und Version
  - Quell-Collection
  - Beschreibung und Tags
  - Statistiken: Anzahl Tripel, Entitaeten, Embeddings
  - Pruefsumme

- **API:**

  Alle Cores auflisten:
  ```graphql
  query {
    contextCores {
      coreId
      version
      sourceCollection
      description
      tags
      stats { quadCount entityCount chunkEmbeddingCount }
      checksum
    }
  }
  ```

  Einzelnen Core abfragen:
  ```graphql
  query {
    contextCore(coreId: "pharma-base", version: "1.0.0") {
      coreId
      version
      stats { quadCount entityCount }
    }
  }
  ```

---

### Context Core loeschen

**Problem:** Nicht mehr benoetigte Wissens-Bundles belegen Speicherplatz im Blob-Store.

**Loesung:** Cores koennen einzeln geloescht werden. Dabei wird sowohl das ZIP-Bundle im Blob-Store als auch der Registry-Eintrag entfernt. Die Quell-Collection und eventuelle Import-Ziele bleiben davon unberuehrt.

#### Anwendung

- **Frontend:** Klicken Sie in der Core-Liste beim gewuenschten Core auf *Loeschen* und bestaetigen Sie die Aktion.

- **API:**

  ```graphql
  mutation {
    deleteContextCore(coreId: "pharma-base", version: "1.0.0")
  }
  ```
