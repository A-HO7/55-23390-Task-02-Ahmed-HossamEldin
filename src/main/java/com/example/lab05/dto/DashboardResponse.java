package com.example.lab05.dto;

import com.example.lab05.model.mongo.PurchaseReceipt;
import com.example.lab05.model.cassandra.SensorReading;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Record representing the personalized dashboard response.
 * Implements Serializable for Redis caching.
 */
public record DashboardResponse(
    String personName,
    Double totalSpent,
    Integer purchaseCount,
    List<PurchaseReceipt> recentPurchases,
    List<Map<String, Object>> friendRecommendations,
    List<String> friendsOfFriends,
    List<SensorReading> recentActivity,
    List<String> youMightAlsoLike,
    boolean servedFromCache
) implements Serializable {}
