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
        className="text-sm border rounded px-3 py-1 w-72"
      />
      {data?.entitySearch && data.entitySearch.length > 0 && (
        <ul className="absolute z-10 mt-1 w-full bg-white border rounded shadow text-sm max-h-64 overflow-auto">
          {data.entitySearch.map((uri) => (
            <li
              key={uri}
              onClick={() => {
                onSelect(uri);
                setValue("");
              }}
              className="px-3 py-2 hover:bg-gray-100 cursor-pointer truncate"
            >
              {uri}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
