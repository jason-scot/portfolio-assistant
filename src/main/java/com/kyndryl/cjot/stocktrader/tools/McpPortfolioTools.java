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
public class McpPortfolioTools {

    @Inject
    McpClient mcpClient;

    @ConfigProperty(name = "mcp.portfolio.server.url")
    String serverUrl;

    private static final String SERVER_NAME = "portfolio-mcp-server";

    @PostConstruct
    public void initializeServer() {
        try {
            mcpClient.initializeMcpServer(SERVER_NAME, serverUrl);
            log.info("Portfolio MCP server initialized at: " + serverUrl);
        } catch (Exception e) {
            log.severe("Failed to initialize Portfolio MCP server: " + e.getMessage());
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up Portfolio MCP server connection");
    }

    @Tool(name = "retrieve_portfolio",
            value = "Return a SPECIFIC PERSON'S investment portfolio. Use ONLY when the user asks about someone's portfolio.")
    public String getPortfolio(@P("The name of the portfolio owner, e.g. 'Frank', 'Tim', or 'Karri' . This is a person's name.") String owner) {
        log.info("Retrieving portfolio for owner via MCP: " + owner);
        try {
            Map<String, Object> arguments = Map.of("owner", owner);
            String result = mcpClient.callTool(SERVER_NAME, "get_portfolio", arguments);
            log.info("Portfolio retrieved via MCP for: " + owner);
            return result;
        } catch (Exception e) {
            log.warning("Failed to retrieve portfolio via MCP for owner: " + owner + ". Error: " + e.getMessage());
            return "Portfolio not available for owner: " + owner + ". Error: " + e.getMessage();
        }
    }
}