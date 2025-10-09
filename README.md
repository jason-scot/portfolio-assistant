# Portfolio Assistant
### Stock Trader AI Helper

Manage your portfolio with the help of AI. This application is part of the Stock Trader solution and provides insights and recommendations based on your stock portfolio.

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
  * `export KS_STOCKTRADER=`<*Your Kubernetes Service running Stocktrader*>
  * `export AZ_SUBSCRIPTION_ID=`<*Your Azure Subscription ID*>
  * `export OWNER_EMAIL=`<*Email Address of the Resources Owner*>
  * `export JWT_ST=`<*Stock Trader App JWT*>
    * To find this, go to your Stocktrader URL, sign in, and find the JWT in cookies (browser local storage), and save the JWT as this environment variable for ease of use

# Deployment on Azure

## Create GPU NodePool
Update the Kubernetes cluster to use the already existing userassigned managed identity, rather than systemassigned managed identity which the resource was provisioned with
```
az aks update \
    --name $KS_STOCKTRADER \
    --resource-group $RG_STOCKTRADER \
    --enable-managed-identity \
    --assign-identity /subscriptions/$AZ_SUBSCRIPTION_ID/resourceGroups/$RG_STOCKTRADER/providers/Microsoft.ManagedIdentity/userAssignedIdentities/aks-managed-identity
```

Decide which GPU resource to use and confirm that there is enough of a resource quota on the Azure subscription to support it.
* By default, a subscription may have no allocation for any GPU resources, so the quota may (likely!) need to be increased if deploying something new.
  * To request increase, go to Quotas in Azure and find the resource you are looking to deploy to see its quota. You can request an increase there too if the quota is too low. Note that various resources require different numbers of processors (the one listed below requires a quota of exactly 4 processors per VM). If you receive an error saying that the quota could not be increased by this method in the console, you must raise a ticket (also in console) to increase the quota. Finally, it should succeed.
* The following is currently the least expensive AKS-compatible GPU VM available in East US that supports Kubernetes node pools (**Last updated: 2025-09-15**)
  * **VM Size: Standard_NC4as_T4_v3**
    * GPU: 1 × NVIDIA T4
    * vCPUs: 4
    * Memory: 28 GiB
    * Use Case: Entry-level GPU workloads, ML inference, graphics rendering
    * Supported in AKS: Yes 1
    * Available in East US: Yes 2
    * Estimated Cost: ~$0.60–$0.70/hour (on-demand); Spot pricing may be significantly lower 3

Once the GPU is chosen and the allocated quota is sufficient, deploy the GPU resource. This script as written will create the GPU resource described above. It will likely take several minutes to deploy.
```
az aks nodepool add \
  --cluster-name $KS_STOCKTRADER \
  --resource-group $RG_STOCKTRADER \
  --name gpupool \
  --node-vm-size Standard_NC4as_T4_v3 \
  --node-count 1 \
  --enable-cluster-autoscaler \
  --min-count 1 \
  --max-count 3 \
  --mode User \
  --node-taints sku=gpu:NoSchedule \
  --labels agentpool=gpupool
```

## Create StorageClass in the Kubernetes cluster
```
kubectl apply -f - <<EOF
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: azure-disk-ssd-standard-llm-sc
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: disk.csi.azure.com
volumeBindingMode: WaitForFirstConsumer
parameters:
  skuName: StandardSSD_LRS
  storageAccountType: StandardSSD_LRS
  kind: Managed
EOF
```

## Configure the GPU Nodepool for Kubernetes
The following is necessary for Kubernetes to be exposed to and able to use GPUs on Azure. In theory, Azure should set this up automatically, but it seems like in practice that this manual step has to be done.

Configure the nvidia-device-plugin settings, so the GPU nodepool can be used properly with Kubernetes
```
kubectl apply -f nvidia-device-plugin.yaml
```

## Deploy Ollama Helm Chart
```
helm repo add otwld https://helm.otwld.com/ &&
helm repo update &&
helm install ollama otwld/ollama --namespace ollama --create-namespace -f ollama-crd.yaml
```

Optional: Give it a minute to deploy, then run the commands that were output and go to the URL that was generated, in order to check on the status of the Ollama deployment. If there is an error running the port-forward command because the pod status is Pending, wait another minute or two and try again.

