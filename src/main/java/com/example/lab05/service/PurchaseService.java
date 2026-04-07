package com.example.lab05.service;

import com.example.lab05.dto.PurchaseRequest;
import com.example.lab05.model.mongo.PurchaseReceipt;
import com.example.lab05.repository.mongo.PurchaseReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the purchase flow across all 6 databases.
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final ProductService productService;               // PostgreSQL
    private final PurchaseReceiptRepository mongoRepo;         // MongoDB
    private final SocialGraphService socialGraphService;       // Neo4j
    private final SensorService sensorService;                 // Cassandra
    private final ProductSearchService searchService;          // Elasticsearch
    private final RedisTemplate<String, Object> redisTemplate;    // Redis

    public PurchaseService(ProductService productService,
                           PurchaseReceiptRepository mongoRepo,
                           SocialGraphService socialGraphService,
                           SensorService sensorService,
                           ProductSearchService searchService,
                           RedisTemplate<String, Object> redisTemplate) {
        this.productService = productService;
        this.mongoRepo = mongoRepo;
        this.socialGraphService = socialGraphService;
        this.sensorService = sensorService;
        this.searchService = searchService;
        this.redisTemplate = redisTemplate;
    }

    // TODO (Section 2 — Purchase Flow):
    // Implement the orchestrated purchase logic here.
    public PurchaseReceipt executePurchase(PurchaseRequest request) {
        // Step 1 — PostgreSQL (HARD dependency)
        // Look up the product by productId.
        com.example.lab05.model.Product product = productService.getProductById(request.productId());
        
        // Validate stockQuantity >= requested quantity
        if (product.getStockQuantity() < request.quantity()) {
            throw new RuntimeException("Insufficient stock for product ID: " + request.productId());
        }
        
        // Deduct stock and save
        product.setStockQuantity(product.getStockQuantity() - request.quantity());
        productService.updateProduct(product.getId(), product);

        // Step 2 — MongoDB (HARD dependency)
        // Create and save PurchaseReceipt
        com.example.lab05.model.mongo.PurchaseReceipt receipt = new com.example.lab05.model.mongo.PurchaseReceipt(
                request.personName(),
                product.getName(),
                product.getCategory(),
                request.quantity(),
                product.getPrice(),
                request.purchaseDetails()
        );
        receipt = mongoRepo.save(receipt);

        // Step 3 — Neo4j (try-catch)
        try {
            socialGraphService.purchase(request.personName(), product.getName(), request.quantity(), product.getPrice());
        } catch (Exception e) {
            log.warn("Failed to create PURCHASED edge for {} -> {}",
                    request.personName(), product.getName(), e);
        }

        // Step 4 — Cassandra (try-catch)
        try {
            com.example.lab05.model.cassandra.SensorReading event = new com.example.lab05.model.cassandra.SensorReading();
            com.example.lab05.model.cassandra.SensorReadingKey key = new com.example.lab05.model.cassandra.SensorReadingKey();
            key.setSensorId("user-activity-" + request.personName().toLowerCase());
            key.setReadingTime(java.time.Instant.now());
            
            event.setKey(key);
            event.setLocation(product.getName());
            // Since temperature/humidity are primitives/Double depending on implementation, provide defaults.
            event.setTemperature(0.0);
            event.setHumidity(0.0);
            
            sensorService.recordReading(event);
        } catch (Exception e) {
            log.warn("Failed to log purchase event for {}",
                    request.personName(), e);
        }

        // Step 5 — Elasticsearch (try-catch)
        try {
            if (product.getStockQuantity() == 0) {
                java.util.List<com.example.lab05.model.elastic.ProductDocument> searchResults = searchService.searchByName(product.getName());
                if (searchResults != null && !searchResults.isEmpty()) {
                    com.example.lab05.model.elastic.ProductDocument doc = searchResults.get(0);
                    doc.setInStock(false);
                    searchService.saveProduct(doc);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to update ES inStock for product {}",
                    product.getId(), e);
        }

        // Step 6 — Redis (try-catch)
        try {
            // we will implement dashboard later, but this serves as the eviction logic
            redisTemplate.delete("dashboard:" + request.personName());
        } catch (Exception e) {
            log.warn("Failed to evict dashboard cache for {}",
                    request.personName(), e);
        }

        return receipt;
    }
    
    public List<PurchaseReceipt> getPurchasesByPerson(String personName) {
        return mongoRepo.findByPersonName(personName);
    }
}
