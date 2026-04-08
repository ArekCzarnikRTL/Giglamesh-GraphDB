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
import Graph from "graphology";
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

const SIGMA_STYLE = { height: "100%", width: "100%" };

const SIGMA_SETTINGS = {
  allowInvalidContainer: true,
  defaultEdgeType: "arrow",
  renderEdgeLabels: true,
  labelRenderedSizeThreshold: 8,
  labelDensity: 0.2,
  labelColor: { color: "#E5E7EB" },
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
    // graphology graph is mutable; copy() snapshots the current state into sigma.
    loadGraph(graph.copy());
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
    <SigmaContainer style={SIGMA_STYLE} settings={SIGMA_SETTINGS}>
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
