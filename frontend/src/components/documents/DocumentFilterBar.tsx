"use client";

import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { DocumentFilter, DocumentState, DocumentType } from "@/types/document";

interface Props {
  value: DocumentFilter;
  onChange: (filter: DocumentFilter) => void;
}

const TYPES: { value: DocumentType | "ALL"; label: string }[] = [
  { value: "ALL", label: "Alle Typen" },
  { value: "SOURCE", label: "Quelle" },
  { value: "PAGE", label: "Seite" },
  { value: "CHUNK", label: "Chunk" },
];

const STATES: { value: DocumentState | "ALL"; label: string }[] = [
  { value: "ALL", label: "Alle Stati" },
  { value: "UPLOADED", label: "Hochgeladen" },
  { value: "PROCESSING", label: "Verarbeitung" },
  { value: "EXTRACTED", label: "Extrahiert" },
  { value: "FAILED", label: "Fehlgeschlagen" },
];

export function DocumentFilterBar({ value, onChange }: Props) {
  return (
    <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
      <Input
        placeholder="Suche nach Titel…"
        value={value.search ?? ""}
        onChange={(e) =>
          onChange({ ...value, search: e.target.value || undefined })
        }
        className="sm:max-w-xs"
      />
      <Select
        value={value.type ?? "ALL"}
        onValueChange={(v) =>
          onChange({ ...value, type: v === "ALL" ? undefined : (v as DocumentType) })
        }
      >
        <SelectTrigger className="sm:w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {TYPES.map((t) => (
            <SelectItem key={t.value} value={t.value}>
              {t.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select
        value={value.state ?? "ALL"}
        onValueChange={(v) =>
          onChange({ ...value, state: v === "ALL" ? undefined : (v as DocumentState) })
        }
      >
        <SelectTrigger className="sm:w-44">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {STATES.map((s) => (
            <SelectItem key={s.value} value={s.value}>
              {s.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
