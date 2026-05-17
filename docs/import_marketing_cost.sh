#!/usr/bin/env bash
#
# import_marketing_cost.sh
# 把 marketing_cost.sql (1.2GB) 导入到目标 MySQL。
# 行为：
#   - SQL 文件里有的表：先 DROP 再用最新版重建（覆盖）
#   - SQL 文件里没有的表：完全不动
#   - 自动检测目标库现有表，列出"将要被替换 / 新建 / 保留不动"的清单
#   - 导入前自动备份目标库中将要被覆盖的表
#

set -euo pipefail

# ================== 配置区（按需修改）==================
SQL_FILE="${SQL_FILE:-/Users/xiexicheng/Documents/sales_cost/marketing-cost-api/docs/marketing_cost.sql}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-}"           # 留空会交互式询问
DB_NAME="${DB_NAME:-marketing_cost}"

DO_BACKUP="${DO_BACKUP:-yes}"     # yes / no
BACKUP_DIR="${BACKUP_DIR:-$HOME/mysql_backup}"
# =====================================================

log() { printf '\033[36m[%(%H:%M:%S)T]\033[0m %s\n' -1 "$*"; }
err() { printf '\033[31m[ERR]\033[0m %s\n' "$*" >&2; exit 1; }

# 1) 基础检查
[[ -f "$SQL_FILE" ]] || err "SQL 文件不存在：$SQL_FILE"
command -v mysql >/dev/null   || err "本机未安装 mysql 客户端"
command -v mysqldump >/dev/null || err "本机未安装 mysqldump"

if [[ -z "$DB_PASS" ]]; then
  read -rsp "请输入 MySQL 密码（${DB_USER}@${DB_HOST}）: " DB_PASS
  echo
fi

