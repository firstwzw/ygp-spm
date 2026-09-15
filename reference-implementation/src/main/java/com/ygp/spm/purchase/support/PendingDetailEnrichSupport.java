package com.ygp.spm.purchase.support;

import com.ygp.spm.purchase.dto.SkuSupplierPair;
import com.ygp.spm.purchase.entity.*;
import com.ygp.spm.purchase.mapper.*;
import com.ygp.spm.feign.pms.PmsSupplierApi;
import com.ygp.spm.feign.cis.CisInventoryApi;
import com.ygp.spm.feign.oms.OmsOrderApi;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 待采明细批量enrichment支持类
 * 
 * 核心优化：将所有per-row查询改为批量查询
 * 
 * @author Cursor Agent
 * @date 2026-09-15
 */
@Component
public class PendingDetailEnrichSupport {
    
    private static final Logger log = LoggerFactory.getLogger(PendingDetailEnrichSupport.class);
    
    @Autowired
    private SpmSupplierGoodsBindingMapper bindingMapper;
    
    @Autowired
    private SpmQuoteMapper quoteMapper;
    
    @Autowired
    private SpmNoteMapper noteMapper;
    
    @Autowired
    private PmsSupplierApi pmsSupplierApi;
    
    @Autowired
    private CisInventoryApi cisInventoryApi;
    
    @Autowired
    private OmsOrderApi omsOrderApi;
    
    /**
     * 批量填充推荐供应商
     * 
     * 优化前：N次DB查询 + N次Feign调用
     * 优化后：1次批量DB查询 + 1次批量Feign调用
     * 
     * @param details 待采明细列表
     */
    public void fillRecommendSupplierBatch(List<SpmPendingPurchaseDetail> details) {
        if (CollectionUtils.isEmpty(details)) {
            return;
        }
        
        long t0 = System.currentTimeMillis();
        
        // 1. 收集所有不同的SKU
        List<String> skuCodes = details.stream()
            .map(SpmPendingPurchaseDetail::getSkuCode)
            .distinct()
            .collect(Collectors.toList());
        
        if (CollectionUtils.isEmpty(skuCodes)) {
            return;
        }
        
        // 2. 批量查询供应商绑定信息
        List<SpmSupplierGoodsBinding> bindings = bindingMapper.selectBatchBySkuCodes(skuCodes);
        Map<String, List<SpmSupplierGoodsBinding>> bindingMap = bindings.stream()
            .collect(Collectors.groupingBy(SpmSupplierGoodsBinding::getSkuCode));
        
        // 3. 收集所有供应商编码
        Set<String> supplierCodes = bindings.stream()
            .map(SpmSupplierGoodsBinding::getSupplierCode)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        
        // 4. 批量调用PMS获取供应商详细信息（评级、名称等）
        Map<String, SupplierInfo> supplierInfoMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(supplierCodes)) {
            try {
                List<SupplierInfo> supplierInfos = pmsSupplierApi.batchGetSuppliers(new ArrayList<>(supplierCodes));
                if (CollectionUtils.isNotEmpty(supplierInfos)) {
                    supplierInfoMap = supplierInfos.stream()
                        .collect(Collectors.toMap(SupplierInfo::getCode, Function.identity(), (a, b) -> a));
                }
            } catch (Exception e) {
                log.error("[SPM待采列表-批量enrich] 批量获取供应商信息失败, supplierCodes={}", supplierCodes, e);
            }
        }
        
        // 5. 为每个待采明细组装推荐供应商列表
        Map<String, SupplierInfo> finalSupplierInfoMap = supplierInfoMap;
        for (SpmPendingPurchaseDetail detail : details) {
            List<SpmSupplierGoodsBinding> skuBindings = bindingMap.get(detail.getSkuCode());
            if (CollectionUtils.isNotEmpty(skuBindings)) {
                // 取前N个推荐供应商（已按评级、价格排序）
                List<RecommendSupplier> recommends = skuBindings.stream()
                    .limit(5) // 最多推荐5个
                    .map(binding -> this.buildRecommendSupplier(binding, finalSupplierInfoMap))
                    .collect(Collectors.toList());
                detail.setRecommendSuppliers(recommends);
            } else {
                detail.setRecommendSuppliers(Collections.emptyList());
            }
        }
        
