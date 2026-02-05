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

package com.kyndryl.cjot.stocktrader.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyndryl.cjot.stocktrader.helpers.jwt.JwtContextHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.java.Log;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client that communicates with MCP servers via HTTP.
 * This client manages HTTP communication with Kubernetes-deployed MCP servers.
 */
@ApplicationScoped
@Log
public class McpClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, McpRestClient> serverClients = new ConcurrentHashMap<>();

    @Inject
    JwtContextHolder jwtContextHolder;

    /**
     * Initialize connection to an MCP server via HTTP.
     */
    public void initializeMcpServer(String serverName, String serverUrl) {
        log.info("Initializing MCP server connection: " + serverName + " at " + serverUrl);
        
        try {
            McpRestClient client = RestClientBuilder.newBuilder()
                    .baseUri(URI.create(serverUrl))
                    .build(McpRestClient.class);
            
            serverClients.put(serverName, client);
            
            // Test connection with health check
            Response healthResponse = client.health();
            if (healthResponse.getStatus() == 200) {
                log.info("MCP server connection established: " + serverName);
            } else {
                log.warning("MCP server health check failed for " + serverName + ": " + healthResponse.getStatus());
            }
            
        } catch (Exception e) {
            log.severe("Failed to initialize MCP server " + serverName + ": " + e.getMessage());
            // Don't throw exception - allow graceful degradation
            log.warning("MCP server " + serverName + " will not be available");
        }
    }

    /**
     * Call an MCP tool on the specified server via HTTP.
     */
    public String callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpRestClient client = serverClients.get(serverName);
        
        if (client == null) {
            log.warning("MCP server not available: " + serverName + ". Returning fallback response.");
            return "MCP server " + serverName + " is not available. Please check the deployment.";
        }

        String requestId = UUID.randomUUID().toString();
        
        // Create MCP tool call request
        Map<String, Object> request = Map.of(
            "id", requestId,
            "method", "tools/call",
            "params", Map.of(
                "name", toolName,
                "arguments", arguments
            )
        );
        
        try {
            log.info("Calling MCP tool: " + toolName + " on server: " + serverName);
            
            Response response = client.callTool(request);
            
            if (response.getStatus() == 200) {
                String responseBody = response.readEntity(String.class);
                log.info("MCP tool call successful: " + responseBody);
                return responseBody;
            } else {
                String errorBody = response.readEntity(String.class);
                log.warning("MCP tool call failed with status " + response.getStatus() + ": " + errorBody);
                return "Error: " + errorBody;
            }
            
        } catch (Exception e) {
            log.severe("Error calling MCP tool " + toolName + " on server " + serverName + ": " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * List available tools on the specified MCP server.
     */
    public String listTools(String serverName) {
        McpRestClient client = serverClients.get(serverName);
        
        if (client == null) {
            log.warning("MCP server not available: " + serverName);
            return "Error: MCP server not available";
        }
        
        try {
            Response response = client.listTools();
            
            if (response.getStatus() == 200) {
                String responseBody = response.readEntity(String.class);
                log.info("MCP tools listed successfully for server: " + serverName);
                return responseBody;
            } else {
                String errorBody = response.readEntity(String.class);
                log.warning("Failed to list MCP tools for server " + serverName + ": " + errorBody);
                return "Error: " + errorBody;
            }
            
        } catch (Exception e) {
            log.severe("Error listing MCP tools for server " + serverName + ": " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * List available resources on the specified MCP server.
     */
    public String listResources(String serverName) {
        McpRestClient client = serverClients.get(serverName);
        
        if (client == null) {
            log.warning("MCP server not available: " + serverName);
            return "Error: MCP server not available";
        }
        
        try {
            Response response = client.listResources();
            
            if (response.getStatus() == 200) {
                String responseBody = response.readEntity(String.class);
                log.info("MCP resources listed successfully for server: " + serverName);
                return responseBody;
            } else {
                String errorBody = response.readEntity(String.class);
                log.warning("Failed to list MCP resources for server " + serverName + ": " + errorBody);
                return "Error: " + errorBody;
            }
            
        } catch (Exception e) {
            log.severe("Error listing MCP resources for server " + serverName + ": " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Read a specific resource from the specified MCP server.
     */
    public String readResource(String serverName, String resourceUri) {
        McpRestClient client = serverClients.get(serverName);
        
        if (client == null) {
            log.warning("MCP server not available: " + serverName);
            return "Error: MCP server not available";
        }
        
        Map<String, Object> request = Map.of(
            "uri", resourceUri
        );
        
        try {
            Response response = client.readResource(request);
            
            if (response.getStatus() == 200) {
                String responseBody = response.readEntity(String.class);
                log.info("MCP resource read successfully: " + resourceUri);
                return responseBody;
            } else {
                String errorBody = response.readEntity(String.class);
                log.warning("Failed to read MCP resource " + resourceUri + ": " + errorBody);
                return "Error: " + errorBody;
            }
            
        } catch (Exception e) {
            log.severe("Error reading MCP resource " + resourceUri + " from server " + serverName + ": " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Clean up server connections.
     */
    public void cleanup() {
        log.info("Cleaning up MCP client connections");
        serverClients.clear();
    }
}