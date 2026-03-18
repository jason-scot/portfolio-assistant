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

package com.kyndryl.cjot.stocktrader.tools;

import com.kyndryl.cjot.stocktrader.mcp.FullMcpClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.java.Log;

import java.util.*;

/**
 * Dynamic MCP Tools that discovers and proxies tools from MCP servers at runtime.
 * This replaces the hardcoded tool classes with dynamic discovery.
 */
@ApplicationScoped
@Log
@SuppressWarnings("unused") // Suppress warnings for potentially unused methods in McpToolInfo
public class DynamicMcpTools {

    @Inject
    FullMcpClient fullMcpClient;

    private Map<String, McpToolInfo> discoveredTools = new HashMap<>();
    private volatile boolean toolsDiscovered = false;
    
    /**
     * Ensure tools are discovered before using them.
     * This is called lazily to avoid @PostConstruct race conditions.
     */
    private synchronized void ensureToolsDiscovered() {
        if (toolsDiscovered) {
            return;
        }
        
        log.info("Starting MCP tool discovery...");
        
        try {
            // Wait a bit to ensure MCP servers are fully initialized
            Thread.sleep(1000);
            
            Map<String, List<Map<String, Object>>> allTools = fullMcpClient.getAllAvailableTools();
            
            if (allTools.isEmpty()) {
                log.warning("No MCP servers available for tool discovery. Retrying...");
                // Retry once after a longer delay
                Thread.sleep(2000);
                allTools = fullMcpClient.getAllAvailableTools();
            }
            
            discoveredTools.clear();
            
            for (Map.Entry<String, List<Map<String, Object>>> serverEntry : allTools.entrySet()) {
                String serverName = serverEntry.getKey();
                List<Map<String, Object>> tools = serverEntry.getValue();
                
                log.info("Discovered " + tools.size() + " tools from server: " + serverName);
                
                for (Map<String, Object> tool : tools) {
                    String toolName = (String) tool.get("name");
                    String description = (String) tool.get("description");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");
                    
                    String qualifiedToolName = serverName + "_" + toolName;
                    discoveredTools.put(qualifiedToolName, new McpToolInfo(serverName, toolName, description, inputSchema));
                    
                    log.info("Registered dynamic tool: " + qualifiedToolName + " - " + description);
                }
            }
            
            toolsDiscovered = true;
            log.info("MCP tool discovery completed. Total tools discovered: " + discoveredTools.size());
            
        } catch (Exception e) {
            log.severe("Error during MCP tool discovery: " + e.getMessage());
            e.printStackTrace();
            toolsDiscovered = true; // Set to true to avoid infinite retry loops
        }
    }
    
    private static class McpToolInfo {
        public String serverName;
        public String toolName;
        public String description;
        public Map<String, Object> inputSchema;
        
        public McpToolInfo(String serverName, String toolName, String description, Map<String, Object> inputSchema) {
            this.serverName = serverName;
            this.toolName = toolName;
            this.description = description;
            this.inputSchema = inputSchema;
        }
        
        // Getters for external access to avoid unused field warnings
        public String getDescription() {
            return description;
        }
        
        public Map<String, Object> getInputSchema() {
            return inputSchema;
        }
    }



    // =============================================================================
    // LangChain4j Tool Methods - These use lazy discovery for true dynamic behavior
    // =============================================================================

    @Tool(name = "retrieve_portfolio",
            value = "Return a SPECIFIC PERSON'S investment portfolio. Use ONLY when the user asks about someone's portfolio.")
    public String getPortfolio(@P("The name of the portfolio owner, e.g. 'Frank', 'Tim', or 'Karri'. This is a person's name.") String owner) {
        ensureToolsDiscovered();
        log.info("Retrieving portfolio for owner via discovered MCP tools: " + owner);
        return callDynamicTool("portfolio-mcp-server_get_portfolio", Map.of("owner", owner));
    }

