#!/usr/bin/env node
/**
 * MCP-Server (stdio) für die Flow-Engine des Household-Managers.
 * Dünner Wrapper über die REST-API des Spring-Boot-Backends, damit KI-Agenten
 * Flows anlegen, ändern, deployen, (de)aktivieren und testen können.
 *
 * Konfiguration: HOUSEHOLD_API_URL (Default http://localhost:8080/api).
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { ApiError, createApiClient } from './api-client.js';
import { toolDefinitions } from './tools.js';

function formatError(error) {
  if (error instanceof ApiError) {
    const body = typeof error.body === 'string' ? error.body : JSON.stringify(error.body, null, 2);
    return `Fehler: Backend antwortete mit HTTP ${error.status}.\n${body ?? ''}`.trim();
  }
  return `Fehler: ${error instanceof Error ? error.message : String(error)}`;
}

const server = new McpServer({ name: 'flow-mcp-server', version: '1.0.0' });
const client = createApiClient();

for (const tool of toolDefinitions) {
  server.registerTool(
    tool.name,
    {
      title: tool.title,
      description: tool.description,
      inputSchema: tool.inputSchema,
      annotations: tool.annotations,
    },
    async (params) => {
      try {
        const result = await tool.handler(client, params ?? {});
        return { content: [{ type: 'text', text: JSON.stringify(result, null, 2) }] };
      } catch (error) {
        return { content: [{ type: 'text', text: formatError(error) }], isError: true };
      }
    }
  );
}

const transport = new StdioServerTransport();
await server.connect(transport);
console.error(`flow-mcp-server läuft (stdio), Backend: ${client.baseUrl}`);
