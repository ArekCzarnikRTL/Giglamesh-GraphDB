"use client";

import { Button } from "@/components/ui/button";

interface Props {
  page: number;
  pageSize: number;
  totalCount: number;
  onPageChange: (page: number) => void;
}

export function DocumentPagination({ page, pageSize, totalCount, onPageChange }: Props) {
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));
  const isFirst = page <= 0;
  const isLast = page >= totalPages - 1;

  return (
    <div className="flex items-center justify-between py-2">
      <span className="text-sm text-muted-foreground">
        Seite {page + 1} von {totalPages} ({totalCount} Dokumente)
      </span>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={isFirst}
          onClick={() => onPageChange(page - 1)}
        >
          Zurück
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={isLast}
          onClick={() => onPageChange(page + 1)}
        >
          Weiter
        </Button>
      </div>
    </div>
  );
}
