# ygp-spm

供应商采购管理服务（Supplier Procurement Management Service），负责供应商信息管理、采购单管理、退供管理、补货管理、数据权限等功能。

---

# Tech Stack
- Java 17
- Spring Boot 3.x
- Spring Cloud Alibaba
- MyBatis Plus
- Redis / Redisson
- Kafka
- XXL-Job (分布式任务调度)
- Nacos (配置中心/注册中心)
- MinIO/OSS (文件存储)

---

# Build Command
```bash
mvn clean package -DskipTests
```

> 本仓库 JDK: **17**（`zsh -ic 'sdk use java 17.0.15-sem && java -version'`）。执行 `mvn` 前先切换并校验。

---

# Global Rules

本仓库 Agent 行为遵循以下全局规则（位于 `~/.cursor/rules/`）：

- [`terminal-usage.mdc`](../../../.cursor/rules/terminal-usage.mdc) — 终端使用、JDK 8/17 探测与切换
- [`code-completion-workflow.mdc`](../../../.cursor/rules/code-completion-workflow.mdc) — 代码完成后须 `ReadLints`，编译/单测须用户确认
- [`java-standards.mdc`](../../../.cursor/rules/java-standards.mdc) — Java 编码规范（DTO 注释、日志前缀、BizCodeUtils、BigDecimalUtils、测试包结构、代码生成器）
- [`doc-naming-prefix.mdc`](../../../.cursor/rules/doc-naming-prefix.mdc) — 文档命名 `YYYYMMDD_NN.主题.md`

## SPM 业务规则（本仓库）

- [`po-direct-stocking-merge-push.mdc`](.cursor/rules/po-direct-stocking-merge-push.mdc) — **PO 直发备货**（`alwaysApply`）：DB 多物理行 / 页面 1 行 SKU / 下游 merge 后推送

---

# Project Structure

```text
ygp-spm/
├── spm-api/                    # API接口定义模块 (FeignClient)
│   └── src/main/java/
│       └── com/ygp/spm/
│           ├── supplier/       # 供应商相关API (3 FeignClients)
│           ├── purchase/       # 采购相关API (10 FeignClients)
│           ├── refund/         # 退供相关API (2 FeignClients)
│           ├── replenishment/  # 补货相关API (2 FeignClients)
│           ├── potentialSupplier/ # 潜在供应商API (7 FeignClients)
│           ├── batchShipment/  # 批量发货API (2 FeignClients)
│           ├── consignor/      # 货主API (1 FeignClient)
│           ├── paymentApply/   # 付款申请API (1 FeignClient)
│           ├── receiptdiscrepancyorder/ # 签收差异单API (2 FeignClients)
│           └── purchasesupport/ # 采购支持API (2 FeignClients)
│
├── spm-service/                # 核心业务服务模块
│   └── src/main/java/
│       └── com/ygp/spm/
│           ├── supplier/           # 供应商管理 (Controller/Service/DAO)
│           ├── potentialSupplier/  # 潜在供应商管理
│           ├── suppliermapping/    # 供应商映射管理
│           ├── purchase/           # 采购单管理
│           ├── purchaseaddress/    # 采购地址管理
│           ├── purchasesupport/    # 采购支持/配置
│           ├── refund/             # 退供管理
│           ├── replenishment/      # 补货管理
│           ├── batchShipment/      # 批量发货管理
│           ├── receiptdiscrepancyorder/ # 签收差异单
│           ├── crosslogistics/     # 跨境物流
│           ├── consignor/          # 货主管理
│           ├── approval/           # 审批回调
│           ├── balance/            # 余额管理
│           ├── rule/               # 规则引擎
│           ├── logistics/          # 物流管理
│           ├── datakanban/         # 数据看板
│           ├── burialpoint/        # 埋点
│           ├── job/                # 定时任务 (24 Jobs)
│           ├── mq/                 # 消息队列 (14 Consumers)
│           ├── config/             # 配置类
│           ├── common/             # 通用工具类
│           ├── aspect/             # AOP切面
│           ├── exception/          # 异常处理
│           ├── syslog/             # 操作日志
│           └── sysnote/            # 备注管理
│
├── dp-api/                     # 数据权限API接口定义
│   └── src/main/java/
│       └── datapermission/
│           └── feign/           # DataPermissionSpi (1 FeignClient)
│           └── model/           # Request/Response DTOs
│
├── dp-service/                 # 数据权限服务模块
│   └── src/main/java/
│       └── datapermission/
│           ├── controller/      # 3 Controllers
│           ├── service/         # 数据权限服务
│           ├── dao/             # 数据访问
│           └── entity/          # 实体类
│
├── dp-core/                    # 数据权限核心逻辑
│
└── knowledge/                  # 知识库目录
    └── code-index/             # 代码索引文件
```

