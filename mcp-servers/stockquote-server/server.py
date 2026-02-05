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
STOCKQUOTE_API_URL = os.getenv('STOCKQUOTE_API_URL', 'http://localhost:9080')

# Create FastAPI app
app = FastAPI(title="StockQuote MCP Server")

@app.get("/health")
async def health_check():
    return JSONResponse({"status": "healthy", "service": "stockquote-mcp-server"})

@app.get("/mcp/tools/list")
async def list_tools():
    """List available MCP tools."""
    tools = [
        {
            "name": "get_stock_quote",
            "description": "Get current stock price and quote information",
            "input_schema": {
                "type": "object",
                "properties": {
                    "symbol": {
                        "type": "string",
                        "description": "Stock symbol (e.g., AAPL, GOOGL, MSFT)"
                    }
                },
                "required": ["symbol"]
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
        
        if tool_name == "get_stock_quote":
            result = await _get_stock_quote(arguments.get("symbol"))
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
    logger.info(f"Starting StockQuote MCP server on port 8000")
    logger.info(f"StockQuote API URL: {STOCKQUOTE_API_URL}")
    uvicorn.run(app, host="0.0.0.0", port=8000, log_level="info")