package com.example.lab05.dto;

import java.util.Map;

/**
 * DTO for a purchase request.
 * Record auto-generates constructor, getters, etc.
 */
public record PurchaseRequest(
    String personName,
    Long productId,
    Integer quantity,
    Map<String, Object> purchaseDetails
) {
}
