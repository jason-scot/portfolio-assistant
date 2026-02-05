#!/bin/bash

# Build and push script for MCP servers
# Usage: ./build-mcp-servers.sh

set -e  # Exit on error

# Configuration
ACR_NAME="portfolioassistantacr"
IMAGE_PREFIX="stocktrader"

echo "🚀 Building and pushing MCP servers to ACR..."

# Array of server directories
servers=("portfolio-server" "stockquote-server" "tradehistory-server")

for server in "${servers[@]}"; do
    echo "📦 Building $server..."
    
    # Build and push using az acr build
    az acr build \
        --registry $ACR_NAME \
        --image $IMAGE_PREFIX/mcp-$server:latest \
        --file mcp-servers/$server/Dockerfile \
        mcp-servers/$server/
    
    echo "✅ $server built and pushed successfully"
done

echo "🎉 All MCP servers built and pushed to ACR!"
echo ""
echo "Next steps:"
echo "1. Deploy to Kubernetes: kubectl apply -f azure-deployment.yaml -n stock-trader"
echo "2. Check deployment status: kubectl get pods -n stock-trader"
echo "3. View logs: kubectl logs deployment/mcp-portfolio-server -n stock-trader"