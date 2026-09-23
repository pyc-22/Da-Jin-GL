param(
  [string]$ComposeFile = "$PSScriptRoot/../../docker-compose.yml",
  [string]$Database = "dajin",
  [string]$User = "dajin",
  [string]$Password = "dajin"
)
$ErrorActionPreference = 'Stop'
docker compose -f $ComposeFile up -d mysql redis
docker compose -f $ComposeFile exec -T mysql mysql --user=$User --password=$Password --database=$Database -e "SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = '$Database'; SELECT COUNT(*) AS store_count FROM sys_store; SELECT COUNT(*) AS config_count FROM sys_config; SELECT config_key,config_value FROM sys_config WHERE config_key IN ('discount_threshold','recycle_approval_limit');"
