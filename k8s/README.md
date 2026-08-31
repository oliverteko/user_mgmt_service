# Kubernetes Manifests

> **Hinweis:** Diese statischen Manifeste sind als Referenz erhalten geblieben. Das tatsächliche Deployment läuft nicht mehr über diese Manifeste, sondern per GitOps über [ArgoCD](https://argo-cd.readthedocs.io/): Helm Chart und ArgoCD Application Manifest liegen im separaten [`user-mgmt-service-ops`](https://github.com/oliverteko/user-mgmt-service-ops) Repository, das ArgoCD kontinuierlich mit dem Cluster synchronisiert. Details zum Cluster-Bootstrap siehe `.github/workflows/argocd-bootstrap.yml` in diesem Repo.

Migration des `docker-compose.yml`-Setups (Next.js Frontend, Spring Boot Backend, PostgreSQL, Traefik) auf Kubernetes. Alle Ressourcen laufen im Namespace `user-mgmt`.

## Voraussetzungen

1. Ein laufender Kubernetes-Cluster (lokal: Minikube/Kind/Docker Desktop, oder Cloud: z.B. Azure AKS) und ein darauf zeigender `kubectl`-Kontext.
2. **Traefik** muss im Cluster installiert sein (nicht Teil dieser Manifeste), z.B. via Helm:
   ```
   helm repo add traefik https://traefik.github.io/charts
   helm install traefik traefik/traefik -n traefik --create-namespace
   ```
   Der `IngressClass`-Name muss `traefik` sein (Helm-Chart-Default). Falls abweichend, `ingressClassName` in `12-ingress-frontend.yaml` anpassen. Für die DOKS-CI (`.github/workflows/build-and-push.yml`) läuft dieser Schritt automatisch bei jedem Deploy (idempotent) — siehe Abschnitt "DigitalOcean" unten.
3. Docker Images für Backend und Frontend müssen in einer für den Cluster erreichbaren Registry liegen (hier: GHCR, siehe `.github/workflows/build-and-push.yml`). Standardmässig werden die Images öffentlich (`ghcr.io/oliverteko/user-mgmt-service-backend`/`-frontend`) erwartet — dazu nach dem ersten Push die Package-Sichtbarkeit in den GitHub-Package-Settings auf "Public" setzen. Alternativ (falls privat) einen Pull-Secret anlegen:
   ```
   kubectl create secret docker-registry ghcr-pull-secret -n user-mgmt \
     --docker-server=ghcr.io \
     --docker-username=<gh-username> \
     --docker-password=<PAT mit read:packages> \
     --docker-email=<email>
   ```
   und `imagePullSecrets: [{name: ghcr-pull-secret}]` in `06-backend-deployment.yaml` / `08-frontend-deployment.yaml` ergänzen.

## DigitalOcean (DOKS)

Ziel-Cluster ist DigitalOcean Kubernetes (DOKS). Zusätzlich zu den allgemeinen Voraussetzungen oben:

1. **doctl installieren und authentifizieren**: `doctl auth init` (benötigt einen DigitalOcean API Token).
2. **Kubeconfig laden**: `doctl kubernetes cluster kubeconfig save <cluster-name>`.
3. **PersistentVolumeClaim**: keine Anpassung nötig — DOKS setzt `do-block-storage` automatisch als Default-`StorageClass`, `k8s/03-postgres-pvc.yaml` funktioniert unverändert.
4. **Traefik**: Installation wie oben beschrieben (Helm). Der vom Chart erstellte `type: LoadBalancer`-Service löst automatisch die Provisionierung eines DigitalOcean Load Balancers mit öffentlicher IP aus.

Es ist **kein Hostname-Bootstrap nötig**: das Frontend proxied sämtliche Backend-Aufrufe serverseitig über eigene Next.js-API-Routes (`frontend/app/api/*/route.ts`), die den Backend-Service intern über `INTERNAL_API_URL` (`http://backend:8080`) erreichen. Der Browser spricht nie direkt mit dem Backend, daher muss die externe IP/Hostname vor dem Build nicht bekannt sein — `12-ingress-frontend.yaml` hat bewusst keinen `host` gesetzt und matched jeden eingehenden Host-Header. Das funktioniert unverändert, egal wie oft der Cluster (und damit die Load-Balancer-IP) neu erstellt wird.

### Deployment via GitOps (ArgoCD)

`.github/workflows/build-and-push.yml` baut und pusht nur noch die Images — das eigentliche Deployment übernimmt ArgoCD, das den Cluster kontinuierlich mit dem [`user-mgmt-service-ops`](https://github.com/oliverteko/user-mgmt-service-ops) Repository synchronisiert (siehe dortiges README).

Der Cluster-Bootstrap (Traefik + ArgoCD installieren, `app-secret` anlegen, ArgoCD Application anwenden) läuft über den separaten, manuell getriggerten Workflow `.github/workflows/argocd-bootstrap.yml`. Dafür einmalig nötig (unabhängig von der Anzahl Cluster-Neuerstellungen):

- GitHub Secret `DIGITALOCEAN_ACCESS_TOKEN` (DigitalOcean API Token mit Schreibrechten).
- GitHub Variable `DO_CLUSTER_NAME` (Name des DOKS-Clusters).
- GitHub Secrets `SPRING_DATASOURCE_PASSWORD` und `JWT_SECRET` (echte Werte für `app-secret` — dieselben Secrets, die auch der Azure-Deploy-Pfad (`deploy.yml`) nutzt; landen nie in Git, nur verschlüsselt in den GitHub-Repo-Secrets).

## Deployment

```bash
# 1. Namespace zuerst
kubectl apply -f k8s/00-namespace.yaml

# 2. Secret erstellen (NICHT aus 02-secret.example.yaml - das ist nur ein Template)
kubectl create secret generic app-secret -n user-mgmt \
  --from-literal=DB_USERNAME=postgres \
  --from-literal=DB_PASSWORD='<echtes-passwort>' \
  --from-literal=JWT_SECRET='<echtes-jwt-secret>'

# 3. Restliche Manifeste
kubectl apply -f k8s/01-configmap.yaml
kubectl apply -f k8s/03-postgres-pvc.yaml
kubectl apply -f k8s/04-postgres-deployment.yaml
kubectl apply -f k8s/05-postgres-service.yaml
kubectl apply -f k8s/06-backend-deployment.yaml
kubectl apply -f k8s/07-backend-service.yaml
kubectl apply -f k8s/08-frontend-deployment.yaml
kubectl apply -f k8s/09-frontend-service.yaml
kubectl apply -f k8s/12-ingress-frontend.yaml
```

(`02-secret.example.yaml` wird bewusst nicht mit `kubectl apply -f k8s/` als Wildcard ausgerollt — daher die einzelnen `apply`-Befehle statt `kubectl apply -f k8s/`.)

## Verifikation

```bash
kubectl get pods,svc,ingress -n user-mgmt
```
Alle drei Deployments (`postgres`, `backend`, `frontend`) sollten `Running` / `READY 1/1` zeigen.

**Datenbank nur über Service erreichbar:**
```bash
kubectl get svc postgres -n user-mgmt   # zeigt nur ClusterIP, kein NodePort/LoadBalancer
```

**Persistenz-Test:**
```bash
# User über die App registrieren, dann:
kubectl delete pod -l app=postgres -n user-mgmt
# nach Neustart des Pods prüfen, dass der User noch existiert:
kubectl exec -it deploy/postgres -n user-mgmt -- psql -U postgres -d postgres -c "select * from users;"
```

**Frontend über Ingress erreichen:**

- Minikube:
  ```bash
  minikube tunnel   # separates Terminal
  kubectl get svc -n traefik   # EXTERNAL-IP ablesen
  # in /etc/hosts eintragen: <EXTERNAL-IP>  user-mgmt.local
  ```
  Ohne Tunnel als Fallback:
  ```bash
  kubectl port-forward -n traefik svc/traefik 8000:80
  curl -H "Host: user-mgmt.local" http://localhost:8000/
  ```
- AKS: externe IP des Traefik-`LoadBalancer`-Service (`kubectl get svc -n traefik`) in `/etc/hosts` eintragen (oder echten DNS-Eintrag anlegen).
- DigitalOcean (DOKS): kein `/etc/hosts`-Eintrag und kein Bootstrap nötig — die Ingress-Regel hat keinen `host` gesetzt und matched jede externe IP direkt.

Danach im Browser auf `http://<ingress-host>/` (lokal: `http://user-mgmt.local/`, DigitalOcean: `http://<traefik-external-ip>/`) Signup → Login → Dashboard durchspielen.
