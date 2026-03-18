/*
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.kyndryl.cjot.stocktrader.rest;

import com.kyndryl.cjot.stocktrader.mcp.FullMcpClient;
import com.kyndryl.cjot.stocktrader.tools.DynamicMcpTools;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.java.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug endpoint for MCP operations and tool discovery
 */
@Path("/debug/mcp")
@Log
public class McpDebugResource {

    @Inject
    FullMcpClient fullMcpClient;

    @Inject  
    DynamicMcpTools dynamicMcpTools;

    @GET
    @Path("/tools")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDiscoveredTools() {
        try {
            log.info("Getting discovered MCP tools");
            
            Map<String, Object> result = new HashMap<>();
            result.put("discoveredTools", dynamicMcpTools.getDiscoveredTools());
            result.put("allAvailableTools", fullMcpClient.getAllAvailableTools());
            result.put("timestamp", System.currentTimeMillis());
            
            return Response.ok(result).build();
            
        } catch (Exception e) {
            log.severe("Error getting discovered tools: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshToolDiscovery() {
        try {
            log.info("Refreshing MCP tool discovery");
            
            dynamicMcpTools.refreshToolDiscovery();
            
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Tool discovery refreshed successfully");
            result.put("discoveredTools", dynamicMcpTools.getDiscoveredTools());
            result.put("timestamp", System.currentTimeMillis());
            
            return Response.ok(result).build();
            
        } catch (Exception e) {
            log.severe("Error refreshing tool discovery: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMcpStatus() {
        try {
            log.info("Getting MCP client status");
            
            Map<String, Object> result = new HashMap<>();
            Map<String, List<Map<String, Object>>> allTools = fullMcpClient.getAllAvailableTools();
            
            Map<String, Object> serverStatus = new HashMap<>();
            for (String serverName : allTools.keySet()) {
                Map<String, Object> status = new HashMap<>();
                status.put("connected", true);
                status.put("toolCount", allTools.get(serverName).size());
                serverStatus.put(serverName, status);
            }
            
            result.put("servers", serverStatus);
            result.put("totalServers", allTools.size());
            result.put("timestamp", System.currentTimeMillis());
            
            return Response.ok(result).build();
            
        } catch (Exception e) {
            log.severe("Error getting MCP status: " + e.getMessage());
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }
}