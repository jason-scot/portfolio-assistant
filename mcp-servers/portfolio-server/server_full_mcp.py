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
PORTFOLIO_API_URL = os.getenv('PORTFOLIO_API_URL', 'http://localhost:9080')

# Create FastAPI app for health checks and HTTP transport
health_app = FastAPI(title="Portfolio MCP Server Health")

@health_app.get("/health")
async def health_check():
    return JSONResponse({"status": "healthy", "service": "portfolio-mcp-server"})

# Create the MCP server
mcp = FastMCP("Portfolio Server")

@mcp.list_resources()
async def list_resources() -> list[Resource]:
    """List available portfolio resources."""
    return [
        Resource(
            uri="portfolio://owner",
            name="Portfolio Owner Information", 
            description="Get portfolio owner details",
            mimeType="application/json"
        ),
        Resource(
            uri="portfolio://holdings", 
            name="Portfolio Holdings",
            description="Get portfolio stock holdings",
            mimeType="application/json"
        )
    ]

@mcp.read_resource()
async def read_resource(uri: str) -> str:
    """Read a specific portfolio resource."""
    if uri == "portfolio://owner":
        return "Use get_portfolio tool to fetch specific owner information"
    elif uri == "portfolio://holdings":
        return "Use get_portfolio tool to fetch portfolio holdings"
    else:
        raise ValueError(f"Unknown resource: {uri}")

@mcp.list_tools()
async def list_tools() -> list[Tool]:
    """List available portfolio tools."""
    return [
        Tool(
            name="get_portfolio",
            description="Get complete portfolio information including owner, holdings, and balance",
            inputSchema={
                "type": "object",
                "properties": {
                    "owner": {
                        "type": "string",
                        "description": "Portfolio owner identifier"
                    }
                },
                "required": ["owner"]
            }
        )
    ]

@mcp.call_tool()
async def call_tool(name: str, arguments: dict) -> Sequence[dict]:
    """Execute a portfolio tool."""
    logger.info(f"Calling tool {name} with arguments {arguments}")
    
    if name == "get_portfolio":
        result = await _get_portfolio(arguments["owner"])
    else:
        raise ValueError(f"Unknown tool: {name}")
    
    return [{"type": "text", "text": result}]

async def _get_portfolio(owner: str) -> str:
    """Get complete portfolio information."""
    try:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{PORTFOLIO_API_URL}/portfolio/{owner}")
            response.raise_for_status()
            logger.info(f"Successfully retrieved portfolio for {owner}")
            return response.text
    except httpx.RequestError as e:
        logger.error(f"Request error getting portfolio for {owner}: {e}")
        return f"Error retrieving portfolio: {str(e)}"
    except httpx.HTTPStatusError as e:
        logger.error(f"HTTP error getting portfolio for {owner}: {e.response.status_code}")
        return f"HTTP error {e.response.status_code}: {e.response.text}"

if __name__ == "__main__":
    # Run both the health check server and MCP server
    import threading
    
    # Start health check server in a separate thread
    def run_health_server():
        uvicorn.run(health_app, host="0.0.0.0", port=8000, log_level="info")
    
    health_thread = threading.Thread(target=run_health_server, daemon=True)
    health_thread.start()
    
    # TCP socket server for MCP protocol communication  
    def run_tcp_mcp_server():
        import socket
        
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
    
    logger.info(f"Starting Portfolio MCP server with stdio transport")
    logger.info(f"Health check available at http://0.0.0.0:8000/health")
    logger.info(f"MCP protocol available at tcp://0.0.0.0:9000")
    logger.info(f"Portfolio API URL: {PORTFOLIO_API_URL}")
    
    # Run MCP server with stdio transport (main thread)
    asyncio.run(mcp.run())