# SPM 待采列表性能优化 - 参考实现

**日期**: 2026-09-15  
**目标**: limit=300 时 P95 < 5000ms (当前 ~8704ms)  
**方法**: 批量查询 + 并行执行

---

## 📁 目录结构

```
reference-implementation/
├── README.md                           # 本文档
├── SQL-INDEX-OPTIMIZATION.sql          # SQL索引优化脚本
└── src/main/java/com/ygp/spm/purchase/
    ├── dto/
    │   └── SkuSupplierPair.java       # SKU-供应商配对键
    ├── mapper/
    │   └── SpmSupplierGoodsBindingMapper.java  # 批量查询Mapper
    ├── support/
    │   └── PendingDetailEnrichSupport.java     # 批量enrich核心类
    └── service/impl/
        └── SpmPendingPurchaseDetailServiceImpl.java  # 主服务实现
```

---

## 🎯 优化关键点

### 1. N+1 查询消除

| 步骤 | 优化前 | 优化后 | 减少次数 |
|------|--------|--------|----------|
| 推荐供应商 | 300×2 = 600次 | 2次（批量） | **-598** |
| 价源选项 | 300×1 = 300次 | 2次（批量） | **-298** |
| 备注 | 300×1 = 300次 | 1次（批量） | **-299** |
| 库存 | 300×1 = 300次 | 1次（批量） | **-299** |
| OMS信息 | 300×1 = 300次 | 1次（批量） | **-299** |
| **合计** | **1800次** | **8次** | **-1792 (-99.6%)** |

### 2. 并行执行

```
优化前（串行）:
DB查询(200ms) → 推荐供应商(1500ms) → 价源选项(500ms) → 备注(300ms) 
  → 库存(800ms) → OMS(700ms) = 总计 4000ms

优化后（并行）:
DB查询(200ms) → [并行执行5个enrich步骤] 
                 max(150ms, 100ms, 50ms, 120ms, 100ms) = 150ms
              = 总计 350ms
```

---

## 📝 核心代码说明

### A. 批量查询Mapper

**关键方法**:
```java
// 批量查询SKU的供应商绑定
List<SpmSupplierGoodsBinding> selectBatchBySkuCodes(List<String> skuCodes);

// 批量查询(SKU, 供应商)对的绑定价
List<SpmSupplierGoodsBinding> selectBatchByPairs(List<SkuSupplierPair> pairs);
```

**XML实现**:
```xml
<select id="selectBatchBySkuCodes">
    SELECT * FROM spm_supplier_goods_binding
    WHERE active = 1 AND sku_code IN
    <foreach collection="skuCodes" item="sku" open="(" separator="," close=")">
        #{sku}
    </foreach>
    ORDER BY sku_code, grade DESC, purchase_price ASC
</select>
```

### B. 批量Enrich支持类

**PendingDetailEnrichSupport** 包含5个批量方法:

1. `fillRecommendSupplierBatch()` - 批量推荐供应商
2. `fillPriceSourceOptionsBatch()` - 批量价源选项
3. `fillNotesBatch()` - 批量备注
4. `fillInventoryBatch()` - 批量库存
5. `fillOmsInfoBatch()` - 批量OMS信息

**典型实现模式**:
```java
public void fillXxxBatch(List<Detail> details) {
    // 1. 收集批量查询的key
    List<Key> keys = details.stream()
        .map(Detail::getKey)
        .distinct()
        .collect(Collectors.toList());
    
    // 2. 批量查询
    Map<Key, Value> map = xxxMapper.selectBatch(keys)
        .stream()
        .collect(Collectors.toMap(...));
    
    // 3. 填充回detail
    details.forEach(d -> d.setXxx(map.get(d.getKey())));
}
```

### C. 并行执行Service

**SpmPendingPurchaseDetailServiceImpl** 主方法:

