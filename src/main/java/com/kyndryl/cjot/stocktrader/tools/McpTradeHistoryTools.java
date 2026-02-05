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
public class McpTradeHistoryTools {

    @Inject
    McpClient mcpClient;

    @ConfigProperty(name = "mcp.tradehistory.server.url")
    String serverUrl;

    private static final String SERVER_NAME = "tradehistory-mcp-server";

    @PostConstruct
    public void initializeServer() {
        try {
            mcpClient.initializeMcpServer(SERVER_NAME, serverUrl);
            log.info("Trade History MCP server initialized at: " + serverUrl);
        } catch (Exception e) {
            log.severe("Failed to initialize Trade History MCP server: " + e.getMessage());
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up Trade History MCP server connection");
    }

    @Tool(name = "get_trade_history",
            value = "Return all trade history records for the given owner/user. This shows all historical transactions (buys and sells).")
    public String getTradeHistory(@P("The username/owner whose trade history to fetch") String owner) {
        log.info("Retrieving trade history for owner via MCP: " + owner);
        try {
            Map<String, Object> arguments = Map.of("owner", owner);
            String result = mcpClient.callTool(SERVER_NAME, "get_trade_history", arguments);
            log.info("Trade history retrieved via MCP for: " + owner);
            return result;
        } catch (Exception e) {
            log.warning("Failed to retrieve trade history via MCP for owner: " + owner + ". Error: " + e.getMessage());
            return "Trade history not available for owner: " + owner + ". Error: " + e.getMessage();
        }
    }
}