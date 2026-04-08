"use client";

import { useState } from "react";
import { useLazyQuery } from "@apollo/client/react";
import { ENTITY_SEARCH_QUERY } from "@/graphql/graph";

interface EntitySearchProps {
  collectionId: string;
  onSelect: (entityUri: string) => void;
}

interface SearchData {
  entitySearch: string[];
}

export function EntitySearch({ collectionId, onSelect }: EntitySearchProps) {
  const [value, setValue] = useState("");
  const [search, { data }] = useLazyQuery<SearchData>(ENTITY_SEARCH_QUERY);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setValue(next);
    if (next.length >= 3 && collectionId) {
      void search({ variables: { collectionId, prefix: next, limit: 20 } });
    }
  };

  return (
    <div className="relative">
      <input
        type="text"
        value={value}
        onChange={handleChange}
        placeholder="Entity suchen…"
        className="w-72 rounded-md border border-border bg-secondary px-3 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
      />
      {data?.entitySearch && data.entitySearch.length > 0 && (
        <ul className="absolute z-10 mt-1 max-h-64 w-full overflow-auto rounded-md border border-border bg-popover text-sm text-popover-foreground shadow-md">
          {data.entitySearch.map((uri) => (
            <li
              key={uri}
              onClick={() => {
                onSelect(uri);
                setValue("");
              }}
              className="cursor-pointer truncate px-3 py-2 hover:bg-accent hover:text-accent-foreground"
            >
              {uri}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
