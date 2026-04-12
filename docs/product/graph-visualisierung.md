# Graph-Visualisierung und Exploration

GraphMesh stellt extrahiertes Wissen als interaktiven Wissensgraphen dar. Entitaeten und ihre Beziehungen lassen sich visuell erkunden, filtern und schrittweise erweitern.

---

## Uebersicht

| Funktion | Beschreibung |
|---|---|
| Graph Explorer | Interaktive Visualisierung aller Entitaeten und Beziehungen einer Collection |
| Subgraph-Abruf | Gezielte Erweiterung des Graphen rund um eine ausgewaehlte Entitaet |

---

## Features

### Graph Explorer

**Problem:** Tabellarische Listen von Wissenseintraegen zeigen keine Zusammenhaenge. Nutzer koennen nicht erkennen, wie Entitaeten miteinander verbunden sind.

**Loesung:** GraphMesh bietet einen interaktiven Wissensgraphen auf der `/graph`-Seite. Entitaeten werden als Knoten dargestellt, Beziehungen als gerichtete Kanten. Das Layout ordnet sich automatisch per Force-Directed-Algorithmus an und passt sich dynamisch an, wenn neue Knoten hinzukommen.

#### Anwendung

- **Frontend:** Seite `/graph` -- vollstaendige Graph-Ansicht mit Steuerungselementen
- **GraphQL API:** Query `triples` zum Abruf von Wissensdaten, Query `graphMetadata` fuer verfuegbare Filter-Optionen

#### Bedienelemente

- **Collection-Auswahl:** Oben in der Kopfzeile wird die aktive Wissens-Collection gewaehlt
- **Entity-Suche:** Suchfeld mit Autovervollstaendigung -- ab 3 Zeichen werden passende Entitaeten vorgeschlagen
- **Filter:** Drei Filterleisten erlauben die Einschraenkung nach Dataset, Praedikat und Entitaetstyp
- **Zoom und Vollbild:** Zoom-Regler und Vollbild-Modus unten rechts
- **Minimap:** Uebersichtskarte unten links zur Orientierung in grossen Graphen
- **Layout-Steuerung:** Start/Stopp des automatischen Force-Layouts

#### Interaktion

- **Klick auf Knoten:** Waehlt die Entitaet aus und zeigt Details in einem Seitenpanel
- **Rechtsklick auf Knoten:** Laedt die Nachbarn dieser Entitaet nach (Subgraph-Erweiterung)
- **Hover:** Hebt die Entitaet und ihre direkten Nachbarn hervor, alle anderen Knoten werden abgeblendet

#### Beispiel

> **Szenario:** Ein Analyst untersucht die Beziehungen eines Unternehmens in importierten Geschaeftsberichten.

1. Der Analyst oeffnet `/graph` und waehlt die Collection "Geschaeftsberichte 2025"
2. Der Graph laedt initial bis zu 500 Triples und zeigt Entitaeten als Netzwerk
3. Im Suchfeld tippt er "Siemens" -- die Autovervollstaendigung schlaegt passende URIs vor
4. Er waehlt den Eintrag aus -- die Kamera fliegt zur Entitaet und deren Nachbarn werden geladen
5. Per Klick auf "Siemens" oeffnet sich rechts das Detail-Panel mit allen Triples dieser Entitaet
6. Er sieht Praedikate wie "hatVorstand", "istTaetigIn", "hatTochtergesellschaft"
7. Per Rechtsklick auf "Siemens Energy" werden deren Nachbarn nachgeladen und der Graph waechst

---

### Subgraph-Abruf

**Problem:** Ein vollstaendiger Wissensgraph kann Tausende Entitaeten enthalten. Ein kompletter Abruf waere unuebersichtlich und langsam.

**Loesung:** GraphMesh laedt Teilgraphen bedarfsgesteuert. Der initiale Abruf zeigt einen Ueberblick; einzelne Entitaeten lassen sich gezielt expandieren, um deren Nachbarschaft schrittweise sichtbar zu machen.

#### Anwendung

- **Frontend:** Rechtsklick auf einen Knoten oder Button "Nachbarn laden" im Detail-Panel
- **GraphQL API:** Query `triples` mit `subject`-Parameter zum Abruf der Nachbarschaft einer Entitaet

#### Verhalten

- **Initialer Abruf:** Beim Oeffnen der Graph-Seite werden bis zu 500 Triples geladen
- **Expansion:** Pro Knoten werden bis zu 50 Nachbar-Triples nachgeladen (sowohl als Subjekt als auch als Objekt)
- **Markierung:** Bereits expandierte Knoten werden visuell gekennzeichnet, der Button "Nachbarn laden" verschwindet
- **Kumulative Darstellung:** Neue Knoten und Kanten werden dem bestehenden Graphen hinzugefuegt, ohne bereits sichtbare Daten zu entfernen

#### Detail-Panel

Nach Auswahl einer Entitaet zeigt das Detail-Panel:

- **Name und URI** der Entitaet
- **Typ** (URI, Literal, Blank Node)
- **Alle Triples** dieser Entitaet mit Praedikat, Objekt und Dataset
- **Nachbarn laden** -- Button zur Expansion (nur bei noch nicht expandierten Knoten)

#### Beispiel

> **Szenario:** Ein Jurist sucht alle Vertragspartner eines bestimmten Vertrags.

1. Er oeffnet `/graph` und waehlt die Collection "Vertragsdokumente"
2. Im Suchfeld gibt er die Vertragsnummer ein und waehlt die passende Entitaet
3. Die Kamera zoomt zum Vertrag -- er sieht die Entitaet mit den initial geladenen Verbindungen
4. Per Rechtsklick auf den Vertragsknoten werden alle Nachbarn geladen
5. Es erscheinen Vertragspartner, Vertragsdaten, Klauseln als neue Knoten
6. Er klickt auf einen Vertragspartner und sieht im Detail-Panel das Praedikat "istVertragspartner" und weitere Attribute
7. Per Rechtsklick auf den Vertragspartner laedt er wiederum dessen weitere Vertraege nach

---

## GraphQL-Referenz

### Triples abrufen

```graphql
query {
  triples(
    collectionId: "col-123"
    subject: "https://example.org/Siemens"
    limit: 100
  ) {
    subject
    predicate
    object
    dataset
    objectType
  }
}
```

### Entity-Suche

```graphql
query {
  entitySearch(
    collectionId: "col-123"
    prefix: "Siemens"
    limit: 20
  )
}
```

### Graph-Metadaten (fuer Filter)

```graphql
query {
  graphMetadata(collectionId: "col-123") {
    datasets
    predicates
    entityTypes
  }
}
```
