"use client";

import dynamic from "next/dynamic";
import { ComponentProps } from "react";

// Sigma touches `window` at module load time → ssr disabled.
const GraphCanvasInner = dynamic(() => import("./GraphCanvasInner"), {
  ssr: false,
  loading: () => (
    <div
      data-testid="graph-canvas-loading"
      className="h-full w-full animate-pulse bg-muted"
    />
  ),
});

export type GraphCanvasProps = ComponentProps<typeof GraphCanvasInner>;

export function GraphCanvas(props: GraphCanvasProps) {
  return <GraphCanvasInner {...props} />;
}
