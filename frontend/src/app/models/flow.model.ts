/** Unser Flow-JSON-Format (identisch zum Backend). */
export interface FlowNode {
  id: string;
  type: string;
  name?: string;
  position: { x: number; y: number };
  config: Record<string, unknown>;
}

export interface FlowWire {
  from: { node: string; port: number };
  to: { node: string };
}

export interface FlowDefinition {
  nodes: FlowNode[];
  wires: FlowWire[];
}

export interface FlowSummary {
  id: number;
  name: string;
  description?: string;
  enabled: boolean;
  deployed: boolean;
  deployedAt?: string;
  updatedAt?: string;
}

export interface FlowDetail extends FlowSummary {
  draftDefinition?: string;
  deployedDefinition?: string;
  createdAt?: string;
}

export type NodeFieldType = 'STRING' | 'NUMBER' | 'ENUM' | 'ENTITY_REF' | 'DEVICE_REF' | 'ALEXA_DEVICE_LIST';

export interface NodeFieldDescriptor {
  key: string;
  label: string;
  type: NodeFieldType;
  required: boolean;
  options: string[];
}

export interface NodeType {
  type: string;
  outputPorts: number;
  trigger: boolean;
  portLabels: string[];
  fields: NodeFieldDescriptor[];
}

export interface ValidationResult {
  errors: string[];
  warnings: string[];
}

export interface DebugEntry {
  timestamp: string;
  label?: string;
  message: Record<string, unknown>;
}
