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

package com.kyndryl.cjot.stocktrader.websocket;

import com.kyndryl.cjot.stocktrader.assistant.PortfolioAssistant;
import com.kyndryl.cjot.stocktrader.helpers.jwt.JwtContextHolder;
import io.micrometer.core.annotation.Timed;
import io.quarkus.security.Authenticated;
import io.quarkus.websockets.next.*;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.auth.LoginConfig;
import org.eclipse.microprofile.jwt.JsonWebToken;

@WebSocket(path = "/ws/stream")
@Authenticated
@LoginConfig(authMethod = "MP-JWT", realmName = "jwt-jaspi")
public class PortfolioAssistantWebSocket {

    @Inject
    WebSocketConnection connection;

    @Inject
    JWTParser jwtParser;

    @Inject
    JwtContextHolder jwtHolder;

    @Inject
    PortfolioAssistant assistant;

    private JsonWebToken jwt;

    @OnOpen
    public void onOpen() {
        // We need to extract the JWT from the connection's handshake request
        // and set it in the JwtContextHolder for later use when calling downstream StockTrader services.
        var authHeader = connection.handshakeRequest().header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            System.err.println("No valid Authorization header found");
            connection.close(new CloseReason(400, "Missing or invalid Authorization header"));
            return;
        }
        
        String token = authHeader.substring("Bearer ".length());
        try {
            jwt = jwtParser.parse(token);
            if (jwt != null) {
                System.out.println("JWT Principal: " + jwt.getName());
            } else {
                System.out.println("No JWT Principal found.");
            }
            jwtHolder.setToken(token);
        } catch (ParseException e) {
            System.err.println("Invalid JWT");
            connection.close(new CloseReason(400, "Invalid JWT"));
        }
    }

    @OnTextMessage
    @RolesAllowed({"StockTrader", "StockViewer"})
    @Timed(description = "Time needed chatting to the agent.")
    public Multi<String> onTextMessage(String question) {
        // Simply pass through the stream as received from the LLM (service) with minimal buffering,
        // to avoid character-by-character output but without adding any artificial line breaks
        TokenBuffer buffer = new TokenBuffer();
        
        return assistant.adviceStreaming(question)
            .onItem().transform(token -> {
                buffer.addToken(token);
                return buffer.getAndEmitIfReady();
            })
            .filter(result -> !result.isEmpty())
            .onCompletion().switchTo(() -> {
                // Emit any remaining content when the stream completes
                String remaining = buffer.getRemaining();
                if (!remaining.isEmpty()) {
                    return Multi.createFrom().item(remaining);
                }
                return Multi.createFrom().empty();
            });
    }

    // Simplified helper class that simply buffers tokens to avoid character-by-character output
    // but doesn't break content artificially
    private static class TokenBuffer {
        private StringBuilder buffer = new StringBuilder();
        private int tokenCount = 0;
        private static final int BUFFER_SIZE = 20; // Larger buffer for smoother OpenAI streaming (adjustable)

        public void addToken(String token) {
            if (token != null) {
                buffer.append(token);
                tokenCount++;
            }
        }

        public String getAndEmitIfReady() {
            // Only emit when we have buffered enough tokens, rather than breaking based on punctuation or character rules
            if (tokenCount >= BUFFER_SIZE) {
                String result = buffer.toString();
                buffer.setLength(0);
                tokenCount = 0;
                return result;
            }
            
            return "";
        }

        public String getRemaining() {
            String result = buffer.toString();
            buffer.setLength(0);
            tokenCount = 0;
            return result;
        }
    }
}
