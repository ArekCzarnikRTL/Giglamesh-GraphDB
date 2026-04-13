export interface CollectionOntology {
  ontologyKey: string;
  role: string;
  assignedAt: string;
  assignedBy: string;
  ontology: {
    key: string;
    name: string;
    namespace: string;
    version: string;
    classCount: number;
    objectPropertyCount: number;
    datatypePropertyCount: number;
  } | null;
}

export interface CollectionDataStats {
  tripleCount: number;
  entityCount: number;
  predicateCount: number;
  datasets: string[];
}
