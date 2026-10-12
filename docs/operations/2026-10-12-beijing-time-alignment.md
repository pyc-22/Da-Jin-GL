# 2026-10-12 生产时间口径统一记录

## 目标与边界

- 后端 JVM、MySQL、管理端 Web、移动端 Web 容器统一使用 `Asia/Shanghai`；MySQL 会话默认时区为 `+08:00`。
- 浏览器端继续原样格式化 API 返回的时间，不在前端额外加减时区。
- 历史修正只触及后端生成的 `DATETIME` 时间列，每个非空值增加 8 小时；不更新金额、库存、状态或其它业务字段。
- 未发现 `TIMESTAMP` 列。日期类型列和业务计划/快照时间不做平移。

## 历史数据列清单

### 同时校正 `create_time` 与 `update_time`

`approval`、`commission_rule`、`goods`、`goods_category`、`goods_piece`、`member`、`old_material`、`old_material_type`、`print_template`、`process_item`、`processing_category`、`processing_commission`、`processing_item`、`processing_order`、`processing_order_item`、`processing_price_tier`、`recycle_order`、`sales_order`、`stock_check`、`stock_inbound`、`stock_supplier`、`sys_config`、`sys_role`、`sys_store`、`sys_user`、`trade_in`、`visit_task`。

### 仅校正 `create_time`

`commission_record`、`finance_record`、`gold_price`、`gold_price_change_log`、`member_balance_record`、`operation_log`、`pay_channel`、`print_job`、`processing_payment`、`processing_payment_component`、`sales_order_item`、`stock_in`、`stock_inbound_item`、`stock_out`。

### 其它服务端事件时间列

- `approval.approve_time`
- `gold_market_quote.update_time`
- `member_consume.consume_time`
- `print_job.printed_time`、`print_job.ignored_time`
- `processing_commission.paid_at`
- `processing_order.loss_time`、`processing_order.handover_time`、`processing_order.completed_time`、`processing_order.picked_up_time`
- `sales_order.paid_time`
- `sys_role_permission.created_at`、`sys_user_permission.created_at`

对应一次性 SQL：`db/migrations/20261012_migrate_server_datetimes_utc_to_shanghai.sql`。脚本仅在 MySQL 系统时区为 UTC、会话时区为 SYSTEM 且迁移 marker 不存在时执行；完成后写入 `sys_config` marker，重复运行不再平移。

### 明确排除

- 外部行情时间：`gold_market_quote.fetched_at`。
- 业务计划/快照：`visit_task.plan_time`、`visit_task.purchase_time_snapshot`。其中 `purchase_time_snapshot` 历史上由业务快照逻辑写入，按需求保留原值。
- `visit_task.call_started_at` 有服务端 `NOW()` 与客户端时间回传两种写入来源，属于混合口径，未纳入整列平移。
- `DATE` 列：`gold_price.date`、`member.birthday`、`processing_order.pickup_date`、`sys_user.entry_date`。
- 行情 `quote_time` 是外部字符串，不属于待校正 `DATETIME` 列。

## 配置

- `docker-compose.yml`、`docker-compose.release.yml`：四个目标服务增加 `TZ=Asia/Shanghai`；backend 另显式设置 `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai`；MySQL 增加 `--default-time-zone=+08:00`。
- JDBC URL 保持 `serverTimezone=Asia/Shanghai`，与 MySQL 新配置一致。
- 此配置只需重建容器，不需要重建镜像；现有镜像中均已检查到上海时区数据文件。

## 生产备份与回滚

生产当前版本标签为 `1.0.9-bcd960e`。执行前的完整数据库备份和配置快照保存在：

- 数据库：`/opt/dajin-system/backup/dajin_timezone_pre_20261012_1031.sql.gz`
- 最终静默期数据库备份（时间迁移前）：`/opt/dajin-system/backup/dajin_timezone_final_before_shift_20261012.sql.gz`
- 工作区/镜像快照：`/home/admin/dajin-system-deploy/snapshots/20261012-pre-timezone/`

先停止 backend 写入，再恢复完整数据库（该操作会覆盖备份之后的所有业务写入）：

