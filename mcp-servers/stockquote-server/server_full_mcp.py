#!/usr/bin/env python3

import asyncio
import httpx
import os
import logging
from mcp.server.fastmcp import FastMCP
from mcp.types import Resource, Tool
from typing import Any, Sequence
import uvicorn
from fastapi import FastAPI
from fastapi.responses import JSONResponse

# Configure logging
logging.basicConfig(level=getattr(logging, os.getenv('LOG_LEVEL', 'INFO')))
logger = logging.getLogger(__name__)

# Get environment variables
STOCKQUOTE_API_URL = os.getenv('STOCKQUOTE_API_URL', 'http://localhost:9080')

# Create FastAPI app for health checks
health_app = FastAPI(title="StockQuote MCP Server Health")

@health_app.get("/health")
async def health_check():
    return JSONResponse({"status": "healthy", "service": "stockquote-mcp-server"})

# Create the MCP server
mcp = FastMCP("StockQuote Server")

@mcp.list_resources()
async def list_resources() -> list[Resource]:
    """List available stock quote resources."""
    return [
        Resource(
            uri="stockquote://quote",
            name="Stock Quote",
            description="Get current stock price information",
            mimeType="application/json"
        ),
        Resource(
            uri="stockquote://market-data",
            name="Market Data", 
            description="Get general market data and trends",
            mimeType="application/json"
        )
    ]

@mcp.read_resource()
async def read_resource(uri: str) -> str:
    """Read a specific stock quote resource."""
    if uri == "stockquote://quote":
        return "Use get_stock_quote tool to fetch specific stock prices"
    elif uri == "stockquote://market-data":
        return "Use get_stock_quote tool to fetch market information"
    else:
        raise ValueError(f"Unknown resource: {uri}")

@mcp.list_tools()
async def list_tools() -> list[Tool]:
    """List available stock quote tools."""
    return [
        Tool(
            name="get_stock_quote",
            description="Get current stock price and quote information",
            inputSchema={
                "type": "object",
                "properties": {
                    "symbol": {
                        "type": "string",
                        "description": "Stock symbol (e.g., AAPL, GOOGL, MSFT)"
                    }
                },
                "required": ["symbol"]
            }
        )
    ]

@mcp.call_tool()
async def call_tool(name: str, arguments: dict) -> Sequence[dict]:
    """Execute a stock quote tool."""
    logger.info(f"Calling tool {name} with arguments {arguments}")
    
    if name == "get_stock_quote":
        result = await _get_stock_quote(arguments["symbol"])
    else:
        raise ValueError(f"Unknown tool: {name}")
    
    return [{"type": "text", "text": result}]

async def _get_stock_quote(symbol: str) -> str:
    """Get stock quote for a specific symbol."""
    try:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{STOCKQUOTE_API_URL}/stock-quote?symbol={symbol}")
            response.raise_for_status()
            logger.info(f"Successfully retrieved stock quote for {symbol}")
            return response.text
    except httpx.RequestError as e:
        logger.error(f"Request error getting stock quote for {symbol}: {e}")
        return f"Error retrieving stock quote: {str(e)}"
    except httpx.HTTPStatusError as e:
        logger.error(f"HTTP error getting stock quote for {symbol}: {e.response.status_code}")
        return f"HTTP error {e.response.status_code}: {e.response.text}"

if __name__ == "__main__":
    import threading
    import socket
    
    # Start health check server in a separate thread
    def run_health_server():
        uvicorn.run(health_app, host="0.0.0.0", port=8000, log_level="info")
    
    health_thread = threading.Thread(target=run_health_server, daemon=True)
    health_thread.start()
    
    # TCP socket server for MCP protocol communication  
    def run_tcp_mcp_server():
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_socket.bind(('0.0.0.0', 9000))  # MCP protocol port
        server_socket.listen(5)
        
        logger.info("MCP TCP server listening on port 9000")
        
        while True:
            try:
                client_socket, address = server_socket.accept()
                logger.info(f"MCP client connected from {address}")
                
                # Handle client in a new thread
                def handle_client(sock):
                    try:
                        # Create pseudo-stdin/stdout for MCP server
                        import sys
                        
                        # Redirect stdin/stdout to socket
                        socket_file = sock.makefile('rw')
                        
                        # Run MCP server with socket as stdio
                        original_stdin = sys.stdin
                        original_stdout = sys.stdout
                        
                        sys.stdin = socket_file
                        sys.stdout = socket_file
                        
                        try:
                            asyncio.run(mcp.run())
                        finally:
                            sys.stdin = original_stdin
                            sys.stdout = original_stdout
                            socket_file.close()
                            
                    except Exception as e:
                        logger.error(f"Error handling MCP client: {e}")
                    finally:
                        sock.close()
                
                client_thread = threading.Thread(target=handle_client, args=(client_socket,))
                client_thread.daemon = True
                client_thread.start()
                
            except Exception as e:
                logger.error(f"Error in TCP MCP server: {e}")
    
    # Start TCP MCP server in separate thread
    tcp_thread = threading.Thread(target=run_tcp_mcp_server, daemon=True)
    tcp_thread.start()
    
    logger.info(f"Starting StockQuote MCP server with stdio transport")
    logger.info(f"Health check available at http://0.0.0.0:8000/health")
    logger.info(f"MCP protocol available at tcp://0.0.0.0:9000")
    logger.info(f"StockQuote API URL: {STOCKQUOTE_API_URL}")
    
    # Run MCP server with stdio transport (main thread)
    asyncio.run(mcp.run())