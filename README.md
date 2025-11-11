# Portfolio Assistant
### Stock Trader AI Helper with Azure OpenAI

Manage your portfolio with the help of AI. This application is part of the Stock Trader solution and provides insights and recommendations based on your stock portfolio using GPT-4 Turbo via the Azure OpenAI service.

This requires the Portfolio and Stock Quote microservices to be running. Additional microservices may be required as development continues.

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
  * `export KS_STOCKTRADER=`<*Your Kubernetes Service running Stocktrader*>
  * `export AZ_SUBSCRIPTION_ID=`<*Your Azure Subscription ID*>
  * `export OWNER_EMAIL=`<*Email Address of the Resources Owner*>
  * `export JWT_ST=`<*Stock Trader App JWT*>
    * To find this, go to your Stocktrader URL, sign in, and find the JWT in cookies (browser local storage), and save the JWT as this environment variable for ease of use

# Deployment on Azure

## Create Azure OpenAI Service
Create an Azure OpenAI service instance. Note that Azure OpenAI availability varies by region - check the [Azure OpenAI Service regions page](https://docs.microsoft.com/en-us/azure/cognitive-services/openai/concepts/regions) for current availability. Also note that Quarkus LangChain4j which is used by this project has hard-coded requirements (limits) for which ChatGPT API versions it can tolerate, so make sure you are using a compatible API/ChatGPT version. This project uses ChatGPT-4 @ turbo-2024-04-09 with API version 2023-05-15.

```bash
# Create the Azure OpenAI service
az cognitiveservices account create \
  --name portfolio-assistant-openai \
  --resource-group $RG_STOCKTRADER \
  --location $AVZONE_OPENAI \
  --kind OpenAI \
  --sku S0 \
  --tags owner=$OWNER_EMAIL created-by=$OWNER_EMAIL purpose="AI and Stock Trader work" solution=stocktrader-portfolio-assistant

# Deploy GPT-4 Turbo model
az cognitiveservices account deployment create \
  --name portfolio-assistant-openai \
  --resource-group $RG_STOCKTRADER \
  --deployment-name gpt-4 \
  --model-name gpt-4 \
  --model-version "turbo-2024-04-09" \
  --model-format OpenAI \
  --sku-capacity 10 \
  --sku-name "Standard"
```

## Create Kubernetes Secret for Azure OpenAI
Get the API key and create the Kubernetes secret:

```bash
# Get the API key
AZURE_OPENAI_API_KEY=$(az cognitiveservices account keys list \
  --name portfolio-assistant-openai \
  --resource-group $RG_STOCKTRADER \
  --query "key1" --output tsv)

# Create the Kubernetes secret with the full deployment endpoint
kubectl create secret generic azure-openai-secret \
  --namespace stock-trader \
  --from-literal=AZURE_OPENAI_API_KEY="$AZURE_OPENAI_API_KEY" \
  --from-literal=AZURE_OPENAI_ENDPOINT="https://portfolio-assistant-openai.openai.azure.com/openai/deployments/gpt-4"
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

## Deploy the image
```bash
kubectl apply -f azure-deployment.yaml -n stock-trader
```

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

In a third new terminal with the found JWT, run the following with the JWT_ST you had exported as an environment variable earlier
  ```bash
  wscat -c ws://localhost:8081/ws/stream -H "Authorization: Bearer $JWT_ST"
  ```

# Configuration Details

## Application Properties
If you have reason to change them from the defaults configured in this project, configure the `quarkus.langchain4j.azure-openai.***` Azure OpenAI properties in `application.properties`.

**Important**: The `AZURE_OPENAI_ENDPOINT` environment variable must include the full deployment path:
`https://<your-service-name>.openai.azure.com/openai/deployments/<deployment-name>`. This is already set in the commands above and should not need to be changed, but it is noted here in case other things are changed which result in this needing to be updated manually. This path configuration allows the Quarkus LangChain4j extension to correctly construct the final API URL by appending `/chat/completions`.

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
- Check that the GPT-4 model deployment is successful and running
- Ensure the API key is correctly set in the Kubernetes secret

## Application Logs
To check application logs for Azure OpenAI connectivity issues:
```bash
kubectl logs deployment/portfolioassistant -n stock-trader -c portfolioassistant
```