```java
public PageInfo<PendingDetailVO> spmPendingPurchaseDetailPage(...) {
    // 1. DB分页查询
    Page<Detail> page = mapper.selectPage(...);
    
    // 2. 并行enrich
    CompletableFuture<Void> f1 = CompletableFuture.runAsync(
        () -> enrichSupport.fillRecommendSupplierBatch(page.getRecords()), 
        enrichExecutor
    );
    CompletableFuture<Void> f2 = ... // 其他4个
    
    CompletableFuture.allOf(f1, f2, f3, f4, f5).join();
    
    // 3. 转VO并返回
    return new PageInfo<>(...);
}
```

---

## 🚀 如何应用到实际代码

### Step 1: 添加SQL索引

```bash
# 在DEV环境执行
mysql -h mysql-dev1.yigongpin.net -u root -p < SQL-INDEX-OPTIMIZATION.sql

# 验证索引创建成功
mysql> SHOW INDEX FROM spm_supplier_goods_binding;
```

### Step 2: 创建批量查询Mapper方法

参考 `SpmSupplierGoodsBindingMapper.java` 和对应的XML文件，在实际Mapper中添加:

1. `selectBatchBySkuCodes` - 推荐供应商用
2. `selectBatchByPairs` - 价源选项用（SUPPLIER_SELECTION场景）

同理在 `SpmQuoteMapper`、`SpmNoteMapper` 中添加批量方法。

### Step 3: 创建/重构Enrich支持类

选项A: 新建 `PendingDetailEnrichSupport`
```java
@Component
public class PendingDetailEnrichSupport {
    @Autowired private SpmSupplierGoodsBindingMapper bindingMapper;
    @Autowired private PmsSupplierApi pmsSupplierApi;
    // ... 其他依赖
    
    public void fillRecommendSupplierBatch(List<Detail> details) { ... }
    public void fillPriceSourceOptionsBatch(List<Detail> details) { ... }
    // ... 其他批量方法
}
```

选项B: 在现有Service中重构原有enrich方法为批量版本。

### Step 4: 主Service改用批量+并行

在 `SpmPendingPurchaseDetailServiceImpl.spmPendingPurchaseDetailPage()` 中:

```java
// 原代码（串行 + 循环查询）:
for (Detail d : page.getRecords()) {
    this.fillRecommendSupplier(d);  // ❌ N次查询
    this.fillPriceSourceOptions(d); // ❌ N次查询
    // ...
}

// 改为（批量 + 并行）:
CompletableFuture.allOf(
    CompletableFuture.runAsync(() -> enrichSupport.fillRecommendSupplierBatch(page.getRecords()), executor),
    CompletableFuture.runAsync(() -> enrichSupport.fillPriceSourceOptionsBatch(page.getRecords()), executor),
    // ... 其他步骤
).join();
```

### Step 5: 配置线程池

在 `application.yml` 或 `@Configuration` 中:

```java
@Bean("enrichExecutor")
public Executor enrichExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("spm-enrich-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
}
```

### Step 6: 验证与测试

1. **单元测试**: 验证批量查询SQL正确性
2. **本地测试**: 用Postman调用接口，观察日志中的 `dbMs`、`enrichMs`
3. **DEV1压测**: 用原脚本重跑 `GBT260915PERF1000ORD0001` 测试单
4. **验收标准**: limit=300 时 P95 < 5000ms

---

## 🔍 下游批量接口协调

### PMS - 批量供应商接口

**需要**: 
```java
@PostMapping("/api/pms/admin/supplier/batchGet")
Response<List<SupplierInfoDTO>> batchGetSuppliers(@RequestBody List<String> codes);
```

**若暂无**: 可在SPM内用信号量控制并发:
```java
Semaphore sem = new Semaphore(10); // 最多并发10个
List<CompletableFuture<SupplierInfo>> futures = supplierCodes.stream()
    .map(code -> CompletableFuture.supplyAsync(() -> {
        sem.acquire();
        try {
            return pmsSupplierApi.getSupplier(code); // 单个接口
        } finally {
            sem.release();
        }
    }, executor))
    .collect(Collectors.toList());
```

### CIS - 批量库存接口