---

# Module Index Files

## SPM Module

- [spm-api.md](knowledge/code-index/spm-api.md) - API接口定义模块 (34 FeignClients)
- [spm-supplier.md](knowledge/code-index/spm-supplier.md) - 供应商管理模块 (供应商、潜在供应商、供应商映射)
- [spm-purchase.md](knowledge/code-index/spm-purchase.md) - 采购管理模块 (采购单、送货清单、付款申请、待采商品)
- [spm-refund.md](knowledge/code-index/spm-refund.md) - 退供管理模块 (退供单、退供明细、退供规则)
- [spm-replenishment.md](knowledge/code-index/spm-replenishment.md) - 补货管理模块
- [spm-job.md](knowledge/code-index/spm-job.md) - 定时任务模块 (24 Jobs)
- [spm-mq.md](knowledge/code-index/spm-mq.md) - 消息队列模块 (14 Consumers, 13 Producers)
- [spm-other.md](knowledge/code-index/spm-other.md) - 其他业务模块 (跨境物流、货主、批量发货、签收差异单、审批、余额、规则引擎、物流、数据看板)
- [spm-support.md](knowledge/code-index/spm-support.md) - 支撑模块 (配置、工具类、异常处理、日志、备注)

## Data Permission Module

- [dp-api.md](knowledge/code-index/dp-api.md) - 数据权限API接口 (1 FeignClient)
- [dp-main.md](knowledge/code-index/dp-main.md) - 数据权限服务模块

---

# Execution Entry Summary

| Entry Type | Count | Module |
|------------|-------|--------|
| HTTP API | 150+ | spm-service (Controllers) |
| Job | 24 | spm-service (job/) |
| MQ Consumer | 14 | spm-service (mq/) |
| MQ Producer | 13 | spm-service (多Service) |
| Listener | 0 | - |

---

# Dependencies

## Internal Dependencies

| Module | Dependency |
|--------|------------|
| spm-service | spm-api |
| dp-service | dp-api, dp-core |

## External Services (via Feign)

| Service | APIs Used |
|---------|-----------|
| ygp-uc | UcUserApi, UcRightApi, UcQyUserApi |
| ygp-pms | ProductApi, SupplierGoodsApi |
| ygp-crm | CrmSalespersonApi, CrmCustomerApi |
| ygp-sys | FileApi, DictApi |
| ygp-oms | OrderApi |
| ygp-fms | PaymentApi |
| ygp-brand | BrandApi |
| ygp-cis | CisOutboundApi |
| approvalflow | ApprovalApi |
| filesystem | FileApi |
| bciscm-fms | FmsAggregationApi |

## External Infrastructure

| Infrastructure | Usage |
|----------------|-------|
| MySQL | 主数据存储 |
| Redis | 缓存、分布式锁、单号生成 |
| Kafka | 消息队列 |
| XXL-Job | 分布式任务调度 |
| Nacos | 配置中心、服务注册 |
| MinIO/OSS | 文件存储 |