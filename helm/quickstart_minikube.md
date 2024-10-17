# Prerequisites

Build dependencies

```bash
helm dependency build
```

Install minikube and start the minikube cluster

```bash
minikube start
```

Enable the ingress addon

```bash
minikube addons enable ingress
```

[Alllow snippets](https://kubernetes.github.io/ingress-nginx/examples/customization/custom-headers/) on the ingress controller

```bash
kubectl apply -f ingress-configmap.yaml
```


# Install the helm chart


## Secrets

Update `POSTGRES_PASSWORD`, `rabbitmq-password`, and `SOLR_ADMIN_PASSWORD` to some cryptographically secure values.

```bash
RELEASE_NAME=my-release envsubst < ./admin/secrets.yaml | kubectl apply -n default -f -
```


## Install the certificate

```bash
rm -f DataONETestIntCA.pem
wget -q https://raw.githubusercontent.com/DataONEorg/ca/main/DataONETestIntCA/certs/DataONETestIntCA.pem
```

```bash
kubectl create configmap my-release-d1-certs-public \
        --from-file=DataONETestIntCA.pem=DataONETestIntCA.pem
```


## Install the token

Get a short-lived token from KCB UI <https://knb.ecoinformatics.org/profile/http://orcid.org/0000-0002-2661-8912>

and save to `urn_node_mydataone.jwt`

```bash
kubectl create secret generic my-release-indexer-token \
        --from-file=DataONEauthToken=urn_node_mydataone.jwt
```


## Create a persistent volume

In `admin/pv-hostpath.yaml`, update `spec.hostPath.path` to a path on your local machine, then create persistent volume claims against that volume.

```bash
RELEASE_NAME=my-release envsubst < ./admin/pv-hostpath.yaml | kubectl apply -n default -f -
kubectl apply -n default -f ./admin/pvclaim.yaml
```


## Install the helm chart

```bash
./helm-upstall.sh my-release default -f examples/values-dev-minikube-example.yaml
```
