"use client";

import { LayoutConfig } from "@/types/graph";

interface GraphControlsProps {
  config: LayoutConfig;
  onChange: (config: LayoutConfig) => void;
  onResetView: () => void;
}

export const DEFAULT_LAYOUT: LayoutConfig = {
  chargeStrength: -150,
  linkDistance: 80,
  centerStrength: 0.05,
  collisionRadius: 20,
};

export function GraphControls({ config, onChange, onResetView }: GraphControlsProps) {
  return (
    <div className="flex items-center gap-6 border-b border-border bg-card p-3 [color-scheme:dark]">
      <label className="flex items-center gap-2 text-sm text-foreground">
        Abstoßung
        <input
          type="range"
          min={-500}
          max={-10}
          value={config.chargeStrength}
          onChange={(e) =>
            onChange({ ...config, chargeStrength: Number(e.target.value) })
          }
        />
      </label>
      <label className="flex items-center gap-2 text-sm text-foreground">
        Kantenlänge
        <input
          type="range"
          min={20}
          max={300}
          value={config.linkDistance}
          onChange={(e) =>
            onChange({ ...config, linkDistance: Number(e.target.value) })
          }
        />
      </label>
      <button
        onClick={() => onChange(DEFAULT_LAYOUT)}
        className="text-sm text-muted-foreground hover:text-foreground"
      >
        Zurücksetzen
      </button>
      <button
        onClick={onResetView}
        className="text-sm text-muted-foreground hover:text-foreground"
      >
        Ansicht zentrieren
      </button>
    </div>
  );
}
