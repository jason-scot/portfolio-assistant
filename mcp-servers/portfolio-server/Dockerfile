FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install -r requirements.txt

# Copy application code
COPY server_full_mcp.py .
COPY server_full_mcp.py .

# Expose ports for health checks and MCP protocol
EXPOSE 8000 9000

# Run the full MCP server (with stdio transport + health check HTTP server)
CMD ["python", "server_full_mcp.py"]