**需要**:
```java
@PostMapping("/api/cis/admin/inventory/batchGetBySku")
Response<List<InventoryDTO>> batchGetInventoryBySku(@RequestBody List<String> skuCodes);
```

### OMS - 批量订单明细接口

**需要**:
```java
@PostMapping("/api/oms/admin/orderDetail/batchGet")
Response<List<OrderDetailDTO>> batchGetOrderDetails(@RequestBody List<Long> ids);
```

---

## 📊 性能预估

### 假设条件

- limit = 300
- 平均每SKU关联 2 个供应商
- DB单次查询: 5ms
- Feign单次调用: 50ms
- 批量overhead: ×1.5

### 优化前（串行 + N+1）

| 步骤 | 次数 | 单次(ms) | 总耗时(ms) |
|------|------|----------|-----------|
| 推荐供应商 (DB) | 300 | 5 | 1,500 |
| 推荐供应商 (Feign) | 300 | 50 | 15,000 |
| 价源选项 (DB) | 300 | 5 | 1,500 |
| 备注 (DB) | 300 | 5 | 1,500 |
| 库存 (Feign) | 300 | 50 | 15,000 |
| OMS (Feign) | 300 | 50 | 15,000 |
| **总计** | | | **49,500ms** |

实测 8704ms 说明有部分并行或缓存。

### 优化后（批量 + 并行）

| 步骤 | 批量次数 | 单次(ms)×overhead | 耗时(ms) | 并行 |
|------|---------|------------------|---------|------|
| 推荐供应商 (DB) | 1 | 5×1.5 | 8 | ✓ |
| 推荐供应商 (Feign) | 1 | 50×1.5 | 75 | ✓ |
| 价源选项 (DB) | 2 | 5×1.5 | 15 | ✓ |
| 备注 (DB) | 1 | 5×1.5 | 8 | ✓ |
| 库存 (Feign) | 1 | 50×1.5 | 75 | ✓ |
| OMS (Feign) | 1 | 50×1.5 | 75 | ✓ |
| **并行max** | | | **max = 75ms** | |
| DB分页 | | | 50ms | 串行 |
| **总计** | | | **125ms** | |

加上网络波动 buffer，**预估 P95 < 500ms**，远超目标 5000ms。

---

## ⚠️ 注意事项

### 1. MySQL IN子句限制

单个 `IN (...)` 建议不超过 1000 个元素，若超过则分批:

```java
List<List<String>> batches = Lists.partition(skuCodes, 500);
List<Binding> results = new ArrayList<>();
for (List<String> batch : batches) {
    results.addAll(bindingMapper.selectBatchBySkuCodes(batch));
}
```

### 2. 线程池配置

- `corePoolSize`: 建议 ≥ 并行enrich步骤数（如5）
- `maxPoolSize`: 建议 2× corePoolSize
- `queueCapacity`: 避免过大导致任务排队延迟
- `rejectedPolicy`: 用 `CallerRunsPolicy`（让调用线程执行，避免丢任务）

### 3. Feign超时配置

批量接口可能返回数据量大，适当调高超时:

```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 5000
        readTimeout: 30000  # 从10s调至30s
```

### 4. 业务逻辑一致性

确保批量版本的排序、筛选逻辑与原per-row版本完全一致:

- 推荐供应商排序: grade DESC, purchase_price ASC, lead_time ASC
- 价源可选性判断: 绑定价存在 + active=1
- 备注排序: create_time DESC

### 5. 空值处理

批量查询返回的Map中，若某key无对应value，需设置默认值:

```java
details.forEach(d -> {
    List<Note> notes = noteMap.getOrDefault(d.getId(), Collections.emptyList());
    d.setNotes(notes);
    d.setNoteCount(notes.size());
});
```

---

## 🧪 测试清单

### 单元测试

- [ ] `selectBatchBySkuCodes` 返回正确SKU绑定
- [ ] `selectBatchByPairs` 返回正确(SKU, 供应商)绑定
- [ ] `fillRecommendSupplierBatch` 推荐供应商排序正确
- [ ] `fillPriceSourceOptionsBatch` 价源可选性判断正确
- [ ] `fillNotesBatch` 备注归属和排序正确
- [ ] 空值场景: SKU无绑定、无备注等

