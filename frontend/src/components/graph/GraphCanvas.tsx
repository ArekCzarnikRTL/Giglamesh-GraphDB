"use client";

import dynamic from "next/dynamic";
import { useEffect, useImperativeHandle, useRef, forwardRef, useCallback } from "react";
import { GraphData, GraphNode, LayoutConfig } from "@/types/graph";

// react-force-graph-2d touches `window` at module load time -> ssr disabled.
const ForceGraph2D = dynamic(() => import("react-force-graph-2d"), { ssr: false });

export interface GraphCanvasHandle {
  zoomToFit: (ms?: number) => void;
  centerOnNode: (nodeId: string) => void;
}

interface GraphCanvasProps {
  data: GraphData;
  layoutConfig: LayoutConfig;
  selectedNodeId: string | null;
  onNodeClick: (node: GraphNode) => void;
  onNodeRightClick: (node: GraphNode) => void;
}

const NODE_COLORS: Record<string, string> = {
  URI: "#4F46E5",
  LITERAL: "#059669",
  BLANK_NODE: "#D97706",
  QUOTED_TRIPLE: "#7C3AED",
};

export const GraphCanvas = forwardRef<GraphCanvasHandle, GraphCanvasProps>(function GraphCanvas(
  { data, layoutConfig, selectedNodeId, onNodeClick, onNodeRightClick },
  ref
) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const fgRef = useRef<any>(null);

  useEffect(() => {
    const fg = fgRef.current;
    if (!fg) return;
    fg.d3Force?.("charge")?.strength?.(layoutConfig.chargeStrength);
    fg.d3Force?.("link")?.distance?.(layoutConfig.linkDistance);
    fg.d3Force?.("center")?.strength?.(layoutConfig.centerStrength);
  }, [layoutConfig]);

  useImperativeHandle(ref, () => ({
    zoomToFit: (ms = 400) => {
      fgRef.current?.zoomToFit?.(ms);
    },
    centerOnNode: (nodeId: string) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const node = (data.nodes as any[]).find((n) => n.id === nodeId);
      if (node && typeof node.x === "number") {
        fgRef.current?.centerAt?.(node.x, node.y, 600);
        fgRef.current?.zoom?.(2, 600);
      }
    },
  }));

  const nodeCanvasObject = useCallback(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (node: any, ctx: CanvasRenderingContext2D, globalScale: number) => {
      const label = node.label.length > 20 ? node.label.slice(0, 20) + "…" : node.label;
      const fontSize = 12 / globalScale;
      const isSelected = node.id === selectedNodeId;

      ctx.beginPath();
      ctx.arc(node.x, node.y, node.size, 0, 2 * Math.PI);
      ctx.fillStyle = isSelected ? "#EF4444" : NODE_COLORS[node.type] ?? "#6B7280";
      ctx.fill();

      if (isSelected) {
        ctx.strokeStyle = "#EF4444";
        ctx.lineWidth = 2 / globalScale;
        ctx.stroke();
      }

      ctx.font = `${fontSize}px Sans-Serif`;
      ctx.textAlign = "center";
      ctx.textBaseline = "top";
      ctx.fillStyle = "#1F2937";
      ctx.fillText(label, node.x, node.y + node.size + 2);
    },
    [selectedNodeId]
  );

  return (
    <div data-testid="graph-canvas" className="w-full h-full">
      <ForceGraph2D
        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
        // @ts-ignore - dynamic import loses ref typing
        ref={fgRef}
        graphData={data}
        nodeCanvasObject={nodeCanvasObject}
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        nodePointerAreaPaint={(node: any, color: string, ctx: CanvasRenderingContext2D) => {
          ctx.fillStyle = color;
          ctx.beginPath();
          ctx.arc(node.x, node.y, node.size + 2, 0, 2 * Math.PI);
          ctx.fill();
        }}
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        onNodeClick={onNodeClick as (node: any, event: MouseEvent) => void}
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        onNodeRightClick={onNodeRightClick as (node: any, event: MouseEvent) => void}
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        linkLabel={(l: any) => l.label}
        linkDirectionalArrowLength={4}
        linkDirectionalArrowRelPos={1}
        linkColor={() => "#9CA3AF"}
      />
    </div>
  );
});
