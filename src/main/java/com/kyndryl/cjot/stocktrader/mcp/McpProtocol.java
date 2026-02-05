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

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * MCP JSON-RPC protocol data structures
 */
public class McpProtocol {

    @Data
    public static class McpRequest {
        private String jsonrpc = "2.0";
        private String id;
        private String method;
        private Map<String, Object> params;
        
        public McpRequest(String id, String method, Map<String, Object> params) {
            this.id = id;
            this.method = method;
            this.params = params;
        }
    }

    @Data
    public static class McpResponse {
        private String jsonrpc;
        private String id;
        private Object result;
        private McpError error;
    }

    @Data
    public static class McpError {
        private int code;
        private String message;
        private Object data;
    }

    @Data
    public static class ToolCall {
        private String name;
        private Map<String, Object> arguments;
        
        public ToolCall(String name, Map<String, Object> arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }

    @Data
    public static class ToolResult {
        private String type;
        private String text;
    }

    @Data
    public static class Tool {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
    }

    @Data
    public static class Resource {
        private String uri;
        private String name;
        private String description;
        private String mimeType;
    }
}