### 集成测试

- [ ] DEV1环境部署成功
- [ ] 用测试单 `GBT260915PERF1000ORD0001` 调用接口
- [ ] 验证返回数据与原逻辑一致（抽样对比）
- [ ] 观察日志中的 `dbMs`, `enrichMs`, `totalMs`

### 性能测试

- [ ] limit=100: P95 < 1000ms
- [ ] limit=300: P95 < 5000ms ✅ **目标**
- [ ] limit=1000: P95 < 10000ms
- [ ] 并发10个请求: P95 无明显劣化

---

## 📈 监控指标

### 上线后观察

1. **Skywalking**: 
   - Span `SpmPendingPurchaseDetailService.spmPendingPurchaseDetailPage` 平均/P95耗时
   - DB查询次数（应减少 99%+）
   - Feign调用次数（应减少 99%+）

2. **业务日志**:
   ```
   [SPM待采列表] orderNo=GBT..., limit=300, dbMs=50, enrichMs=120, totalMs=170, 
       detail[recommendMs=80, priceOptionMs=15, noteMs=8, inventoryMs=75, omsMs=70]
   ```

3. **MySQL慢查询**:
   ```sql
   SELECT COUNT(*) FROM mysql.slow_log
   WHERE sql_text LIKE '%spm_pending_purchase_detail%'
     AND query_time > 1;
   ```
   应明显减少。

4. **错误率**: 
   - 接口 5xx 错误率 < 0.01%
   - 业务异常（如库存获取失败）比例 < 1%

---

## 🔧 故障排查

### 问题1: 批量查询返回空

**现象**: `bindingMapper.selectBatchBySkuCodes(skuCodes)` 返回空list

**排查**:
1. 检查 `skuCodes` 是否为空
2. 检查SQL中 `active=1` 条件是否过滤掉了数据
3. 用EXPLAIN验证索引是否生效
4. 直接在MySQL客户端执行SQL验证数据存在性

### 问题2: 并发执行报错

**现象**: `RejectedExecutionException` 或线程池耗尽

**排查**:
1. 检查线程池配置: `corePoolSize`, `maxPoolSize`, `queueCapacity`
2. 观察监控: 线程池活跃线程数、队列长度
3. 调整策略: 改用 `CallerRunsPolicy` 或增大线程池

### 问题3: Feign调用超时

**现象**: `SocketTimeoutException: Read timed out`

**排查**:
1. 确认下游接口是否支持批量查询
2. 检查批量接口返回数据量（>1MB 可能超时）
3. 调高 `feign.client.config.default.readTimeout`
4. 若下游无批量接口，改用信号量限流的单次调用

### 问题4: 业务结果不一致

**现象**: 批量版本返回的推荐供应商与原版本不同

**排查**:
1. 对比SQL: 原逻辑的WHERE/ORDER BY 与批量SQL是否一致
2. 检查排序逻辑: `grade DESC, purchase_price ASC`
3. 检查数据过滤: `active=1`, `valid_start/valid_end` 时间范围
4. 写AB对比单测: 同输入，对比两版本输出

---

## 📦 依赖版本

- **Java**: 17 (JDK 8+ 也兼容)
- **Spring Boot**: 3.x (2.x 也兼容)
- **MyBatis Plus**: 3.x
- **MySQL**: 8.0 (支持 `(col1, col2) IN (...)` 语法，5.7 需改写SQL)

---

## 📚 参考资料

- [技术方案文档](../docs/20260915_01.pending-purchase-list-performance-optimization.md)
- [压测报告](../uploads/_____ad2e.md)
- [压测数据](../uploads/bench_results_dda9.json)
- MyBatis Plus批量查询: https://baomidou.com/pages/49cc81/
- CompletableFuture并行编程: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html

---

**作者**: Cursor Agent  
**版本**: v1.0  
**最后更新**: 2026-09-15
