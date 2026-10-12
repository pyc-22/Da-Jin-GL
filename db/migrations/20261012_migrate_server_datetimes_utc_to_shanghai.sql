-- One-time production data correction for a database whose session and system
-- time zones are still UTC. Run only after a verified full backup and while
-- backend writes are paused. All migrated columns are DATETIME values written
-- by server SQL NOW()/CURRENT_TIMESTAMP or server-side event timestamps.
--
-- The guarded marker makes reruns no-ops after a successful commit. The UTC
-- precondition prevents applying the +8 hour correction after MySQL is already
-- running at +08:00. Non-time business columns are never updated here.

SET @timezone_shift_apply = (
  SELECT IF(
    @@system_time_zone = 'UTC'
      AND @@session.time_zone = 'SYSTEM'
      AND NOT EXISTS (
        SELECT 1
        FROM sys_config
        WHERE store_id = 1
          AND config_group = 'MIGRATION'
          AND config_key = 'timezone_history_shift_20261012'
      ),
    1,
    0
  )
);

START TRANSACTION;

UPDATE approval
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    approve_time = DATE_ADD(approve_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE commission_record
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE commission_rule
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE finance_record
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE gold_market_quote
SET update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE gold_price
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE gold_price_change_log
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE goods
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE goods_category
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE goods_piece
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE member
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE member_balance_record
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE member_consume
SET consume_time = DATE_ADD(consume_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE old_material
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE old_material_type
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE operation_log
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE pay_channel
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE print_job
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    printed_time = DATE_ADD(printed_time, INTERVAL 8 HOUR),
    ignored_time = DATE_ADD(ignored_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE print_template
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE process_item
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_category
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_commission
SET paid_at = DATE_ADD(paid_at, INTERVAL 8 HOUR),
    create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_item
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_order
SET loss_time = DATE_ADD(loss_time, INTERVAL 8 HOUR),
    handover_time = DATE_ADD(handover_time, INTERVAL 8 HOUR),
    create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR),
    completed_time = DATE_ADD(completed_time, INTERVAL 8 HOUR),
    picked_up_time = DATE_ADD(picked_up_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_order_item
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_payment
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_payment_component
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE processing_price_tier
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE recycle_order
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sales_order
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR),
    paid_time = DATE_ADD(paid_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sales_order_item
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE stock_check
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE stock_in
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE stock_inbound
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE stock_inbound_item
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE stock_out
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE stock_supplier
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sys_config
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sys_role
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sys_role_permission
SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sys_store
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sys_user
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE sys_user_permission
SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE trade_in
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

UPDATE visit_task
SET create_time = DATE_ADD(create_time, INTERVAL 8 HOUR),
    update_time = DATE_ADD(update_time, INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

INSERT INTO sys_config
  (store_id, config_group, config_key, config_value, description, config_sort,
   enabled, create_time, update_time)
SELECT 1, 'MIGRATION', 'timezone_history_shift_20261012', '1',
       'UTC服务端时间历史数据+8小时迁移完成', 0, 1,
       DATE_ADD(NOW(), INTERVAL 8 HOUR), DATE_ADD(NOW(), INTERVAL 8 HOUR)
WHERE @timezone_shift_apply = 1;

SELECT @timezone_shift_apply AS migration_applied;
COMMIT;
