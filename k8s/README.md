# Kubernetes Manifests

Migration des `docker-compose.yml`-Setups (Next.js Frontend, Spring Boot Backend, PostgreSQL, Traefik) auf Kubernetes. Alle Ressourcen laufen im Namespace `user-mgmt`.

## Voraussetzungen

1. Ein laufender Kubernetes-Cluster (lokal: Minikube/Kind/Docker Desktop, oder Cloud: z.B. Azure AKS) und ein darauf zeigender `kubectl`-Kontext.
2. **Traefik inkl. CRDs** muss im Cluster installiert sein (nicht Teil dieser Manifeste), z.B. via Helm:
   ```
   helm repo add traefik https://traefik.github.io/charts
   helm install traefik traefik/traefik -n traefik --create-namespace
   ```
   Der `IngressClass`-Name muss `traefik` sein (Helm-Chart-Default). Falls abweichend, `ingressClassName` in `11-ingress-backend.yaml`/`12-ingress-frontend.yaml` anpassen.
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

### Hostname-Bootstrap (Henne-Ei-Problem)

`NEXT_PUBLIC_API_URL` wird beim Frontend-Image-Build fest eingebacken (siehe "Bekannte Einschränkung" unten) und muss zum Ingress-Host passen. Der verwendete Hostname ist `<load-balancer-ip>.sslip.io` (löst automatisch auf die IP auf, kein eigener Domainname nötig) — die IP ist aber erst bekannt, **nachdem** der Traefik-`LoadBalancer`-Service existiert. Deshalb einmaliger Bootstrap in zwei Schritten:

1. **Bootstrap-Deploy**: Manifeste mit dem Platzhalter-Host `REPLACE-WITH-LB-IP.sslip.io` (Default in den committeten Dateien) anwenden, dann:
   ```bash
   kubectl get svc -n traefik
   ```
   liefert die externe IP.
2. **Host fixieren**: in `k8s/11-ingress-backend.yaml`, `k8s/12-ingress-frontend.yaml` und `.github/workflows/build-and-push.yml` (`NEXT_PUBLIC_API_URL`) `REPLACE-WITH-LB-IP` durch die echte IP ersetzen, committen. Der CI-Workflow baut das Frontend-Image danach mit dem korrekten Host neu und deployt automatisch.

**Wichtig**: Solange der Traefik-`LoadBalancer`-Service nicht gelöscht/neu erstellt wird, bleibt die IP (und damit der sslip.io-Hostname) stabil. Wird der Service doch neu erstellt, ändert sich die IP und Schritt 2 muss wiederholt werden.

### Automatisches Deployment via CI

`.github/workflows/build-and-push.yml` enthält einen `deploy`-Job, der nach jedem Image-Build automatisch auf den DOKS-Cluster deployt (`kubectl apply` + `kubectl set image` + `kubectl rollout status`). Dafür nötig:

- GitHub Secret `DIGITALOCEAN_ACCESS_TOKEN` (DigitalOcean API Token mit Schreibrechten).
- GitHub Variable `DO_CLUSTER_NAME` (Name des DOKS-Clusters).
- **`app-secret` muss vor dem ersten automatischen Deploy einmalig manuell im Cluster existieren** (siehe Abschnitt "Deployment" unten) — der CI-Job legt es bewusst nicht an, damit keine echten Secret-Werte durch CI-Konfiguration laufen müssen.

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
kubectl apply -f k8s/10-middleware.yaml
kubectl apply -f k8s/11-ingress-backend.yaml
kubectl apply -f k8s/12-ingress-frontend.yaml
```

(`02-secret.example.yaml` wird bewusst nicht mit `kubectl apply -f k8s/` als Wildcard ausgerollt — daher die einzelnen `apply`-Befehle statt `kubectl apply -f k8s/`.)

## Verifikation

```bash
kubectl get pods,svc,ingress,middleware -n user-mgmt
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
- DigitalOcean (DOKS): kein `/etc/hosts`-Eintrag nötig — der Hostname ist `<load-balancer-ip>.sslip.io` und damit von jedem Rechner mit Internetzugang aus direkt auflösbar. Siehe Abschnitt "DigitalOcean" oben für den einmaligen Bootstrap, mit dem die IP ermittelt und der Hostname fixiert wird.

Danach im Browser auf `http://<ingress-host>/` (lokal: `http://user-mgmt.local/`, DigitalOcean: `http://<load-balancer-ip>.sslip.io/`) Signup → Login → Dashboard durchspielen.

## Bekannte Einschränkung: `NEXT_PUBLIC_API_URL`

Diese Variable wird beim Frontend-Image-Build **fest in das JS-Bundle eingebacken** (siehe `frontend/Dockerfile`) und kann zur Laufzeit nicht über ConfigMap/Env-Var geändert werden. Sie ist deshalb bewusst nicht Teil der ConfigMap, sondern wird als Docker-Build-Arg in der CI gesetzt (siehe `NEXT_PUBLIC_API_URL` in `.github/workflows/build-and-push.yml`) und muss exakt zum Ingress-Host passen. Ändert sich der Ingress-Hostname, muss das Frontend-Image neu gebaut werden.
