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

  const selectClass =
    "text-sm rounded-md border border-input bg-input/30 px-2 py-1 min-w-[140px] text-foreground [color-scheme:dark] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50";

  return (
    <div className="flex flex-wrap gap-4 border-b border-border bg-card p-3">
      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        Dataset
        <select
          multiple
          aria-label="Dataset"
          value={filter.datasets}
          onChange={handle("datasets")}
          className={selectClass}
        >
          {availableDatasets.map((d) => (
            <option key={d} value={d}>{d}</option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        Prädikat
        <select
          multiple
          aria-label="Prädikat"
          value={filter.predicates}
          onChange={handle("predicates")}
          className={selectClass}
        >
          {availablePredicates.map((p) => (
            <option key={p} value={p}>{p}</option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        Typ
        <select
          multiple
          aria-label="Typ"
          value={filter.entityTypes}
          onChange={handle("entityTypes")}
          className={selectClass}
        >
          {availableTypes.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
      </label>
    </div>
  );
}
