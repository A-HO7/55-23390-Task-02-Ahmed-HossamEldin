package com.example.lab05.service;

import com.example.lab05.dto.DashboardResponse;
import com.example.lab05.model.cassandra.SensorReading;
import com.example.lab05.model.elastic.ProductDocument;
import com.example.lab05.model.mongo.PurchaseReceipt;
import com.example.lab05.model.neo4j.Person;
import com.example.lab05.repository.mongo.PurchaseReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to assemble a personalized dashboard reading from all 6 databases.
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final PurchaseReceiptRepository mongoRepo;
    private final SocialGraphService socialGraphService;
    private final SensorService sensorService;
    private final ProductSearchService searchService;
    private final RedisTemplate<String, Object> redisTemplate;

    public DashboardService(PurchaseReceiptRepository mongoRepo,
                            SocialGraphService socialGraphService,
                            SensorService sensorService,
                            ProductSearchService searchService,
                            RedisTemplate<String, Object> redisTemplate) {
        this.mongoRepo = mongoRepo;
        this.socialGraphService = socialGraphService;
        this.sensorService = sensorService;
        this.searchService = searchService;
        this.redisTemplate = redisTemplate;
    }

    public DashboardResponse getDashboard(String personName) {
        // Step 0 — Redis (check cache, try-catch)
        String cacheKey = "dashboard:" + personName;
        try {
            DashboardResponse cached = (DashboardResponse) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                // Reconstruct with servedFromCache = true
                return new DashboardResponse(
                    cached.personName(),
                    cached.totalSpent(),
                    cached.purchaseCount(),
                    cached.recentPurchases(),
                    cached.friendRecommendations(),
                    cached.friendsOfFriends(),
                    cached.recentActivity(),
                    cached.youMightAlsoLike(),
                    true
                );
            }
        } catch (Exception e) {
            log.warn("Redis cache check failed for {}: {}", personName, e.getMessage());
        }

        // --- Assemble Dashboard Freshly ---

        // Step 1 — MongoDB (Core data)
        List<PurchaseReceipt> allReceipts = mongoRepo.findByPersonName(personName);
        Double totalSpent = allReceipts.stream().mapToDouble(PurchaseReceipt::getTotalPrice).sum();
        Integer purchaseCount = allReceipts.size();
        
        // Take last 5 as recentPurchases
        List<PurchaseReceipt> recentPurchases = allReceipts.stream()
            .sorted(Comparator.comparing(PurchaseReceipt::getPurchasedAt).reversed())
            .limit(5)
            .collect(Collectors.toList());

        // Step 2 — Neo4j (Soft dependency)
        List<Map<String, Object>> friendRecommendations = new ArrayList<>();
        List<String> friendsOfFriends = new ArrayList<>();
        try {
            friendRecommendations = socialGraphService.getRecommendations(personName, 5);
            List<Person> fofNodes = socialGraphService.getFriendsOfFriends(personName);
            friendsOfFriends = fofNodes.stream().map(Person::getName).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to fetch Neo4j data for {}: {}", personName, e.getMessage());
        }

        // Step 3 — Cassandra (Soft dependency)
        List<SensorReading> recentActivity = new ArrayList<>();
        try {
            recentActivity = sensorService.getLatestReadings("user-activity-" + personName.toLowerCase(), 10);
        } catch (Exception e) {
            log.warn("Failed to fetch activity for {}: {}", personName, e.getMessage());
        }

        // Step 4 — Elasticsearch (Soft dependency)
        List<String> youMightAlsoLike = new ArrayList<>();
        try {
            Set<String> alreadyPurchasedNames = allReceipts.stream()
                .map(PurchaseReceipt::getProductName)
                .collect(Collectors.toSet());
            
            Set<String> distinctCategories = allReceipts.stream()
                .map(PurchaseReceipt::getProductCategory)
                .collect(Collectors.toSet());

            for (String category : distinctCategories) {
                List<ProductDocument> suggestions = searchService.getByCategory(category);
                List<String> categorySuggestions = suggestions.stream()
                    .filter(doc -> !alreadyPurchasedNames.contains(doc.getName()))
                    .map(ProductDocument::getName)
                    .limit(2)
                    .collect(Collectors.toList());
                youMightAlsoLike.addAll(categorySuggestions);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch ES suggestions for {}: {}", personName, e.getMessage());
        }

        // Step 5 — Construct and cache (try-catch on cache save)
        DashboardResponse response = new DashboardResponse(
            personName,
            totalSpent,
            purchaseCount,
            recentPurchases,
            friendRecommendations,
            friendsOfFriends,
            recentActivity,
            youMightAlsoLike,
            false // Freshly assembled
        );

        try {
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("Failed to cache dashboard for {}: {}", personName, e.getMessage());
        }

        return response;
    }
}
