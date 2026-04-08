"use client";

import "@react-sigma/core/lib/style.css";

import {
  ControlsContainer,
  FullScreenControl,
  SigmaContainer,
  ZoomControl,
  useCamera,
  useLoadGraph,
  useRegisterEvents,
  useSetSettings,
  useSigma,
} from "@react-sigma/core";
import {
  LayoutForceAtlas2Control,
  useWorkerLayoutForceAtlas2,
} from "@react-sigma/layout-forceatlas2";
import { MiniMap } from "@react-sigma/minimap";
import Graph, { MultiDirectedGraph } from "graphology";
import { ForceAtlas2Settings } from "graphology-layout-forceatlas2";
import { FC, useEffect, useState } from "react";

import { buildEdgeReducer, buildNodeReducer } from "@/lib/graph/highlight";
import { EdgeAttributes, NodeAttributes } from "@/types/graph";

export interface GraphCanvasInnerProps {
  graph: Graph<NodeAttributes, EdgeAttributes>;
  version: number;
  selectedNodeId: string | null;
  focus: { id: string; nonce: number } | null;
  onNodeClick: (nodeId: string) => void;
  onNodeRightClick: (nodeId: string) => void;
}

const SIGMA_STYLE = {
  height: "100%",
  width: "100%",
  backgroundColor: "var(--background)",
};

const LABEL_COLOR = "#E5E7EB";
const HOVER_BG = "#1f2638";
const HOVER_BORDER = "rgba(255,255,255,0.12)";

/**
 * Custom hover renderer: sigma's built-in drawDiscNodeHover hardcodes a white
 * background box, which makes our light label text (#E5E7EB) unreadable on
 * the dark canvas theme. This renders a dark rounded box with a subtle
 * border instead and then draws the label on top.
 */
function drawDarkNodeHover(
  context: CanvasRenderingContext2D,
  data: { x: number; y: number; size: number; label?: string | null },
  settings: { labelSize: number; labelFont: string; labelWeight: string },
) {
  const { labelSize, labelFont, labelWeight } = settings;
  context.font = `${labelWeight} ${labelSize}px ${labelFont}`;
  const PADDING = 4;

  if (typeof data.label === "string" && data.label.length > 0) {
    const textWidth = context.measureText(data.label).width;
    const boxWidth = Math.round(textWidth + PADDING * 3);
    const boxHeight = Math.round(labelSize + PADDING * 2);
    const boxX = Math.round(data.x + data.size + PADDING);
    const boxY = Math.round(data.y - boxHeight / 2);
    const radius = 4;

    context.fillStyle = HOVER_BG;
    context.strokeStyle = HOVER_BORDER;
    context.lineWidth = 1;
    context.shadowColor = "rgba(0,0,0,0.45)";
    context.shadowBlur = 8;
    context.beginPath();
    if (typeof context.roundRect === "function") {
      context.roundRect(boxX, boxY, boxWidth, boxHeight, radius);
    } else {
      context.rect(boxX, boxY, boxWidth, boxHeight);
    }
    context.fill();
    context.stroke();
    context.shadowBlur = 0;

    context.fillStyle = LABEL_COLOR;
    context.fillText(data.label, boxX + PADDING * 1.5, data.y + labelSize / 3);
  } else {
    context.fillStyle = HOVER_BG;
    context.shadowColor = "rgba(0,0,0,0.45)";
    context.shadowBlur = 8;
    context.beginPath();
    context.arc(data.x, data.y, data.size + PADDING, 0, Math.PI * 2);
    context.closePath();
    context.fill();
    context.shadowBlur = 0;
  }
}

const SIGMA_SETTINGS = {
  allowInvalidContainer: true,
  defaultEdgeType: "arrow",
  renderEdgeLabels: true,
  labelRenderedSizeThreshold: 6,
  labelDensity: 0.4,
  labelFont: "Inter, system-ui, sans-serif",
  labelSize: 13,
  labelWeight: "500",
  labelColor: { color: LABEL_COLOR },
  edgeLabelColor: { color: "#9CA3AF" },
  edgeLabelSize: 11,
  defaultDrawNodeHover: drawDarkNodeHover,
};