## Create the Azure Container Registry (ACR)
```
az acr create \
  --name portfolioassistantacr \
  --resource-group $RG_STOCKTRADER \
  --sku Standard \
  --location eastus \
  --tags owner=$OWNER_EMAIL purpose="GH Actions Build" solution=stocktrader-portfolio-assistant
```

## Build the app image and push it to the registry
```
mvn clean install
```

```
az acr build \
  --registry portfolioassistantacr \
  --image ibmstocktrader/portfolioassistant:latest \
  --file src/main/docker/Dockerfile.jvm \
  .
```

NOTE: If not already done, update `azure-deployment.yaml` file to use the image repo you want to use; default value is: `portfolioassistantacr.azurecr.io/ibmstocktrader/portfolioassistant:latest`

## Create an ImagePullSecret
First, enable admin on the ACR
```
az acr update -n portfolioassistantacr --admin-enabled true
```

Then, should be able to do this with the following one-liner
```
kubectl create secret docker-registry acr-auth --namespace stock-trader --docker-server=portfolioassistantacr.azurecr.io --docker-username=$(az acr credential show --name portfolioassistantacr --query 'username' -o tsv) --docker-password=$(az acr credential show --name portfolioassistantacr --query 'passwords[0].value' -o tsv)
```
### BUT
If the one-liner does not work, then try as follows
  > Get the username and first password (passwords[0].value) to the ACR
  > ```
  > az acr credential show --name portfolioassistantacr
  > ```
  > Create the Kubernetes secret using those values. This will create a secret named acr-auth, which the azure-deployment.yaml file should know about in spec.template.spec
  > ```
  > kubectl create secret docker-registry acr-auth \
  >   --namespace stock-trader \
  >   --docker-server=portfolioassistantacr.azurecr.io \
  >   --docker-username=<username> \
  >   --docker-password=<password>
  > ```

## Deploy the image
```
kubectl apply -f azure-deployment.yaml -n stock-trader
```

## Set up port forwarding
Get the pods
```
kubectl get po -n stock-trader
```
In a new terminal, create an environment variable with the name of the pod running the portfolio-assistant service with `export ASSISTANT_POD_NAME=`<*Pod Name*>

In a second new terminal, run
  ```
  kubectl port-forward pod/$ASSISTANT_POD_NAME -n stock-trader 8081:8080
  ```

In a third new terminal with the found JWT, run the following with the JWT_ST you had exported as an environment variable earlier
  ```
  wscat -c ws://localhost:8081/ws/stream -H "Authorization: Bearer $JWT_ST"
  ```

# Misc.
To access the GPU nodepool via a debug pod for ssh debug access, run
```
kubectl debug node/$NAME_GPUPOOL_NODE -it --image=mcr.microsoft.com/aks/fundamental/base-ubuntu:v0.0.11
```

Once in a debug pod, to check the extension logs
```
cat /host/var/log/azure/nvidia-vmext-status
```

To delete the GPU nodepool, e.g. to save resources when not in use, run this command
```
az aks nodepool delete \
  --resource-group $RG_STOCKTRADER \
  --cluster-name $KS_STOCKTRADER \
  --name gpupool
```

To abort a long-running Azure operation in case of an issue, run
```
az aks operation-abort --name $KS_STOCKTRADER --resource-group $RG_STOCKTRADER
```

To export the Node Resource Group of the AKS cluster, for easier use
```
export NODE_RG=$(az aks show \
  --resource-group $RG_STOCKTRADER \
  --name $KS_STOCKTRADER \
  --query nodeResourceGroup \
  -o tsv)
```

To export the VMSS Name of the GPU nodepool as an environment variable, for easier use
```
export VMSSNAME_GPUPOOL=$(az vmss list \
  --resource-group $NODE_RG \
  --query "[?contains(name, 'gpupool')].name" \
  -o tsv)
```

To export the name of the Kubernetes node that is now running on the GPU nodepool, for easier use
```
export NAME_GPUPOOL_NODE=$(az vmss list-instances --resource-group $NODE_RG --name $VMSSNAME_GPUPOOL --query "[0].osProfile.computerName" -o tsv)
```