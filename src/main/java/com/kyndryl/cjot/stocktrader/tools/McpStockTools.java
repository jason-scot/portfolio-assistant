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

import com.kyndryl.cjot.stocktrader.mcp.McpClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.Map;

@ApplicationScoped
@Log
public class McpStockTools {

    @Inject
    McpClient mcpClient;

    @ConfigProperty(name = "mcp.stockquote.server.url")
    String serverUrl;

    private static final String SERVER_NAME = "stockquote-mcp-server";

    @PostConstruct
    public void initializeServer() {
        try {
            mcpClient.initializeMcpServer(SERVER_NAME, serverUrl);
            log.info("Stock Quote MCP server initialized at: " + serverUrl);
        } catch (Exception e) {
            log.severe("Failed to initialize Stock Quote MCP server: " + e.getMessage());
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up Stock Quote MCP server connection");
    }

    @Tool(name = "get_stock_price",
            value = "Return the live/most-recent market price for a PUBLIC TICKER (e.g., 'TSLA'). Use ONLY for public stock price/quote questions." +
                    "Use to fetch the current price for tickers you're recommending. Do NOT use for personal portfolios.")
    public String getStockPrice(@P("The public stock ticker symbol to look up") String stockSymbol) {
        log.info("Retrieving stock quote for symbol via MCP: " + stockSymbol);
        try {
            Map<String, Object> arguments = Map.of("symbol", stockSymbol);
            String result = mcpClient.callTool(SERVER_NAME, "get_stock_quote", arguments);
            log.info("Stock quote retrieved via MCP for: " + stockSymbol);
            return result;
        } catch (Exception e) {
            log.warning("Failed to retrieve stock quote via MCP for symbol: " + stockSymbol + ". Error: " + e.getMessage());
            return "Stock quote not available for symbol: " + stockSymbol + ". Error: " + e.getMessage();
        }
    }
}