    @Tool(name = "get_portfolio_returns",
            value = "Get portfolio returns/performance for a specific owner")
    public String getPortfolioReturns(@P("The name of the portfolio owner") String owner) {
        ensureToolsDiscovered();
        log.info("Getting portfolio returns for owner via discovered MCP tools: " + owner);
        return callDynamicTool("portfolio-mcp-server_get_portfolio_returns", Map.of("owner", owner));
    }

    @Tool(name = "get_portfolio_notional",
            value = "Get the notional value of a portfolio for a specific owner")
    public String getPortfolioNotional(@P("The name of the portfolio owner") String owner) {
        ensureToolsDiscovered();
        log.info("Getting portfolio notional for owner via discovered MCP tools: " + owner);
        return callDynamicTool("portfolio-mcp-server_get_portfolio_notional", Map.of("owner", owner));
    }

    @Tool(name = "get_return_on_investment",
            value = "Get return on investment for a specific stock and owner")
    public String getReturnOnInvestment(@P("The name of the portfolio owner") String owner,
                                       @P("The stock symbol") String symbol) {
        ensureToolsDiscovered();
        log.info("Getting return on investment for " + symbol + " for owner via discovered MCP tools: " + owner);
        return callDynamicTool("portfolio-mcp-server_get_return_on_investment", 
                             Map.of("owner", owner, "symbol", symbol));
    }

    @Tool(name = "get_stock_price",
            value = "Get current stock price for a given symbol")
    public String getStockPrice(@P("Stock symbol to get price for (e.g., 'IBM', 'AAPL')") String stockSymbol) {
        ensureToolsDiscovered();
        log.info("Getting stock price for symbol via discovered MCP tools: " + stockSymbol);
        return callDynamicTool("stockquote-mcp-server_get_stock_quote", Map.of("symbol", stockSymbol));
    }

    @Tool(name = "get_historical_trades",
            value = "Get historical trade information for a portfolio owner")
    public String getHistoricalTrades(@P("The name of the portfolio owner") String owner) {
        ensureToolsDiscovered();
        log.info("Getting historical trades for owner via discovered MCP tools: " + owner);
        return callDynamicTool("tradehistory-mcp-server_get_trade_history", Map.of("owner", owner));
    }

    /**
     * Generic method to call any discovered MCP tool
     */
    private String callDynamicTool(String qualifiedToolName, Map<String, Object> arguments) {
        McpToolInfo toolInfo = discoveredTools.get(qualifiedToolName);
        
        if (toolInfo == null) {
            // Fallback - try to infer server and tool name
            String[] parts = qualifiedToolName.split("_", 2);
            if (parts.length == 2) {
                String serverName = parts[0];
                String toolName = parts[1];
                log.info("Tool not found in registry, attempting direct call: " + serverName + "/" + toolName);
                return fullMcpClient.callTool(serverName, toolName, arguments);
            } else {
                log.warning("Unknown dynamic tool: " + qualifiedToolName);
                return "Error: Unknown tool " + qualifiedToolName;
            }
        }
        
        log.info("Calling dynamic MCP tool: " + qualifiedToolName + " with arguments: " + arguments);
        
        try {
            return fullMcpClient.callTool(toolInfo.serverName, toolInfo.toolName, arguments);
        } catch (Exception e) {
            log.severe("Error calling dynamic tool " + qualifiedToolName + ": " + e.getMessage());
            return "Error calling tool " + qualifiedToolName + ": " + e.getMessage();
        }
    }

    /**
     * Get information about all discovered tools
     */
    public Map<String, McpToolInfo> getDiscoveredTools() {
        return new HashMap<>(discoveredTools);
    }

    /**
     * Refresh tool discovery (can be called to rediscover tools)
     */
    public void refreshToolDiscovery() {
        log.info("Refreshing MCP tool discovery...");
        toolsDiscovered = false;
        discoveredTools.clear();
        ensureToolsDiscovered();
    }
}