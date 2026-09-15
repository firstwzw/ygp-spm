package com.ygp.spm.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ygp.spm.purchase.dto.SkuSupplierPair;
import com.ygp.spm.purchase.entity.SpmSupplierGoodsBinding;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 供应商商品绑定Mapper
 * 
 * @author Cursor Agent
 * @date 2026-09-15
 */
public interface SpmSupplierGoodsBindingMapper extends BaseMapper<SpmSupplierGoodsBinding> {
    
    /**
     * 批量查询SKU的供应商绑定信息
     * 用于推荐供应商功能
     * 
     * @param skuCodes SKU编码列表
     * @return 供应商绑定列表（已按 sku_code, grade DESC, purchase_price ASC 排序）
     */
    List<SpmSupplierGoodsBinding> selectBatchBySkuCodes(@Param("skuCodes") List<String> skuCodes);
    
    /**
     * 批量查询(SKU, 供应商)对的绑定价格
     * 用于SUPPLIER_SELECTION场景的价源选项判断
     * 
     * @param pairs (SKU, 供应商) 配对列表
     * @return 绑定信息列表
     */
    List<SpmSupplierGoodsBinding> selectBatchByPairs(@Param("pairs") List<SkuSupplierPair> pairs);
}
