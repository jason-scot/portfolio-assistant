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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full MCP Protocol Client that communicates with MCP servers using JSON-RPC 2.0.
 * This client can handle stdio transport for MCP servers and dynamically discover tools.
 */
@ApplicationScoped
@Log
public class FullMcpClient {

    @ConfigProperty(name = "mcp.fullclient.portfolio.host", defaultValue = "localhost")
    String portfolioHost;
    
    @ConfigProperty(name = "mcp.fullclient.stockquote.host", defaultValue = "localhost")  
    String stockquoteHost;
    
    @ConfigProperty(name = "mcp.fullclient.tradehistory.host", defaultValue = "localhost")
    String tradehistoryHost;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, McpServerConnection> serverConnections = new ConcurrentHashMap<>();
    private final AtomicInteger requestIdGenerator = new AtomicInteger(0);

    /**
     * Represents a connection to an MCP server
     */
    private static class McpServerConnection {
        private final String serverName;
        private final String serverHost;
        private final int serverPort;
        private Process serverProcess;
        private BufferedWriter writer;
        private BufferedReader reader;
        private Socket socket;
        private boolean initialized = false;
        private Map<String, Object> serverCapabilities = new HashMap<>();
        private List<Map<String, Object>> availableTools = new ArrayList<>();

        public McpServerConnection(String serverName, String serverHost, int serverPort) {
            this.serverName = serverName;
            this.serverHost = serverHost;
            this.serverPort = serverPort;
        }

        public boolean isInitialized() { return initialized; }
        public void setInitialized(boolean initialized) { this.initialized = initialized; }
        public Map<String, Object> getServerCapabilities() { return serverCapabilities; }
        public List<Map<String, Object>> getAvailableTools() { return availableTools; }
        public void setAvailableTools(List<Map<String, Object>> tools) { this.availableTools = tools; }
        public BufferedWriter getWriter() { return writer; }
        public BufferedReader getReader() { return reader; }
        
        public void setStreams(BufferedWriter writer, BufferedReader reader) {
            this.writer = writer;
            this.reader = reader;
        }
        
        public void setSocket(Socket socket) {
            this.socket = socket;
        }
        
