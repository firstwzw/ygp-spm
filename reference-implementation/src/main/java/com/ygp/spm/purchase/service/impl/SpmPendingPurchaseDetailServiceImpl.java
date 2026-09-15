package com.ygp.spm.purchase.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ygp.base.common.domain.PageInfo;
import com.ygp.spm.purchase.entity.SpmPendingPurchaseDetail;
import com.ygp.spm.purchase.mapper.SpmPendingPurchaseDetailMapper;
import com.ygp.spm.purchase.request.PendingDetailQueryRequest;
import com.ygp.spm.purchase.service.SpmPendingPurchaseDetailService;
import com.ygp.spm.purchase.support.PendingDetailEnrichSupport;
import com.ygp.spm.purchase.vo.PendingDetailVO;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 待采明细服务实现
 * 
 * 性能优化版本：批量查询 + 并行enrich
 * 
 * @author Cursor Agent
 * @date 2026-09-15
 */
@Service
public class SpmPendingPurchaseDetailServiceImpl implements SpmPendingPurchaseDetailService {
    
    private static final Logger log = LoggerFactory.getLogger(SpmPendingPurchaseDetailServiceImpl.class);
    
    @Autowired
    private SpmPendingPurchaseDetailMapper pendingDetailMapper;
    
    @Autowired
    private PendingDetailEnrichSupport enrichSupport;
    
    /**
     * 线程池：用于并行执行enrich任务
     * 建议配置：coreSize=10, maxSize=20, queueCapacity=100
     */
    @Autowired
    private Executor enrichExecutor;
    
    /**
     * 分页查询待采明细
     * 
     * 性能优化关键点：
     * 1. DB分页查询基础数据（单次）
     * 2. 所有enrich步骤改为批量查询
     * 3. enrich步骤之间并行执行
     * 
     * @param request 查询请求
     * @return 分页结果
     */
    @Override
    public PageInfo<PendingDetailVO> spmPendingPurchaseDetailPage(PendingDetailQueryRequest request) {
        long t0 = System.currentTimeMillis();
        
        // 1. 数据库分页查询（基础数据）
        long dbStart = System.currentTimeMillis();
        Page<SpmPendingPurchaseDetail> mybatisPage = new Page<>(request.getPage(), request.getLimit());
        Page<SpmPendingPurchaseDetail> page = pendingDetailMapper.selectPageByCondition(mybatisPage, request);
        long dbMs = System.currentTimeMillis() - dbStart;
        
        if (CollectionUtils.isEmpty(page.getRecords())) {
            log.info("[SPM待采列表] 无数据, orderNo={}, dbMs={}", request.getOrderNo(), dbMs);
            return new PageInfo<>(Collections.emptyList(), 0L);
        }
        
        // 2. 并行执行所有enrich步骤
        long enrichStart = System.currentTimeMillis();
        
        CompletableFuture<Long> f1 = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            enrichSupport.fillRecommendSupplierBatch(page.getRecords());
            return System.currentTimeMillis() - t;
        }, enrichExecutor);
        
        CompletableFuture<Long> f2 = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            enrichSupport.fillPriceSourceOptionsBatch(page.getRecords());
            return System.currentTimeMillis() - t;
        }, enrichExecutor);
        
        CompletableFuture<Long> f3 = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            enrichSupport.fillNotesBatch(page.getRecords());
            return System.currentTimeMillis() - t;
        }, enrichExecutor);
        
        CompletableFuture<Long> f4 = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            enrichSupport.fillInventoryBatch(page.getRecords());
            return System.currentTimeMillis() - t;
        }, enrichExecutor);
        
        CompletableFuture<Long> f5 = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            enrichSupport.fillOmsInfoBatch(page.getRecords());
            return System.currentTimeMillis() - t;
        }, enrichExecutor);
        
        // 等待所有enrich任务完成
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(f1, f2, f3, f4, f5);
        allTasks.join();
        
        long enrichMs = System.currentTimeMillis() - enrichStart;
        
        // 获取各步骤耗时
        Long recommendMs = f1.join();
        Long priceOptionMs = f2.join();
        Long noteMs = f3.join();
        Long inventoryMs = f4.join();
        Long omsMs = f5.join();
        
        // 3. 转换为VO
        long voStart = System.currentTimeMillis();
        List<PendingDetailVO> vos = page.getRecords().stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        long voMs = System.currentTimeMillis() - voStart;
        
        long totalMs = System.currentTimeMillis() - t0;
        
        // 4. 记录性能日志
        log.info("[SPM待采列表] 查询完成, orderNo={}, page={}, limit={}, total={}, " +
                "dbMs={}, enrichMs={}, voMs={}, totalMs={}, " +
                "detail[recommendMs={}, priceOptionMs={}, noteMs={}, inventoryMs={}, omsMs={}]",
            request.getOrderNo(), request.getPage(), request.getLimit(), page.getTotal(),
            dbMs, enrichMs, voMs, totalMs,
            recommendMs, priceOptionMs, noteMs, inventoryMs, omsMs);
        
        return new PageInfo<>(vos, page.getTotal());
    }
    
    /**
     * 实体转VO
     */
    private PendingDetailVO convertToVO(SpmPendingPurchaseDetail entity) {
        PendingDetailVO vo = new PendingDetailVO();
        // ... 字段映射
        return vo;
    }
}
