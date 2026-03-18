# Portfolio Assistant
### Stock Trader AI Helper with Azure OpenAI and MCP

Manage your portfolio with the help of AI. This application is part of the Stock Trader solution and provides insights and recommendations based on your stock portfolio using GPT-4o via the Azure OpenAI service.

This requires the Portfolio and Stock Quote microservices to be running. Additional microservices may be required as development continues. In this implementation, dedicated MCP servers are deployed on Kubernetes which act as intermediaries between the AI assistant and the Stock Trader microservices, which are unchanged from their own separate deployment and which remain accessible via their original APIs.

**Architecture Components:**
- **Portfolio Assistant**: Main application with Azure OpenAI integration
- **MCP Portfolio Server**: Provides portfolio data access via MCP protocol
- **MCP Stock Quote Server**: Provides stock quote functionality via MCP protocol  
- **MCP Trade History Server**: Provides trade history access via MCP protocol (NOTE: the trade history service may not be running, as currently configured by the stock trader terraform scripts)

# Prerequisites
### On Azure
Stocktrader running on Azure; this setup assumes it was spun up via our Terraform scripts

### Local
* Java: `JDK@21`, installed via homebrew is simplest if on Mac
* `docker` CLI commands via Docker Desktop, Podman, Rancher, or similar
* `wscat` npm package, installed globally via npm is simplest
* An open terminal having successfully ran `az login` into the subscription you will use
* `export` the following local env variables for ease of use running the scripts below
  * `export RG_STOCKTRADER=`<*Your Azure Resource Group*>
  * `export AVZONE_OPENAI=`<*Availability Zone to deploy OpenAI service to*> (NOTE: for lowest latency, use the closest AZ to the AZ of RG_STOCKTRADER that supports Azure OpenAI service, recognizing that not all AZs support OpenAI)
  * `export OWNER_EMAIL=`<*Email Address of the Resources Owner*>
  * `export JWT_ST=`<*Stock Trader App JWT*>
    * To find this, go to your Stocktrader URL, sign in, and find the JWT in cookies (browser local storage), and save the JWT as this environment variable for ease of use

# Deployment on Azure

