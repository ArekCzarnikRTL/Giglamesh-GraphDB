// frontend/src/types/cores.ts

export interface ContextCoreStats {
  quadCount: number;
  entityCount: number;
  chunkEmbeddingCount: number;
  ontologyAxiomCount: number;
}

export interface ContextCore {
  coreId: string;
  version: string;
  sourceCollection: string;
  createdAt: string;
  createdBy: string;
  description: string | null;
  tags: string[];
  stats: ContextCoreStats;
  embeddingModel: string;
  embeddingDimension: number;
  checksum: string;
}

export interface ContextCoresData {
  contextCores: ContextCore[];
}

export interface BuildContextCoreResult {
  buildContextCore: {
    coreId: string;
    version: string;
    checksum: string;
    stats: {
      quadCount: number;
      entityCount: number;
      chunkEmbeddingCount: number;
    };
  };
}

export interface ImportContextCoreResult {
  importContextCore: {
    coreId: string;
    version: string;
    quadsImported: number;
    embeddingsImported: number;
  };
}
