package com.example.lab05.repository.mongo;

import com.example.lab05.model.mongo.PurchaseReceipt;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository interface for PurchaseReceipt MongoDB documents.
 */
@Repository
public interface PurchaseReceiptRepository extends MongoRepository<PurchaseReceipt, String> {

    List<PurchaseReceipt> findByPersonName(String personName);

    List<PurchaseReceipt> findByProductCategory(String category);
}
