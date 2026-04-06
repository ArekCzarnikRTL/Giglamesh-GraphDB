"use client";

import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

interface Props {
  message: string;
  originalQuery: string;
  onRetry: (originalQuery: string) => void;
}

export function ErrorBubble({ message, originalQuery, onRetry }: Props) {
  return (
    <Alert variant="destructive" className="max-w-3xl">
      <AlertTitle>Anfrage fehlgeschlagen</AlertTitle>
      <AlertDescription className="space-y-2">
        <p className="text-sm">{message}</p>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => onRetry(originalQuery)}
        >
          Erneut versuchen
        </Button>
      </AlertDescription>
    </Alert>
  );
}