```bash
cd /opt/dajin-system
docker compose -f docker-compose.release.yml -f docker-compose.override.yml stop backend
gzip -dc /opt/dajin-system/backup/dajin_timezone_final_before_shift_20261012.sql.gz \
  | docker exec -i dajin-system-mysql-1 sh -lc \
    'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
```

恢复旧 Compose 时区配置并重启目标容器：

```bash
cp -p /home/admin/dajin-system-deploy/snapshots/20261012-pre-timezone/docker-compose.release.yml \
  /opt/dajin-system/docker-compose.release.yml
docker compose -f docker-compose.release.yml -f docker-compose.override.yml \
  up -d --no-deps mysql backend admin-web mobile-web
```

恢复数据和旧时区配置必须作为一组回滚操作执行；仅恢复其中之一会再次造成时间口径错配。

## 最终运行态核验（2026-10-12）

### 时区与服务状态

生产 SSH 核验时间为 `2026-10-12 10:54:30 +0800`：宿主机和 `dajin-system-backend-1`、`dajin-system-admin-web-1`、`dajin-system-mobile-web-1`、`dajin-system-mysql-1` 容器的 `date` 输出一致，均为 `CST +0800`。容器环境 `TZ=Asia/Shanghai`；backend 的 `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai`，JDBC `serverTimezone=Asia/Shanghai`。

backend 容器内 `/actuator/health` 返回 `{"status":"UP","groups":["local-core"]}`。MySQL 查询返回 `@@global.time_zone=+08:00`、`@@session.time_zone=+08:00`、`@@system_time_zone=CST`；`NOW()=2026-10-12 10:54:31`、`UTC_TIMESTAMP()=2026-10-12 02:54:31`，相差 8 小时。迁移 marker `timezone_history_shift_20261012` 存在且值为 `1`。

### 写入时间与日期边界

在一个 MySQL 会话中创建并随后删除临时表，使用 `DEFAULT CURRENT_TIMESTAMP` 插入一行；写入时间与同一语句读取的 `NOW()` 相同，差值 0 秒。临时表已删除，没有保留测试数据。这验证 MySQL 默认时间写入；未创建实际加工/销售业务单。

用临时 DATETIME 样本校验报表使用的左闭右开条件：`2026-10-12 00:00:00 <= t < 2026-10-13 00:00:00` 包含 00:00、07:59:59、08:00 和 23:59:59，共 4 条，排除次日 00:00；10 月月区间包含 10 月末、排除 11 月 1 日 00:00。边界用例为合成 SQL 样本，不是生产报表 API 的专门运行态调用。

### 历史记录抽查

从迁移前完整备份与当前库对照，以下三张加工单的 `create_time`、`update_time` 均只平移了 8 小时，状态未变：

| 加工单号 | 字段 | 备份原值 | 当前值 | 结果 |
|---|---|---|---|---|
| `JG20261011103925795A7F6` | create_time / update_time | `2026-10-11 10:39:25` / `10:39:25` | `2026-10-11 18:39:25` / `18:39:25` | +8 小时，PENDING 不变 |
| `JG202610101228421582954` | create_time / update_time | `2026-10-10 12:28:42` / `12:39:13` | `2026-10-10 20:28:42` / `20:39:13` | +8 小时，COMPLETED 不变 |
| `JG20261008122539998079D` | create_time / update_time | `2026-10-08 12:25:39` / `12:26:51` | `2026-10-08 20:25:39` / `20:26:51` | +8 小时，COMPLETED 不变 |

### 前端显示验收状态

代码复核确认管理端 `formatTime`、移动端加工单详情以及收银端小票对后端 DATETIME 字符串不做二次时区偏移；管理端报表日期按 `Asia/Shanghai` 计算。未用生产账号在手机端、收银端、管理端逐页打开同一张单并检查报表/打印画面，因此“同一记录四处肉眼显示完全一致”仍需运维或业务人员做登录后的人工验收。前端没有新增加减时区逻辑。

### 备份校验

最终静默期备份 gzip 完整性校验通过；SHA256 现场复核为：

`d3c43bd8f8af0af3370a8a58c8351f8b0d9bfaa0c08a24c4f825527b2608d533`
