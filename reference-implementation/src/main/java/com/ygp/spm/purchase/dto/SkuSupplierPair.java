package com.ygp.spm.purchase.dto;

import java.util.Objects;

/**
 * SKU-供应商配对键
 * 用于批量查询绑定价
 * 
 * @author Cursor Agent
 * @date 2026-09-15
 */
public class SkuSupplierPair {
    
    private String skuCode;
    private String supplierCode;
    
    public SkuSupplierPair() {
    }
    
    public SkuSupplierPair(String skuCode, String supplierCode) {
        this.skuCode = skuCode;
        this.supplierCode = supplierCode;
    }
    
    public String getSkuCode() {
        return skuCode;
    }
    
    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }
    
    public String getSupplierCode() {
        return supplierCode;
    }
    
    public void setSupplierCode(String supplierCode) {
        this.supplierCode = supplierCode;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SkuSupplierPair that = (SkuSupplierPair) o;
        return Objects.equals(skuCode, that.skuCode) && 
               Objects.equals(supplierCode, that.supplierCode);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(skuCode, supplierCode);
    }
    
    @Override
    public String toString() {
        return "SkuSupplierPair{" +
                "skuCode='" + skuCode + '\'' +
                ", supplierCode='" + supplierCode + '\'' +
                '}';
    }
}