const FA2_SETTINGS: ForceAtlas2Settings = { slowDown: 10, gravity: 1, scalingRatio: 10 };

/** ForceAtlas2LayoutParameters wrapper used by react-sigma hooks/controls */
const FA2_PARAMS = { settings: FA2_SETTINGS };

const LoadGraphFromStore: FC<{
  graph: Graph<NodeAttributes, EdgeAttributes>;
  version: number;
}> = ({ graph, version }) => {
  const loadGraph = useLoadGraph();
  useEffect(() => {
    // Pass the live graph instance directly. graph.copy() loses the multi:true
    // option in graphology 0.26 when re-adding parallel edges, which crashes
    // for RDF graphs that have multiple predicates between the same entities.
    // Sigma observes graphology events on the live instance, and the version
    // counter in the dep array triggers re-load on every mutation cycle.
    loadGraph(graph);
  }, [graph, version, loadGraph]);
  return null;
};

const WorkerLayout: FC = () => {
  const { start, kill } = useWorkerLayoutForceAtlas2(FA2_PARAMS);
  useEffect(() => {
    start();
    return () => kill();
  }, [start, kill]);
  return null;
};

const GraphEvents: FC<{
  selectedNodeId: string | null;
  onNodeClick: (id: string) => void;
  onNodeRightClick: (id: string) => void;
}> = ({ selectedNodeId, onNodeClick, onNodeRightClick }) => {
  const sigma = useSigma();
  const registerEvents = useRegisterEvents();
  const setSettings = useSetSettings();
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);

  useEffect(() => {
    registerEvents({
      clickNode: (e) => onNodeClick(e.node),
      rightClickNode: (e) => {
        e.preventSigmaDefault();
        onNodeRightClick(e.node);
      },
      enterNode: (e) => setHoveredNode(e.node),
      leaveNode: () => setHoveredNode(null),
    });
  }, [registerEvents, onNodeClick, onNodeRightClick]);

  useEffect(() => {
    const active = hoveredNode ?? selectedNodeId;
    const sigmaGraph = sigma.getGraph() as Graph<NodeAttributes, EdgeAttributes>;
    setSettings({
      nodeReducer: buildNodeReducer(sigmaGraph, active),
      edgeReducer: buildEdgeReducer(sigmaGraph, active),
    });
  }, [hoveredNode, selectedNodeId, sigma, setSettings]);

  return null;
};

const FocusOnNode: FC<{ focus: { id: string; nonce: number } | null }> = ({ focus }) => {
  const { gotoNode } = useCamera({ duration: 600 });
  useEffect(() => {
    if (focus) gotoNode(focus.id);
  }, [focus, gotoNode]);
  return null;
};

const GraphCanvasInner: FC<GraphCanvasInnerProps> = ({
  graph,
  version,
  selectedNodeId,
  focus,
  onNodeClick,
  onNodeRightClick,
}) => (
  <div data-testid="graph-canvas" className="h-full w-full">
    <SigmaContainer
      graph={MultiDirectedGraph}
      style={SIGMA_STYLE}
      settings={SIGMA_SETTINGS}
    >
      <LoadGraphFromStore graph={graph} version={version} />
      <WorkerLayout />
      <GraphEvents
        selectedNodeId={selectedNodeId}
        onNodeClick={onNodeClick}
        onNodeRightClick={onNodeRightClick}
      />
      <FocusOnNode focus={focus} />

      <ControlsContainer position="bottom-right">
        <ZoomControl />
        <FullScreenControl />
        <LayoutForceAtlas2Control settings={FA2_PARAMS} />
      </ControlsContainer>
      <ControlsContainer position="bottom-left">
        <MiniMap width="140px" height="140px" />
      </ControlsContainer>
    </SigmaContainer>
  </div>
);

export default GraphCanvasInner;
