"use client";

import { GraphFilter as FilterType } from "@/types/graph";

interface GraphFilterProps {
  filter: FilterType;
  availableDatasets: string[];
  availablePredicates: string[];
  availableTypes: string[];
  onChange: (filter: FilterType) => void;
}

export function GraphFilter({
  filter,
  availableDatasets,
  availablePredicates,
  availableTypes,
  onChange,
}: GraphFilterProps) {
  const handle = (key: keyof FilterType) => (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selected = Array.from(e.target.selectedOptions).map((o) => o.value).filter(Boolean);
    onChange({ ...filter, [key]: selected });
  };

  return (
    <div className="flex flex-wrap gap-4 p-3 border-b bg-gray-50">
      <label className="flex flex-col text-xs text-gray-600">
        Dataset
        <select
          multiple
          aria-label="Dataset"
          value={filter.datasets}
          onChange={handle("datasets")}
          className="text-sm border rounded px-2 py-1 min-w-[140px]"
        >
          {availableDatasets.map((d) => (
            <option key={d} value={d}>{d}</option>
          ))}
        </select>
      </label>
      <label className="flex flex-col text-xs text-gray-600">
        Prädikat
        <select
          multiple
          aria-label="Prädikat"
          value={filter.predicates}
          onChange={handle("predicates")}
          className="text-sm border rounded px-2 py-1 min-w-[140px]"
        >
          {availablePredicates.map((p) => (
            <option key={p} value={p}>{p}</option>
          ))}
        </select>
      </label>
      <label className="flex flex-col text-xs text-gray-600">
        Typ
        <select
          multiple
          aria-label="Typ"
          value={filter.entityTypes}
          onChange={handle("entityTypes")}
          className="text-sm border rounded px-2 py-1 min-w-[140px]"
        >
          {availableTypes.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
      </label>
    </div>
  );
}