MY_AUTH=(-h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS")

# 2) 测试连接
log "测试 MySQL 连接 ..."
if ! mysql "${MY_AUTH[@]}" -e "SELECT 1;" >/dev/null 2>&1; then
  err "MySQL 连接失败，请确认 host / port / 用户名 / 密码"
fi
log "✅ MySQL 连接 OK"

# 3) 确认/创建数据库
log "确认数据库 \`$DB_NAME\` 存在 ..."
mysql "${MY_AUTH[@]}" -e \
  "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
log "✅ 数据库就绪"

# 4) 解析 SQL 文件中的所有表
log "解析 SQL 文件中的表名 ..."
SQL_TABLES=$(grep -oE '^DROP TABLE IF EXISTS `[^`]+`' "$SQL_FILE" \
             | sed -E 's/^DROP TABLE IF EXISTS `([^`]+)`$/\1/' \
             | sort -u)
SQL_TABLE_COUNT=$(echo "$SQL_TABLES" | wc -l | tr -d ' ')
log "SQL 文件包含 $SQL_TABLE_COUNT 张表"

# 5) 列出目标库现有表
log "查询目标库现有表 ..."
EXISTING=$(mysql "${MY_AUTH[@]}" -N -B -e \
  "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB_NAME';" \
  | sort -u)
EXISTING_COUNT=$(echo "$EXISTING" | grep -c . || true)
log "目标库现有 $EXISTING_COUNT 张表"

# 6) 计算差异
TO_REPLACE=$(comm -12 <(echo "$SQL_TABLES") <(echo "$EXISTING"))
TO_CREATE=$(comm -23 <(echo "$SQL_TABLES") <(echo "$EXISTING"))
KEEP_AS_IS=$(comm -13 <(echo "$SQL_TABLES") <(echo "$EXISTING"))

REPLACE_COUNT=$(echo "$TO_REPLACE" | grep -c . || true)
CREATE_COUNT=$(echo "$TO_CREATE" | grep -c . || true)
KEEP_COUNT=$(echo "$KEEP_AS_IS" | grep -c . || true)

echo
echo "================ 导入计划 ================"
echo "  目标库：$DB_NAME @ $DB_HOST:$DB_PORT"
echo "  SQL 文件：$SQL_FILE ($(du -h "$SQL_FILE" | awk '{print $1}'))"
echo "  ----------------------------------------"
printf "  ⚠️  将被【覆盖】的旧表：%d 张\n" "$REPLACE_COUNT"
printf "  ✨ 将被【新建】的表  ：%d 张\n" "$CREATE_COUNT"
printf "  ✅ 保持【不动】的表  ：%d 张\n" "$KEEP_COUNT"
echo "=========================================="
echo

if [[ "$REPLACE_COUNT" -gt 0 ]]; then
  echo "—— 将被覆盖的表（旧数据会被替换为 SQL 文件里的最新版）——"
  echo "$TO_REPLACE" | sed 's/^/    - /'
  echo
fi

read -rp "继续导入？(yes / no): " ANS
[[ "$ANS" == "yes" ]] || { log "已取消"; exit 0; }

# 7) 备份将要被覆盖的表
if [[ "$DO_BACKUP" == "yes" && "$REPLACE_COUNT" -gt 0 ]]; then
  mkdir -p "$BACKUP_DIR"
  STAMP=$(date +%Y%m%d_%H%M%S)
  BACKUP_FILE="$BACKUP_DIR/${DB_NAME}_pre_import_${STAMP}.sql.gz"

  log "备份将被覆盖的 $REPLACE_COUNT 张表 → $BACKUP_FILE"
  # shellcheck disable=SC2086
  mysqldump "${MY_AUTH[@]}" \
      --single-transaction --quick --skip-lock-tables \
      --set-gtid-purged=OFF --column-statistics=0 \
      "$DB_NAME" $TO_REPLACE 2>/dev/null \
    | gzip > "$BACKUP_FILE" \
    || err "备份失败（如不需要备份，DO_BACKUP=no 重试）"
  BACKUP_SIZE=$(du -h "$BACKUP_FILE" | awk '{print $1}')
  log "✅ 备份完成（$BACKUP_SIZE）"
else
  log "ℹ️  跳过备份（DO_BACKUP=$DO_BACKUP 或没有要覆盖的表）"
fi

# 8) 执行导入
log "开始导入 SQL 文件，请耐心等待 ..."
START_TS=$(date +%s)

# 用 pv 显示进度（如果安装了），否则普通管道
if command -v pv >/dev/null; then
  pv "$SQL_FILE" | mysql "${MY_AUTH[@]}" \
    --default-character-set=utf8mb4 \
    --max-allowed-packet=512M \
    "$DB_NAME"
else
  log "（未安装 pv，无进度条；可 brew install pv 后获得进度显示）"
  mysql "${MY_AUTH[@]}" \
    --default-character-set=utf8mb4 \
    --max-allowed-packet=512M \
    "$DB_NAME" < "$SQL_FILE"
fi

END_TS=$(date +%s)
log "✅ 导入完成（耗时 $((END_TS - START_TS)) 秒）"

# 9) 校验：对比表数量、行数抽样
log "导入后校验 ..."
NEW_TABLE_COUNT=$(mysql "${MY_AUTH[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB_NAME';")
log "目标库当前表数：$NEW_TABLE_COUNT"

echo
echo "—— SQL 文件中表的行数（导入后实际值）——"
mysql "${MY_AUTH[@]}" -N -B -e \
"SELECT TABLE_NAME, TABLE_ROWS
 FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='$DB_NAME'
   AND TABLE_NAME IN ($(echo "$SQL_TABLES" | sed "s/.*/'&'/" | paste -sd, -))
 ORDER BY TABLE_NAME;" \
 | awk -F'\t' '{ printf "  %-50s %s\n", $1, $2 }'

echo
log "🎉 全部完成"
[[ -n "${BACKUP_FILE:-}" ]] && log "如需回滚，备份文件：$BACKUP_FILE"
