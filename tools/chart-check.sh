#!/usr/bin/env bash
# Valida o Helm chart do catálogo sem cluster (ADR 018): helm lint e os manifestos renderizados contra os schemas do
# Kubernetes mínimo do chart, pelo kubeconform, com os valores padrão, os de produção de exemplo e os do k3d.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CHART=infra/helm/catalog-inventory
HELM_IMAGE=alpine/helm:4.3.1@sha256:e3f8d5c462d75470fbb1fce79ad5c20063657096d1f98c0fcea869e2600e2e5e
KUBECONFORM_IMAGE=ghcr.io/yannh/kubeconform:v0.8.0@sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e
# Schemas dos CRDs (o ServiceMonitor do Prometheus Operator), num commit fixo do catálogo da Datree.
CRDS="https://raw.githubusercontent.com/datreeio/CRDs-catalog/c86448cbf1a840ca62d119259864863897c8748a"
KUBE_VERSION=$(sed -n 's/^kubeVersion: *">=\([0-9.]*\).*/\1/p' "$ROOT/$CHART/Chart.yaml")

helm() { docker run --rm -v "$ROOT:/work:ro" -w /work "$HELM_IMAGE" "$@"; }

echo "== lint com os valores padrão"
# WHY: os valores padrão deixam vazios, de propósito, banco, Kafka e JWT; os avisos disso só aparecem se o lint falhar.
if ! out=$(helm lint --quiet "$CHART" --set image.tag=check 2>&1); then
  echo "$out"
  exit 1
fi

for values in values-production.example.yaml ci/k3d-values.yaml; do
  echo "== $values"
  helm lint --quiet "$CHART" -f "$CHART/$values" --set image.tag=check
  helm template catalog-inventory "$CHART" -f "$CHART/$values" --set image.tag=check --kube-version "$KUBE_VERSION" |
    docker run --rm -i "$KUBECONFORM_IMAGE" -strict -summary -kubernetes-version "$KUBE_VERSION" \
      -schema-location default \
      -schema-location "$CRDS/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" -
done