        public void close() {
            try {
                if (writer != null) writer.close();
                if (reader != null) reader.close();
                if (socket != null) socket.close();
                if (serverProcess != null) serverProcess.destroyForcibly();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    @PostConstruct
    public void initializeDefaultServers() {
        // Initialize default MCP servers from configuration
        if (!"localhost".equals(portfolioHost)) {
            initializeMcpServer("portfolio-mcp-server", portfolioHost, 8000);
        }
        if (!"localhost".equals(stockquoteHost)) {
            initializeMcpServer("stockquote-mcp-server", stockquoteHost, 8000);
        }
        if (!"localhost".equals(tradehistoryHost)) {
            initializeMcpServer("tradehistory-mcp-server", tradehistoryHost, 8000);
        }
    }

    /**
     * Initialize connection to an MCP server and perform MCP handshake
     */
    public boolean initializeMcpServer(String serverName, String serverHost, int serverPort) {
        log.info("Initializing full MCP server connection: " + serverName + " at " + serverHost + ":" + serverPort);
        
        McpServerConnection connection = new McpServerConnection(serverName, serverHost, serverPort);
        serverConnections.put(serverName, connection);
        
        try {
            // For Kubernetes deployment, we'll use a TCP socket connection approach
            // The MCP servers will expose a TCP port that accepts stdio-like JSON-RPC communication
            Socket socket = new Socket(serverHost, 9000); // MCP protocol port
            connection.setSocket(socket);
            
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            connection.setStreams(writer, reader);
            
            // Perform MCP initialization handshake
            if (performMcpHandshake(connection)) {
                // List available tools after handshake
                listToolsForServer(connection);
                connection.setInitialized(true);
                log.info("MCP server " + serverName + " initialized successfully with " + 
                    connection.getAvailableTools().size() + " tools");
                return true;
            } else {
                log.warning("MCP handshake failed for server: " + serverName);
                connection.close();
                serverConnections.remove(serverName);
                return false;
            }
            
        } catch (Exception e) {
            log.severe("Failed to initialize MCP server " + serverName + ": " + e.getMessage());
            connection.close();
            serverConnections.remove(serverName);
            return false;
        }
    }

    /**
     * Perform MCP initialization handshake
     */
    private boolean performMcpHandshake(McpServerConnection connection) {
        try {
            // Send initialize request
            Map<String, Object> initRequest = createJsonRpcRequest("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(
                    "tools", Map.of()
                ),
                "clientInfo", Map.of(
                    "name", "portfolio-assistant",
                    "version", "1.0.0"
                )
            ));
            
            sendMessage(connection, initRequest);
            Map<String, Object> initResponse = receiveMessage(connection);
            
            if (initResponse == null || initResponse.containsKey("error")) {
                log.severe("MCP initialization failed: " + 
                    (initResponse != null ? initResponse.get("error") : "No response"));
                return false;
            }
            
            // Store server capabilities
            Map<String, Object> result = (Map<String, Object>) initResponse.get("result");
            if (result != null) {
                connection.getServerCapabilities().putAll(result);
            }
            
            // Send initialized notification
            Map<String, Object> initializedNotification = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
            );
            
            sendMessage(connection, initializedNotification);
            
            log.info("MCP handshake completed successfully for " + connection.serverName);
            return true;
            
        } catch (Exception e) {
            log.severe("Error during MCP handshake for " + connection.serverName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * List tools available on the MCP server
     */
    private void listToolsForServer(McpServerConnection connection) {
        try {
            Map<String, Object> listToolsRequest = createJsonRpcRequest("tools/list", Map.of());
            sendMessage(connection, listToolsRequest);
            Map<String, Object> response = receiveMessage(connection);
            
            if (response != null && !response.containsKey("error")) {
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                if (result != null && result.containsKey("tools")) {
                    List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
                    connection.setAvailableTools(tools);
                    log.info("Listed " + tools.size() + " tools for server " + connection.serverName);
                }
            }
        } catch (Exception e) {
            log.warning("Failed to list tools for server " + connection.serverName + ": " + e.getMessage());
        }
    }

    /**
     * Get all available tools from all connected MCP servers
     */
    public Map<String, List<Map<String, Object>>> getAllAvailableTools() {
        Map<String, List<Map<String, Object>>> allTools = new HashMap<>();
        
        for (Map.Entry<String, McpServerConnection> entry : serverConnections.entrySet()) {
            McpServerConnection connection = entry.getValue();
            if (connection.isInitialized()) {
                allTools.put(entry.getKey(), new ArrayList<>(connection.getAvailableTools()));
            }
        }
        
        return allTools;
    }

    /**
     * Call an MCP tool on the specified server
     */
    public String callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpServerConnection connection = serverConnections.get(serverName);
        
        if (connection == null || !connection.isInitialized()) {
            log.warning("MCP server not available: " + serverName);
            return "Error: MCP server " + serverName + " is not available";
        }

        try {
            Map<String, Object> toolCallRequest = createJsonRpcRequest("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments
            ));
            
            sendMessage(connection, toolCallRequest);
            Map<String, Object> response = receiveMessage(connection);
            
            if (response != null && !response.containsKey("error")) {
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                if (result != null && result.containsKey("content")) {
                    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
                    if (!content.isEmpty()) {
                        return (String) content.get(0).get("text");
                    }
                }
            } else if (response != null) {
                Map<String, Object> error = (Map<String, Object>) response.get("error");
                log.warning("MCP tool call error: " + error);
                return "Error: " + error.get("message");
            }
            
            return "No response from MCP tool call";
            
        } catch (Exception e) {
            log.severe("Error calling MCP tool " + toolName + " on server " + serverName + ": " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Create a JSON-RPC 2.0 request
     */
    private Map<String, Object> createJsonRpcRequest(String method, Map<String, Object> params) {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdGenerator.incrementAndGet());
        request.put("method", method);
        request.put("params", params);
        return request;
    }

    /**
     * Send a JSON-RPC message to the server
     */
    private void sendMessage(McpServerConnection connection, Map<String, Object> message) throws IOException {
        String jsonMessage = objectMapper.writeValueAsString(message);
        log.fine("Sending MCP message: " + jsonMessage);
        
        connection.getWriter().write(jsonMessage);
        connection.getWriter().newLine();
        connection.getWriter().flush();
    }

    /**
     * Receive a JSON-RPC message from the server
     */
    private Map<String, Object> receiveMessage(McpServerConnection connection) throws IOException {
        String line = connection.getReader().readLine();
        if (line == null) {
            return null;
        }
        
        log.fine("Received MCP message: " + line);
        
        try {
            return objectMapper.readValue(line, Map.class);
        } catch (JsonProcessingException e) {
            log.warning("Failed to parse MCP response: " + line);
            return null;
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up full MCP client connections");
        for (McpServerConnection connection : serverConnections.values()) {
            connection.close();
        }
        serverConnections.clear();
    }
}