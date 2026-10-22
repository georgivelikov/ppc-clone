{{- define "cutpay.image" -}}
{{ .root.Values.image.registry }}{{ .name }}:{{ .root.Values.image.tag }}
{{- end -}}

{{- define "cutpay.labels" -}}
app.kubernetes.io/part-of: cutpay
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* Settings every Spring service gets: where Kafka is, and (for those with a database) which one. */}}
{{- define "cutpay.commonEnv" -}}
- name: SPRING_KAFKA_BOOTSTRAP_SERVERS
  value: {{ .root.Values.kafka.bootstrap | quote }}
- name: CUTPAY_LOG_DIR
  value: /tmp/logs
{{- if .svc.db }}
- name: SPRING_DATASOURCE_URL
  value: "jdbc:postgresql://{{ .root.Values.postgres.host }}:5432/{{ .svc.db }}"
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef: {name: {{ .root.Values.postgres.secret }}, key: username}
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef: {name: {{ .root.Values.postgres.secret }}, key: password}
{{- end }}
{{- if .svc.jwks }}
- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI
  value: "http://identity:8081/.well-known/jwks.json"
{{- end }}
{{- end -}}
