-- Run with the existing database selected. Additive and safe to repeat.
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sales_order' AND column_name='handover'), 'SELECT 1', 'ALTER TABLE sales_order ADD COLUMN handover TINYINT NOT NULL DEFAULT 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='processing_order' AND column_name='handover'), 'SELECT 1', 'ALTER TABLE processing_order ADD COLUMN handover TINYINT NOT NULL DEFAULT 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='processing_order' AND column_name='handover_time'), 'SELECT 1', 'ALTER TABLE processing_order ADD COLUMN handover_time DATETIME NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SELECT table_name,column_name,column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('sales_order','processing_order') AND column_name IN ('handover','handover_time');
