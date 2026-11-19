package com.example.jora.model;

/** Request payload for creating an order. */
public class CreateOrderRequest {
    private String customerId;
    private Long preTaxAmount;
    private Long postTaxAmount;

    public CreateOrderRequest() {
    }

    public CreateOrderRequest(String customerId, Long preTaxAmount, Long postTaxAmount) {
        this.customerId = customerId;
        this.preTaxAmount = preTaxAmount;
        this.postTaxAmount = postTaxAmount;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public Long getPreTaxAmount() {
        return preTaxAmount;
    }

    public void setPreTaxAmount(Long preTaxAmount) {
        this.preTaxAmount = preTaxAmount;
    }

    public Long getPostTaxAmount() {
        return postTaxAmount;
    }

    public void setPostTaxAmount(Long postTaxAmount) {
        this.postTaxAmount = postTaxAmount;
    }
}
