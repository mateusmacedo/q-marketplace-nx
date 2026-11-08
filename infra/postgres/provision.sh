#!/bin/sh
# Provisiona o PostgreSQL compartilhado (ADR 019): no banco de PGDATABASE, um schema por contexto recebido como
# argumento, dono do role <contexto>_migrator (DDL, usado pelo Flyway), e o role <contexto> de runtime, só com DML.
# Com POSTGRES_EXPORTER_PASSWORD, também o role exporter (pg_monitor). Conecta como superusuário pelas variáveis PG*;
# as senhas vêm de <CONTEXTO>_DB_PASSWORD e <CONTEXTO>_DB_MIGRATOR_PASSWORD. Uso: provision.sh catalog_inventory ...
# WHY: idempotente e reaplica senhas e grants a cada execução, para que trocar uma senha com o volume mantido funcione.
set -eu

sql() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }
fail() { echo "provision.sh: $*" >&2; exit 1; }

# role <nome> <variável com a senha>
role() {
  [ -n "$(printenv "$2" || true)" ] || fail "defina $2"
  sql -v name="$1" -v pass_var="$2" <<'SQL'
\getenv pass :pass_var
-- WHY: a senha vai no texto do ALTER ROLE; nem o log de instruções nem o de erro podem registrá-la.
SET log_statement = none;
SET log_min_error_statement = panic;
SELECT format('CREATE ROLE %I', :'name') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'name') \gexec
ALTER ROLE :"name" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'pass';
GRANT CONNECT ON DATABASE :"DBNAME" TO :"name";
SQL
}

# context <schema>: o runtime tem o nome do schema, e o dono, o sufixo _migrator.
context() {
  case $1 in '' | [!a-z]* | *[!a-z0-9_]*) fail "nome de contexto inválido: '$1'" ;; esac
  env_prefix=$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')
  role "$1_migrator" "${env_prefix}_DB_MIGRATOR_PASSWORD"
  role "$1" "${env_prefix}_DB_PASSWORD"
  sql -v schema="$1" -v migrator="$1_migrator" -v runtime="$1" <<'SQL'
SET client_min_messages = warning;
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"migrator";
ALTER SCHEMA :"schema" OWNER TO :"migrator";
REVOKE ALL ON SCHEMA :"schema" FROM PUBLIC;
GRANT USAGE ON SCHEMA :"schema" TO :"runtime";
-- WHY: as tabelas nascem nas migrations do migrator; os default privileges dão DML ao runtime em cada uma.
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA :"schema" GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA :"schema" GRANT USAGE, SELECT ON SEQUENCES TO :"runtime";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA :"schema" TO :"runtime";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA :"schema" TO :"runtime";
ALTER ROLE :"migrator" IN DATABASE :"DBNAME" SET search_path = :"schema";
ALTER ROLE :"runtime" IN DATABASE :"DBNAME" SET search_path = :"schema";
SQL
  echo "contexto $1: schema $1, $1_migrator (dono, DDL) e $1 (só DML) prontos"
}

[ "$#" -gt 0 ] || fail "informe ao menos um contexto"

# WHY: só os roles provisionados conectam, e ninguém cria objetos fora do schema do próprio contexto.
sql <<'SQL'
REVOKE ALL ON DATABASE :"DBNAME" FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL

if [ -n "${POSTGRES_EXPORTER_PASSWORD:-}" ]; then
  role exporter POSTGRES_EXPORTER_PASSWORD
  sql <<'SQL'
SELECT 'GRANT pg_monitor TO exporter' WHERE NOT pg_has_role('exporter', 'pg_monitor', 'MEMBER') \gexec
SQL
  echo "role exporter (pg_monitor) pronto"
fi

for name in "$@"; do context "$name"; done