## Create Azure OpenAI Service
Create an Azure OpenAI service instance. Note that Azure OpenAI availability varies by region - check the [Azure OpenAI Service regions page](https://docs.microsoft.com/en-us/azure/cognitive-services/openai/concepts/regions) for current availability. Also note that Quarkus LangChain4j which is used by this project has hard-coded requirements (limits) for which ChatGPT API versions it can tolerate, so make sure you are using a compatible API/ChatGPT version.

**Important**: This guide uses the regional endpoint format (`https://<region>.api.cognitive.microsoft.com`) which is the current Azure standard, not the legacy custom subdomain format.

```bash
# Create the Azure OpenAI service
az cognitiveservices account create \
  --name portfolio-assistant-openai \
  --resource-group $RG_STOCKTRADER \
  --location $AVZONE_OPENAI \
  --kind OpenAI \
  --sku S0 \
  --tags owner=$OWNER_EMAIL created-by=$OWNER_EMAIL purpose="AI and Stock Trader work" solution=stocktrader-portfolio-assistant

# Deploy GPT-4o model
az cognitiveservices account deployment create \
  --name portfolio-assistant-openai \
  --resource-group $RG_STOCKTRADER \
  --deployment-name gpt-4o \
  --model-name gpt-4o \
  --model-version "2024-08-06" \
  --model-format OpenAI \
  --sku-capacity 10 \
  --sku-name "Standard"
```

## Create Kubernetes Secret for Azure OpenAI
Get the API key and create the Kubernetes secret:

```bash
# Get the API key
export AZURE_OPENAI_API_KEY=$(az cognitiveservices account keys list \
  --name portfolio-assistant-openai \
  --resource-group $RG_STOCKTRADER \
  --query "key1" --output tsv)

# Create the Kubernetes secret with the regional deployment endpoint
# Note: Use the regional endpoint format, not the custom subdomain format
kubectl create secret generic azure-openai-secret \
  --namespace stock-trader \
  --from-literal=AZURE_OPENAI_API_KEY="$AZURE_OPENAI_API_KEY" \
  --from-literal=AZURE_OPENAI_ENDPOINT="https://$AVZONE_OPENAI.api.cognitive.microsoft.com/openai/deployments/gpt-4o"
```

## Create the Azure Container Registry (ACR)
```bash
az acr create \
  --name portfolioassistantacr \
  --resource-group $RG_STOCKTRADER \
  --sku Standard \
  --location $AVZONE_OPENAI \
  --tags owner=$OWNER_EMAIL purpose="GH Actions Build" solution=stocktrader-portfolio-assistant
```

## Build the app image and push it to the registry
```bash
mvn clean install
```

```bash
az acr build \
  --registry portfolioassistantacr \
  --image ibmstocktrader/portfolioassistant:latest \
  --file src/main/docker/Dockerfile.jvm \
  .
```

NOTE: If not already done, update `azure-deployment.yaml` file to use the image repo you want to use; default value is: `portfolioassistantacr.azurecr.io/ibmstocktrader/portfolioassistant:latest`

## Build and Push MCP Server Images
```bash
# Build and push all MCP server images to ACR
./build-mcp-servers.sh
```

NOTE: The `azure-deployment.yaml` file includes deployments for both the main application and all MCP servers with the correct image references.

## Create an ImagePullSecret
First, enable admin on the ACR
```bash
az acr update -n portfolioassistantacr --admin-enabled true
```

Then, should be able to do this with the following one-liner
```bash
kubectl create secret docker-registry acr-auth --namespace stock-trader --docker-server=portfolioassistantacr.azurecr.io --docker-username=$(az acr credential show --name portfolioassistantacr --query 'username' -o tsv) --docker-password=$(az acr credential show --name portfolioassistantacr --query 'passwords[0].value' -o tsv)
```
### BUT
If the one-liner does not work, then try as follows
  > Get the username and first password (passwords[0].value) to the ACR
  > ```bash
  > az acr credential show --name portfolioassistantacr
  > ```
  > Create the Kubernetes secret using those values. This will create a secret named acr-auth, which the azure-deployment.yaml file should know about in spec.template.spec
  > ```bash
  > kubectl create secret docker-registry acr-auth \
  >   --namespace stock-trader \
  >   --docker-server=portfolioassistantacr.azurecr.io \
  >   --docker-username=<username> \
  >   --docker-password=<password>
  > ```

## Deploy the portfolio-assistant application and the MCP servers
```bash
# Deploy both the main application and all MCP servers
kubectl apply -f azure-deployment.yaml -n stock-trader

# Check deployment status
kubectl get pods -n stock-trader | grep -E '(portfolioassistant|mcp-)'

# Verify MCP servers are healthy
kubectl get services -n stock-trader | grep mcp
```

This will deploy:
- **portfolioassistant**: Main AI assistant application  
- **mcp-portfolio-server**: MCP server for portfolio data
- **mcp-stockquote-server**: MCP server for stock quotes
- **mcp-tradehistory-server**: MCP server for trade history
- Associated Kubernetes services for internal communication

# MCP Architecture Details

## Model Context Protocol (MCP) Integration
The Portfolio Assistant uses the **full Model Context Protocol** implementation for AI tool access to Stock Trader data. This includes:

- **JSON-RPC 2.0 Protocol**: Complete MCP protocol compliance with proper handshake
- **Dynamic Tool Discovery**: Tools are discovered at runtime from MCP servers
- **Stdio/TCP Transport**: Supports both stdio and TCP socket communication
- **Official MCP Library**: Uses the official `mcp` Python library with FastMCP framework

### MCP Server Architecture
Each MCP server exposes dual transports:
- **Port 8000**: HTTP health checks for Kubernetes probes
- **Port 9000**: TCP socket MCP protocol for client communication
- **stdio**: Standard MCP protocol for direct process communication

### Available MCP Tools (Dynamically Discovered)
- **get_portfolio** (Portfolio Server): Retrieves complete portfolio information
- **get_portfolio_notional** (Portfolio Server): Gets portfolio notional value
- **get_portfolio_returns** (Portfolio Server): Calculates portfolio returns
- **get_return_on_investment** (Portfolio Server): ROI for specific stocks
- **get_stock_quote** (Stock Quote Server): Current stock prices
- **get_trade_history** (Trade History Server): Historical trade information

### Internal Communication
The Java application uses full MCP protocol via TCP sockets:
- `mcp-portfolio-server-service.stock-trader.svc.cluster.local:9000`
- `mcp-stockquote-server-service.stock-trader.svc.cluster.local:9000`  
- `mcp-tradehistory-server-service.stock-trader.svc.cluster.local:9000`

# Testing the Application

## Set up port forwarding
Get the pods
```bash
kubectl get po -n stock-trader
```
In a new terminal, create an environment variable with the name of the pod running the portfolio-assistant service with `export ASSISTANT_POD_NAME=`<*Pod Name*>

In a second new terminal, run
  ```bash
  kubectl port-forward pod/$ASSISTANT_POD_NAME -n stock-trader 8081:8080
  ```

## Test MCP Tool Discovery (Full MCP Verification)
Before testing the AI functionality, verify that the full MCP implementation is working:

```bash
# Check MCP server connection status
curl "http://localhost:8081/debug/mcp/status"

# View all discovered tools from MCP servers
curl "http://localhost:8081/debug/mcp/tools"

# Refresh tool discovery
curl "http://localhost:8081/debug/mcp/refresh"
```

**Expected Results:**
- Status endpoint should show connected MCP servers
- Tools endpoint should list dynamically discovered tools (get_portfolio, get_stock_quote, etc.)
- Each server should report multiple available tools

## Test AI Assistant (WebSocket)
In a third new terminal with the found JWT, run the following with the JWT_ST you had exported as an environment variable earlier
  ```bash
  wscat -c ws://localhost:8081/ws/stream -H "Authorization: Bearer $JWT_ST"
  ```

# Configuration Details

## Application Properties
If you have reason to change them from the defaults configured in this project, configure the `quarkus.langchain4j.azure-openai.***` Azure OpenAI properties in `application.properties`.

**Important**: The `AZURE_OPENAI_ENDPOINT` environment variable must include the full deployment path using the **regional endpoint format**:
`https://<region>.api.cognitive.microsoft.com/openai/deployments/<deployment-name>` (e.g., `https://eastus2.api.cognitive.microsoft.com/openai/deployments/gpt-4o`). 

**Note**: Azure has moved away from the custom subdomain format (`https://<service-name>.openai.azure.com`) to the regional endpoint format. Always use the regional endpoint that matches your `$AVZONE_OPENAI` variable. This path configuration allows the Quarkus LangChain4j extension to correctly construct the final API URL by appending `/chat/completions`.

## Maven Dependencies
NOTE: The project uses the Quarkus LangChain4j Azure OpenAI extension (already set in `pom.xml`):

```xml
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-azure-openai</artifactId>
</dependency>
```

# Troubleshooting

## Azure OpenAI Service Issues
- Verify that Azure OpenAI service is available in your chosen region
- Check that the GPT-4o model deployment is successful and running
- Ensure the API key is correctly set in the Kubernetes secret
- **Endpoint Format**: If you see "Access denied due to invalid subscription key or wrong API endpoint" errors, verify you're using the correct regional endpoint format: `https://<region>.api.cognitive.microsoft.com/openai/deployments/gpt-4o`

# Restart deployments to pick up new configuration
```bash
# Restart main application
kubectl rollout restart deployment/portfolioassistant -n stock-trader

# Restart MCP servers if needed
kubectl rollout restart deployment/mcp-portfolio-server -n stock-trader
kubectl rollout restart deployment/mcp-stockquote-server -n stock-trader
kubectl rollout restart deployment/mcp-tradehistory-server -n stock-trader
```

## MCP Server Issues
To troubleshoot full MCP server connectivity:

```bash
# Check MCP server pod status
kubectl get pods -n stock-trader | grep mcp

# Check MCP server logs
kubectl logs deployment/mcp-portfolio-server -n stock-trader
kubectl logs deployment/mcp-stockquote-server -n stock-trader  
kubectl logs deployment/mcp-tradehistory-server -n stock-trader

# Test MCP server health endpoints (requires port forwarding)
kubectl port-forward -n stock-trader svc/mcp-portfolio-server-service 8000:8000 &
curl http://localhost:8000/health

# Test full MCP protocol debug endpoints
kubectl port-forward pod/$ASSISTANT_POD_NAME -n stock-trader 8081:8080 &
curl http://localhost:8081/debug/mcp/status
curl http://localhost:8081/debug/mcp/tools
```

## Application Logs
To check application logs for Azure OpenAI connectivity issues:
```bash
kubectl logs deployment/portfolioassistant -n stock-trader -c portfolioassistant
```
