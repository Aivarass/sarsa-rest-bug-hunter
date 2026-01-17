package com.example.api.controller;

import com.example.api.model.Item;
import com.example.api.model.Price;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    public static final Map<Long, Price> prices = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    // GET all prices
    @GetMapping
    public ResponseEntity<List<Price>> getAllPrices() {
        return ResponseEntity.ok(new ArrayList<>(prices.values()));
    }

    // GET single price
    @GetMapping("/{id}")
    public ResponseEntity<Price> getPrice(@PathVariable Long id) {
        Price price = prices.get(id);
        if (price == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(price);
    }

    // POST - create new price (requires valid item ID)
    @PostMapping
    public ResponseEntity<Price> createPrice(@RequestBody Price price) {
        if (price.getItem() == null || price.getItem().getId() == null) {
            return ResponseEntity.notFound().build();
        }
        if (!ItemController.items.containsKey(price.getItem().getId())) {
            return ResponseEntity.notFound().build();
        }
        // BUG: Negative price causes server error
//        if (price.getPrice() < 0) {
//            return ResponseEntity.internalServerError().body(null);
//        }
        Long id = idCounter.getAndIncrement();
        price.setId(id);
        prices.put(id, price);
        return ResponseEntity.status(HttpStatus.CREATED).body(price);
    }

    // PUT - full update
    @PutMapping("/{id}")
    public ResponseEntity<Price> updatePrice(@PathVariable Long id, @RequestBody Price price) {
        if (!prices.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        if (price.getItem() != null && price.getItem().getId() != null 
            && !ItemController.items.containsKey(price.getItem().getId())) {
            return ResponseEntity.notFound().build();
        }
        price.setId(id);
        prices.put(id, price);
        return ResponseEntity.ok(price);
    }

    // PATCH - partial update
    @PatchMapping("/{id}")
    public ResponseEntity<Price> patchPrice(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        Price price = prices.get(id);
        if (price == null) {
            return ResponseEntity.notFound().build();
        }

        if (updates.containsKey("price")) {
            Object priceValue = updates.get("price");
            double newPrice = ((Number) priceValue).doubleValue();
            price.setPrice(newPrice);
        }

        if (updates.containsKey("itemId")) {
            Object itemIdValue = updates.get("itemId");

            Long itemId = ((Number) itemIdValue).longValue();
            if (!ItemController.items.containsKey(itemId)) {
                return ResponseEntity.notFound().build();
            }
            Item item = ItemController.items.get(itemId);
            price.setItem(item);
        }

        prices.put(id, price);
        return ResponseEntity.ok(price);
    }

    // DELETE single price
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePrice(@PathVariable Long id) {
        if (!prices.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        // BUG: Delete always throws server error (same pattern as items)
//        return ResponseEntity.internalServerError().body(null);
         prices.remove(id);
         return ResponseEntity.noContent().build();
    }

    // DELETE all prices
    @DeleteMapping
    public ResponseEntity<Void> deleteAllPrices() {
        prices.clear();
        return ResponseEntity.noContent().build();
    }
}
