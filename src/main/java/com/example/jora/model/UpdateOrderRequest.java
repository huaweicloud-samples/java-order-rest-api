package com.example.jora.model;

import java.math.BigDecimal;

/** Request payload for updating an existing order (optimistic locking by version). */
public class UpdateOrderRequest {
    private String orderId;
    private String customerId;
    private BigDecimal preTaxAmount;
    private BigDecimal postTaxAmount;
    private Long version;

    public UpdateOrderRequest() {
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getPreTaxAmount() {
        return preTaxAmount;
    }

    public void setPreTaxAmount(BigDecimal preTaxAmount) {
        this.preTaxAmount = preTaxAmount;
    }

    public BigDecimal getPostTaxAmount() {
        return postTaxAmount;
    }

    public void setPostTaxAmount(BigDecimal postTaxAmount) {
        this.postTaxAmount = postTaxAmount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
