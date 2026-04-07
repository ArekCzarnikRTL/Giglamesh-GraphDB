"use client";

import { useState } from "react";
import { useMutation, useQuery } from "@apollo/client/react";
import { toast } from "sonner";
import {
  CONFIG_KEYS_QUERY,
  SET_CONFIG_MUTATION,
} from "@/graphql/admin";
import {
  CONFIG_TYPES,
  ConfigEntry,
  ConfigTypeValue,
} from "@/types/admin";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ConfigItemCard } from "./ConfigItemCard";

interface ConfigKeysData {
  configKeys: ConfigEntry[];
}

export function ConfigEditor() {
  const [selectedType, setSelectedType] = useState<ConfigTypeValue | null>(null);

  const { data, loading, error, refetch } = useQuery<ConfigKeysData>(
    CONFIG_KEYS_QUERY,
    { variables: { type: selectedType } },
  );

  const [setConfig] = useMutation(SET_CONFIG_MUTATION);

  const handleSave = (item: ConfigEntry) => async (value: string) => {
    try {
      await setConfig({
        variables: { key: item.key, value, type: item.type },
      });
      toast.success("Konfiguration gespeichert");
      refetch();
    } catch (err) {
      toast.error(`Fehler: ${(err as Error).message}`);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant={selectedType === null ? "default" : "outline"}
          onClick={() => setSelectedType(null)}
        >
          Alle
        </Button>
        {CONFIG_TYPES.map((type) => (
          <Button
            key={type}
            size="sm"
            variant={selectedType === type ? "default" : "outline"}
            onClick={() => setSelectedType(type)}
          >
            {type}
          </Button>
        ))}
      </div>

      {loading && !data && (
        <div className="space-y-2">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTitle>Fehler</AlertTitle>
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      )}

      {data && data.configKeys.length === 0 && (
        <p className="text-muted-foreground">Keine Konfigurationen vorhanden.</p>
      )}

      <div className="space-y-3">
        {data?.configKeys.map((item) => (
          <ConfigItemCard
            key={item.id}
            item={item}
            onSave={handleSave(item)}
          />
        ))}
      </div>
    </div>
  );
}