        long cost = System.currentTimeMillis() - t0;
        log.info("[SPM待采列表-批量enrich] fillRecommendSupplier完成, detailCount={}, skuCount={}, supplierCount={}, costMs={}",
            details.size(), skuCodes.size(), supplierCodes.size(), cost);
    }
    
    /**
     * 批量填充价源选项信息
     * 
     * 包含两种场景：
     * - SUPPLIER_SELECTION: 查询绑定价，判断是否可选
     * - QUOTE_PRIORITY: 查询报价单信息
     * 
     * @param details 待采明细列表
     */
    public void fillPriceSourceOptionsBatch(List<SpmPendingPurchaseDetail> details) {
        if (CollectionUtils.isEmpty(details)) {
            return;
        }
        
        long t0 = System.currentTimeMillis();
        
        // 场景1: SUPPLIER_SELECTION - 批量查询绑定价
        this.fillSupplierSelectionOptions(details);
        
        // 场景2: QUOTE_PRIORITY - 批量查询报价单
        this.fillQuotePriorityOptions(details);
        
        long cost = System.currentTimeMillis() - t0;
        log.info("[SPM待采列表-批量enrich] fillPriceSourceOptions完成, detailCount={}, costMs={}",
            details.size(), cost);
    }
    
    /**
     * 填充SUPPLIER_SELECTION场景的价源选项
     */
    private void fillSupplierSelectionOptions(List<SpmPendingPurchaseDetail> details) {
        // 1. 筛选SUPPLIER_SELECTION场景的明细
        List<SpmPendingPurchaseDetail> selectionDetails = details.stream()
            .filter(d -> "SUPPLIER_SELECTION".equals(d.getPriceSource()))
            .collect(Collectors.toList());
        
        if (CollectionUtils.isEmpty(selectionDetails)) {
            return;
        }
        
        // 2. 收集所有(SKU, 供应商)配对
        List<SkuSupplierPair> pairs = selectionDetails.stream()
            .filter(d -> StringUtils.isNotBlank(d.getSupplierCode()))
            .map(d -> new SkuSupplierPair(d.getSkuCode(), d.getSupplierCode()))
            .distinct()
            .collect(Collectors.toList());
        
        if (CollectionUtils.isEmpty(pairs)) {
            return;
        }
        
        // 3. 批量查询绑定价
        List<SpmSupplierGoodsBinding> bindings = bindingMapper.selectBatchByPairs(pairs);
        Map<SkuSupplierPair, SpmSupplierGoodsBinding> bindingMap = bindings.stream()
            .collect(Collectors.toMap(
                b -> new SkuSupplierPair(b.getSkuCode(), b.getSupplierCode()),
                Function.identity(),
                (a, b) -> a
            ));
        
        // 4. 填充到明细
        for (SpmPendingPurchaseDetail detail : selectionDetails) {
            SkuSupplierPair key = new SkuSupplierPair(detail.getSkuCode(), detail.getSupplierCode());
            SpmSupplierGoodsBinding binding = bindingMap.get(key);
            
            detail.setSelectable(binding != null);
            if (binding != null) {
                detail.setBindingPrice(binding.getPurchasePrice());
                detail.setLeadTime(binding.getLeadTime());
            }
        }
    }
    
    /**
     * 填充QUOTE_PRIORITY场景的价源选项
     */
    private void fillQuotePriorityOptions(List<SpmPendingPurchaseDetail> details) {
        // 1. 筛选QUOTE_PRIORITY场景的明细
        List<SpmPendingPurchaseDetail> quoteDetails = details.stream()
            .filter(d -> "QUOTE_PRIORITY".equals(d.getPriceSource()))
            .filter(d -> StringUtils.isNotBlank(d.getPurchasePriceCode()))
            .collect(Collectors.toList());
        
        if (CollectionUtils.isEmpty(quoteDetails)) {
            return;
        }
        
        // 2. 收集所有报价单编码
        Set<String> quoteCodes = quoteDetails.stream()
            .map(SpmPendingPurchaseDetail::getPurchasePriceCode)
            .collect(Collectors.toSet());
        
        // 3. 批量查询报价单
        List<SpmQuote> quotes = quoteMapper.selectBatchByCodes(new ArrayList<>(quoteCodes));
        Map<String, SpmQuote> quoteMap = quotes.stream()
            .collect(Collectors.toMap(SpmQuote::getCode, Function.identity(), (a, b) -> a));
        
        // 4. 填充到明细
        for (SpmPendingPurchaseDetail detail : quoteDetails) {
            SpmQuote quote = quoteMap.get(detail.getPurchasePriceCode());
            if (quote != null) {
                detail.setQuoteInfo(quote);
                detail.setQuotePrice(quote.getQuotePrice());
                detail.setQuoteValidStart(quote.getValidStart());
                detail.setQuoteValidEnd(quote.getValidEnd());
            }
        }
    }
    
    /**
     * 批量填充备注信息
     * 
     * @param details 待采明细列表
     */
    public void fillNotesBatch(List<SpmPendingPurchaseDetail> details) {
        if (CollectionUtils.isEmpty(details)) {
            return;
        }
        
        long t0 = System.currentTimeMillis();
        
        // 1. 收集所有待采明细ID
        List<Long> detailIds = details.stream()
            .map(SpmPendingPurchaseDetail::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        if (CollectionUtils.isEmpty(detailIds)) {
            return;
        }
        
        // 2. 批量查询备注
        List<SpmNote> notes = noteMapper.selectBatchByBizIds(detailIds);
        Map<Long, List<SpmNote>> noteMap = notes.stream()
            .collect(Collectors.groupingBy(SpmNote::getBizId));
        
        // 3. 填充到明细
        for (SpmPendingPurchaseDetail detail : details) {
            List<SpmNote> detailNotes = noteMap.getOrDefault(detail.getId(), Collections.emptyList());
            detail.setNotes(detailNotes);
            detail.setNoteCount(detailNotes.size());
        }
        
        long cost = System.currentTimeMillis() - t0;
        log.info("[SPM待采列表-批量enrich] fillNotes完成, detailCount={}, noteCount={}, costMs={}",
            details.size(), notes.size(), cost);
    }
    
    /**
     * 批量填充库存信息
     * 
     * @param details 待采明细列表
     */
    public void fillInventoryBatch(List<SpmPendingPurchaseDetail> details) {
        if (CollectionUtils.isEmpty(details)) {
            return;
        }
        
        long t0 = System.currentTimeMillis();
        
        // 1. 收集所有SKU
        List<String> skuCodes = details.stream()
            .map(SpmPendingPurchaseDetail::getSkuCode)
            .distinct()
            .collect(Collectors.toList());
        
        if (CollectionUtils.isEmpty(skuCodes)) {
            return;
        }
        
        // 2. 批量调用CIS获取库存
        Map<String, Inventory> inventoryMap = new HashMap<>();
        try {
            List<Inventory> inventories = cisInventoryApi.batchGetInventoryBySku(skuCodes);
            if (CollectionUtils.isNotEmpty(inventories)) {
                inventoryMap = inventories.stream()
                    .collect(Collectors.toMap(Inventory::getSkuCode, Function.identity(), (a, b) -> a));
            }
        } catch (Exception e) {
            log.error("[SPM待采列表-批量enrich] 批量获取库存失败, skuCodes={}", skuCodes, e);
        }
        
        // 3. 填充到明细
        for (SpmPendingPurchaseDetail detail : details) {
            Inventory inventory = inventoryMap.get(detail.getSkuCode());
            if (inventory != null) {
                detail.setAvailableStock(inventory.getAvailableQty());
                detail.setTotalStock(inventory.getTotalQty());
            } else {
                detail.setAvailableStock(0);
                detail.setTotalStock(0);
            }
        }
        
        long cost = System.currentTimeMillis() - t0;
        log.info("[SPM待采列表-批量enrich] fillInventory完成, detailCount={}, skuCount={}, costMs={}",
            details.size(), skuCodes.size(), cost);
    }
    
    /**
     * 批量填充OMS订单信息
     * 
     * @param details 待采明细列表
     */
    public void fillOmsInfoBatch(List<SpmPendingPurchaseDetail> details) {
        if (CollectionUtils.isEmpty(details)) {
            return;
        }
        
        long t0 = System.currentTimeMillis();
        
        // 1. 收集所有订单明细ID
        List<Long> orderDetailIds = details.stream()
            .map(SpmPendingPurchaseDetail::getOrderDetailId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        
        if (CollectionUtils.isEmpty(orderDetailIds)) {
            return;
        }
        
        // 2. 批量调用OMS获取订单明细
        Map<Long, OrderDetail> orderDetailMap = new HashMap<>();
        try {
            List<OrderDetail> orderDetails = omsOrderApi.batchGetOrderDetails(orderDetailIds);
            if (CollectionUtils.isNotEmpty(orderDetails)) {
                orderDetailMap = orderDetails.stream()
                    .collect(Collectors.toMap(OrderDetail::getId, Function.identity(), (a, b) -> a));
            }
        } catch (Exception e) {
            log.error("[SPM待采列表-批量enrich] 批量获取OMS订单明细失败, orderDetailIds={}", orderDetailIds, e);
        }
        
        // 3. 填充到明细
        for (SpmPendingPurchaseDetail detail : details) {
            OrderDetail orderDetail = orderDetailMap.get(detail.getOrderDetailId());
            if (orderDetail != null) {
                detail.setCustomerName(orderDetail.getCustomerName());
                detail.setCustomerCode(orderDetail.getCustomerCode());
                detail.setOrderStatus(orderDetail.getStatus());
                detail.setDeliveryDate(orderDetail.getDeliveryDate());
            }
        }
        
        long cost = System.currentTimeMillis() - t0;
        log.info("[SPM待采列表-批量enrich] fillOmsInfo完成, detailCount={}, orderDetailCount={}, costMs={}",
            details.size(), orderDetailIds.size(), cost);
    }
    
    /**
     * 构建推荐供应商对象
     */
    private RecommendSupplier buildRecommendSupplier(SpmSupplierGoodsBinding binding, 
                                                     Map<String, SupplierInfo> supplierInfoMap) {
        RecommendSupplier recommend = new RecommendSupplier();
        recommend.setSupplierCode(binding.getSupplierCode());
        recommend.setPurchasePrice(binding.getPurchasePrice());
        recommend.setLeadTime(binding.getLeadTime());
        recommend.setGrade(binding.getGrade());
        
        // 补充供应商详细信息
        SupplierInfo info = supplierInfoMap.get(binding.getSupplierCode());
        if (info != null) {
            recommend.setSupplierName(info.getName());
            recommend.setSupplierLevel(info.getLevel());
            recommend.setCooperationYears(info.getCooperationYears());
        }
        
        return recommend;
    }
}
