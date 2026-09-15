-- ============================================
-- SPM 待采列表性能优化 - SQL索引优化脚本
-- 日期: 2026-09-15
-- 目标: 支持批量查询，减少N+1问题
-- ============================================

-- ============================================
-- 1. 供应商商品绑定表优化
-- ============================================

-- 1.1 批量查询SKU绑定的复合索引
-- 用途: fillRecommendSupplierBatch 批量查询
-- SQL: SELECT * FROM spm_supplier_goods_binding WHERE active=1 AND sku_code IN (...)
CREATE INDEX idx_sku_active ON spm_supplier_goods_binding(sku_code, active)
COMMENT '批量查询SKU供应商绑定';

-- 验证查询计划
EXPLAIN SELECT * 
FROM spm_supplier_goods_binding 
WHERE active = 1 AND sku_code IN ('SKU001', 'SKU002', 'SKU003');
-- 期望: type=range, key=idx_sku_active, rows ≈ 实际数量

-- 1.2 (SKU, 供应商)联合查询复合索引
-- 用途: fillPriceSourceOptionsBatch 中 SUPPLIER_SELECTION 场景
-- SQL: SELECT * FROM spm_supplier_goods_binding WHERE active=1 AND (sku_code, supplier_code) IN (...)
CREATE INDEX idx_sku_supplier_active ON spm_supplier_goods_binding(sku_code, supplier_code, active)
COMMENT 'SKU+供应商价源选项查询';

-- 验证查询计划
EXPLAIN SELECT * 
FROM spm_supplier_goods_binding 
WHERE active = 1 
  AND (sku_code, supplier_code) IN (('SKU001', 'SUP001'), ('SKU002', 'SUP002'));
-- 期望: type=range, key=idx_sku_supplier_active


-- ============================================
-- 2. 报价单表优化
-- ============================================

-- 2.1 报价单编码索引
-- 用途: fillPriceSourceOptionsBatch 中 QUOTE_PRIORITY 场景
-- SQL: SELECT * FROM spm_quote WHERE active=1 AND code IN (...)
CREATE INDEX idx_code_active ON spm_quote(code, active)
COMMENT '批量查询报价单';

-- 验证查询计划
EXPLAIN SELECT * 
FROM spm_quote 
WHERE active = 1 AND code IN ('QUOTE001', 'QUOTE002', 'QUOTE003');
-- 期望: type=range, key=idx_code_active


-- ============================================
-- 3. 备注表优化
-- ============================================

-- 3.1 业务ID+类型复合索引
-- 用途: fillNotesBatch 批量查询备注
-- SQL: SELECT * FROM spm_note WHERE active=1 AND biz_type='PENDING_DETAIL' AND biz_id IN (...)
CREATE INDEX idx_bizid_type_active ON spm_note(biz_id, biz_type, active)
COMMENT '批量查询业务备注';

-- 验证查询计划
EXPLAIN SELECT * 
FROM spm_note 
WHERE active = 1 
  AND biz_type = 'PENDING_DETAIL' 
  AND biz_id IN (1001, 1002, 1003);
-- 期望: type=range, key=idx_bizid_type_active


-- ============================================
-- 4. 待采明细表优化（主表）
-- ============================================

-- 4.1 订单号查询索引（列表主查询）
-- SQL: SELECT * FROM spm_pending_purchase_detail WHERE active=1 AND order_no=? ORDER BY ... LIMIT ?
CREATE INDEX idx_orderno_active ON spm_pending_purchase_detail(order_no, active, create_time)
COMMENT '待采列表主查询';

-- 验证查询计划
EXPLAIN SELECT * 
FROM spm_pending_purchase_detail 
WHERE active = 1 AND order_no = 'GBT260915PERF1000ORD0001' 
ORDER BY create_time DESC 
LIMIT 300;
-- 期望: type=range, key=idx_orderno_active, rows ≈ 实际总数


-- ============================================
-- 5. 索引健康度检查
-- ============================================

-- 5.1 检查索引是否生效
SELECT 
    table_name,
    index_name,
    column_name,
    cardinality,
    sub_part,
    packed,
    nullable,
    index_type
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN (
      'spm_supplier_goods_binding',
      'spm_quote',
      'spm_note',
      'spm_pending_purchase_detail'
  )
