{{- define "catalog-inventory.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "catalog-inventory.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "catalog-inventory.selectorLabels" -}}
app.kubernetes.io/name: {{ include "catalog-inventory.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "catalog-inventory.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "catalog-inventory.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: marketplace
{{- end }}

{{/*
Nome, rótulos e seletor de um componente da release (api, worker ou metrics).
Recebem dict com "root" (contexto raiz) e "component".
WHY: o component fica fora de catalog-inventory.labels porque o Job de tópicos já define o próprio.
*/}}
{{- define "catalog-inventory.componentFullname" -}}
{{- printf "%s-%s" (include "catalog-inventory.fullname" .root) .component | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "catalog-inventory.componentSelectorLabels" -}}
{{ include "catalog-inventory.selectorLabels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "catalog-inventory.componentLabels" -}}
{{ include "catalog-inventory.labels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "catalog-inventory.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "catalog-inventory.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "catalog-inventory.image" -}}
{{- $tag := required "image.tag é obrigatório: informe a tag imutável da imagem nativa" .Values.image.tag | toString }}
{{- if eq $tag "latest" }}
{{- fail "image.tag não aceita \"latest\": use uma tag imutável" }}
{{- end }}
{{- if .Values.image.digest }}
{{- printf "%s:%s@%s" .Values.image.repository $tag .Values.image.digest }}
{{- else }}
{{- printf "%s:%s" .Values.image.repository $tag }}
{{- end }}
{{- end }}

{{/*
Tópicos com os padrões de topicDefaults aplicados, em ordem alfabética da chave, como lista JSON.
*/}}
{{- define "catalog-inventory.topics" -}}
{{- $defaults := .Values.topicDefaults }}
{{- $result := list }}
{{- range $key := keys .Values.topics | sortAlpha }}
{{- $topic := get $.Values.topics $key }}
{{- $create := true }}
{{- if hasKey $topic "create" }}
{{- $create = $topic.create }}
{{- end }}
{{- $config := merge (deepCopy (default (dict) $topic.config)) (default (dict) $defaults.config) }}
{{- $result = append $result (dict
      "key" $key
      "name" (required (printf "topics.%s.name é obrigatório" $key) $topic.name)
      "create" $create
      "partitions" (default $defaults.partitions $topic.partitions)
      "replicationFactor" (default $defaults.replicationFactor $topic.replicationFactor)
      "config" $config) }}
{{- end }}
{{- toJson $result }}
{{- end }}

{{/*
Deployment de um papel do serviço (api ou worker): mesma imagem e mesma configuração, com o papel escolhido pelo
perfil do Quarkus. Recebe dict com "root" (contexto raiz) e "role"; os valores do papel vêm de .Values.<role>.
*/}}
{{- define "catalog-inventory.deployment" -}}
{{- $root := .root }}
{{- $role := .role }}
{{- $values := index $root.Values $role }}
{{- $component := dict "root" $root "component" $role }}
{{- $hpa := default (dict) $values.hpa }}
{{- $annotations := merge (dict) (default (dict) $values.podAnnotations) (default (dict) $root.Values.podAnnotations) }}
{{- $_ := set $annotations "checksum/config" (include (print $root.Template.BasePath "/configmap.yaml") $root | sha256sum) }}
{{- if $root.Values.metrics.scrapeAnnotations }}
{{- $_ := set $annotations "prometheus.io/scrape" "true" }}
{{- $_ := set $annotations "prometheus.io/path" "/q/metrics" }}
{{- $_ := set $annotations "prometheus.io/port" "9000" }}
{{- end }}
{{- $roleEnv := default (list) $values.extraEnv }}
{{- $roleEnvNames := list }}
{{- range $roleEnv }}
{{- $roleEnvNames = append $roleEnvNames .name }}
{{- end }}
{{- $extraEnv := list }}
{{- range default (list) $root.Values.extraEnv }}
{{- if not (has .name $roleEnvNames) }}
{{- $extraEnv = append $extraEnv . }}
{{- end }}
{{- end }}
{{- $extraEnv = concat $extraEnv $roleEnv }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "catalog-inventory.componentFullname" $component }}
  labels:
    {{- include "catalog-inventory.componentLabels" $component | nindent 4 }}
spec:
  {{- if not $hpa.enabled }}
  replicas: {{ $values.replicaCount }}
  {{- end }}
  revisionHistoryLimit: 5
  strategy:
    {{- toYaml $root.Values.strategy | nindent 4 }}
  selector:
    matchLabels:
      {{- include "catalog-inventory.componentSelectorLabels" $component | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "catalog-inventory.componentSelectorLabels" $component | nindent 8 }}
        {{- with $root.Values.podLabels }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      annotations:
        {{- toYaml $annotations | nindent 8 }}
    spec:
      serviceAccountName: {{ include "catalog-inventory.serviceAccountName" $root }}
      automountServiceAccountToken: false
      # WHY: o Quarkus repassa toda variável KAFKA_* ao cliente Kafka; os service links de um Service "kafka" no
      # namespace (KAFKA_PORT, KAFKA_SERVICE_HOST) virariam propriedades espúrias.
      enableServiceLinks: false
      {{- with $root.Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      securityContext:
        {{- toYaml $root.Values.podSecurityContext | nindent 8 }}
      terminationGracePeriodSeconds: {{ $root.Values.terminationGracePeriodSeconds }}
      containers:
        - name: catalog-inventory
          image: {{ include "catalog-inventory.image" $root | quote }}
          imagePullPolicy: {{ $root.Values.image.pullPolicy }}
          ports:
            {{- if eq $role "api" }}
            - name: http
              containerPort: 8080
              protocol: TCP
            {{- end }}
            # WHY: saúde e métricas nos dois papéis; é a única porta do worker, que roda com quarkus.http.host-enabled=false.
            - name: management
              containerPort: 9000
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "catalog-inventory.fullname" $root }}
            {{- with $root.Values.kafka.clientSecret }}
            - secretRef:
                name: {{ . }}
            {{- end }}
            {{- with $root.Values.extraEnvFrom }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          env:
            # O perfil só liga e desliga componentes; a configuração é a mesma nos dois papéis.
            - name: QUARKUS_PROFILE
              value: {{ printf "prod,%s" $role | quote }}
            - name: QUARKUS_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: {{ required "database.existingSecret é obrigatório: Secret com as credenciais do banco" $root.Values.database.existingSecret }}
                  key: {{ $root.Values.database.usernameKey }}
            - name: QUARKUS_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ $root.Values.database.existingSecret }}
                  key: {{ $root.Values.database.passwordKey }}
            {{- $migratorSecret := $root.Values.database.migrator.existingSecret | default $root.Values.database.existingSecret }}
            - name: QUARKUS_FLYWAY_USERNAME
              valueFrom:
                secretKeyRef:
                  name: {{ $migratorSecret }}
                  key: {{ required "database.migrator.usernameKey é obrigatório: chave do usuário dono do schema" $root.Values.database.migrator.usernameKey }}
            - name: QUARKUS_FLYWAY_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ $migratorSecret }}
                  key: {{ required "database.migrator.passwordKey é obrigatório: chave da senha do dono do schema" $root.Values.database.migrator.passwordKey }}
            {{- if $root.Values.cache.enabled }}
            - name: QUARKUS_REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ required "cache.existingSecret é obrigatório com cache.enabled: Secret com a senha do Redis" $root.Values.cache.existingSecret }}
                  key: {{ $root.Values.cache.passwordKey }}
            {{- end }}
            {{- with $extraEnv }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          startupProbe:
            {{- toYaml $root.Values.startupProbe | nindent 12 }}
          readinessProbe:
            {{- toYaml $root.Values.readinessProbe | nindent 12 }}
          livenessProbe:
            {{- toYaml $root.Values.livenessProbe | nindent 12 }}
          {{- if gt (int $root.Values.preStopSleepSeconds) 0 }}
          lifecycle:
            preStop:
              sleep:
                seconds: {{ $root.Values.preStopSleepSeconds }}
          {{- end }}
          resources:
            {{- toYaml $values.resources | nindent 12 }}
          securityContext:
            {{- toYaml $root.Values.securityContext | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir:
            sizeLimit: {{ $root.Values.tmpVolume.sizeLimit }}
      {{- if $root.Values.topologySpread.enabled }}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: {{ $root.Values.topologySpread.topologyKey }}
          whenUnsatisfiable: {{ $root.Values.topologySpread.whenUnsatisfiable }}
          labelSelector:
            matchLabels:
              {{- include "catalog-inventory.componentSelectorLabels" $component | nindent 14 }}
      {{- end }}
      {{- with $values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with $values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end }}
