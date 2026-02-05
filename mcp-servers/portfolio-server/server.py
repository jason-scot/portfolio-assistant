#!/usr/bin/env python3

import asyncio
import httpx
import os
import logging
from fastapi import FastAPI
from fastapi.responses import JSONResponse
import uvicorn

# Configure logging
logging.basicConfig(level=getattr(logging, os.getenv('LOG_LEVEL', 'INFO')))
logger = logging.getLogger(__name__)

# Get environment variables
PORTFOLIO_API_URL = os.getenv('PORTFOLIO_API_URL', 'http://localhost:9080')

# Create FastAPI app
app = FastAPI(title="Portfolio MCP Server")

@app.get("/health")
async def health_check():
    return JSONResponse({"status": "healthy", "service": "portfolio-mcp-server"})

@app.get("/mcp/tools/list")
async def list_tools():
    """List available MCP tools."""
    tools = [
        {
            "name": "get_portfolio",
            "description": "Get complete portfolio information including owner, holdings, and balance",
            "input_schema": {
                "type": "object",
                "properties": {
                    "owner": {
                        "type": "string",
                        "description": "Portfolio owner identifier"
                    }
                },
                "required": ["owner"]
            }
        }
    ]
    return JSONResponse({"tools": tools})

@app.post("/mcp/tools/call")
async def call_tool(request: dict):
    """Execute an MCP tool."""
    try:
        tool_name = request.get("params", {}).get("name")
        arguments = request.get("params", {}).get("arguments", {})
        
        logger.info(f"Calling tool {tool_name} with arguments {arguments}")
        
        if tool_name == "get_portfolio":
            result = await _get_portfolio(arguments.get("owner"))
            return JSONResponse({
                "content": [{"type": "text", "text": result}]
            })
        else:
            return JSONResponse({
                "error": {"code": -1, "message": f"Unknown tool: {tool_name}"}
            }, status_code=400)
            
    except Exception as e:
        logger.error(f"Error calling tool: {e}")
        return JSONResponse({
            "error": {"code": -1, "message": str(e)}
        }, status_code=500)

@app.get("/mcp/resources/list")
async def list_resources():
    """List available MCP resources."""
    resources = [
        {
            "uri": "portfolio://owner",
            "name": "Portfolio Owner Information",
            "description": "Get portfolio owner details",
            "mime_type": "application/json"
        },
        {
            "uri": "portfolio://holdings",
            "name": "Portfolio Holdings", 
            "description": "Get current portfolio holdings",
            "mime_type": "application/json"
        }
    ]
    return JSONResponse({"resources": resources})

@app.post("/mcp/resources/read")
async def read_resource(request: dict):
    """Read a specific MCP resource."""
    try:
        uri = request.get("uri")
        
        if uri == "portfolio://owner":
            result = await _get_portfolio_owner()
        elif uri == "portfolio://holdings":
            result = await _get_portfolio_holdings()
        else:
            return JSONResponse({
                "error": {"code": -1, "message": f"Unknown resource: {uri}"}
            }, status_code=400)
            
        return JSONResponse({
            "contents": [{"uri": uri, "mime_type": "application/json", "text": result}]
        })
        
    except Exception as e:
        logger.error(f"Error reading resource: {e}")
        return JSONResponse({
            "error": {"code": -1, "message": str(e)}
        }, status_code=500)

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

async def _get_portfolio_holdings(owner: str = "test") -> str:
    """Get portfolio holdings."""
    return await _get_portfolio(owner)

async def _get_portfolio_owner() -> str:
    """Get portfolio owner information."""
    return await _get_portfolio_holdings()

if __name__ == "__main__":
    logger.info(f"Starting Portfolio MCP server on port 8000")
    logger.info(f"Portfolio API URL: {PORTFOLIO_API_URL}")
    uvicorn.run(app, host="0.0.0.0", port=8000, log_level="info")