ORDER BY table_name, index_name, seq_in_index;

-- 5.2 检查索引大小和碎片率
SELECT 
    table_name,
    index_name,
    ROUND(stat_value * @@innodb_page_size / 1024 / 1024, 2) AS size_mb
FROM mysql.innodb_index_stats
WHERE database_name = DATABASE()
  AND table_name IN (
      'spm_supplier_goods_binding',
      'spm_quote',
      'spm_note',
      'spm_pending_purchase_detail'
  )
  AND stat_name = 'size'
ORDER BY size_mb DESC;


-- ============================================
-- 6. 慢查询验证（上线后执行）
-- ============================================

-- 6.1 开启慢查询日志（临时开启，验证后关闭）
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;  -- 超过1秒记录
SET GLOBAL log_queries_not_using_indexes = 'ON';  -- 记录未使用索引的查询

-- 6.2 查看慢查询统计
SELECT 
    DIGEST_TEXT,
    COUNT_STAR AS exec_count,
    ROUND(AVG_TIMER_WAIT / 1000000000, 3) AS avg_ms,
    ROUND(MAX_TIMER_WAIT / 1000000000, 3) AS max_ms,
    ROUND(SUM_TIMER_WAIT / 1000000000, 3) AS total_ms
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = DATABASE()
  AND DIGEST_TEXT LIKE '%spm_%'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 20;


-- ============================================
-- 7. 回滚脚本（如需回滚索引）
-- ============================================

-- DROP INDEX idx_sku_active ON spm_supplier_goods_binding;
-- DROP INDEX idx_sku_supplier_active ON spm_supplier_goods_binding;
-- DROP INDEX idx_code_active ON spm_quote;
-- DROP INDEX idx_bizid_type_active ON spm_note;
-- DROP INDEX idx_orderno_active ON spm_pending_purchase_detail;


-- ============================================
-- 8. 上线检查清单
-- ============================================

/*
[ ] 1. 在DEV环境验证所有索引创建成功
[ ] 2. 使用EXPLAIN验证查询计划符合预期
[ ] 3. 索引大小合理（单表索引 < 表数据大小的50%）
[ ] 4. 业务低峰时段添加索引（避免锁表）
[ ] 5. 使用 ALGORITHM=INPLACE 在线添加（MySQL 5.6+）
[ ] 6. 备份当前表结构
[ ] 7. 监控索引创建进度（information_schema.processlist）
[ ] 8. 上线后验证慢查询是否减少
*/


-- ============================================
-- 9. MySQL 8.0 优化建议
-- ============================================

-- 9.1 使用不可见索引测试（MySQL 8.0+）
-- 先创建为不可见，验证无负面影响后再设为可见
ALTER TABLE spm_supplier_goods_binding ADD INDEX idx_sku_active (sku_code, active) INVISIBLE;
-- 验证性能...
ALTER TABLE spm_supplier_goods_binding ALTER INDEX idx_sku_active VISIBLE;

-- 9.2 多值索引（如果有JSON字段批量查询需求）
-- 示例：如果 attributes 字段存储JSON数组
-- CREATE INDEX idx_attributes ON spm_supplier_goods_binding((CAST(attributes->'$.tags' AS CHAR(100) ARRAY)));


-- ============================================
-- 10. 性能基线记录
-- ============================================

/*
优化前基线（2026-09-15 压测数据）:
- limit=100: P95 = 4528ms
- limit=300: P95 = 8705ms (目标 < 5000ms)
- limit=1000: P95 = 15329ms

预期优化后:
- limit=100: P95 < 500ms
- limit=300: P95 < 1000ms
- limit=1000: P95 < 3000ms

验证SQL:
SELECT 
    DATE_FORMAT(create_time, '%Y-%m-%d %H:%i') AS time_bucket,
    COUNT(*) AS request_count,
    AVG(response_time_ms) AS avg_ms,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY response_time_ms) AS p95_ms
FROM api_access_log
WHERE api_path = '/api/spm/admin/spmPendingPurchaseDetail/page'
  AND create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY time_bucket
ORDER BY time_bucket DESC;
*/
