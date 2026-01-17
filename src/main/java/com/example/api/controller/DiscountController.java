package com.example.api.controller;

import com.example.api.model.Discount;
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
@RequestMapping("/api/discounts")
public class DiscountController {

    public static final Map<Long, Discount> discounts = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    // GET all discounts
    @GetMapping
    public ResponseEntity<List<Discount>> getAllDiscounts() {
        return ResponseEntity.ok(new ArrayList<>(discounts.values()));
    }

    // GET single discount
    @GetMapping("/{id}")
    public ResponseEntity<Discount> getDiscount(@PathVariable Long id) {
        Discount discount = discounts.get(id);
        if (discount == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(discount);
    }


    @PostMapping
    public ResponseEntity<Discount> createDiscount(@RequestBody Discount discount) {
        if (discount.getPrice() == null || discount.getPrice().getId() == null) {
            return ResponseEntity.notFound().build();
        }
        if (!PriceController.prices.containsKey(discount.getPrice().getId())) {
            return ResponseEntity.notFound().build();
        }

//        if (discount.getDiscount() < 0) {
//            return ResponseEntity.internalServerError().body(null);
//        }
        Long id = idCounter.getAndIncrement();
        discount.setId(id);
        discounts.put(id, discount);
        return ResponseEntity.status(HttpStatus.CREATED).body(discount);
    }


    @PutMapping("/{id}")
    public ResponseEntity<Discount> updateDiscount(@PathVariable Long id, @RequestBody Discount discount) {
        if (!discounts.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        if (discount.getPrice() != null && discount.getPrice().getId() != null
            && !PriceController.prices.containsKey(discount.getPrice().getId())) {
            return ResponseEntity.notFound().build();
        }
        discount.setId(id);
        discounts.put(id, discount);
        return ResponseEntity.ok(discount);
    }

    // PATCH - partial update
    @PatchMapping("/{id}")
    public ResponseEntity<Discount> patchDiscount(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        Discount discount = discounts.get(id);
        if (discount == null) {
            return ResponseEntity.notFound().build();
        }

        if (updates.containsKey("discount")) {
            Object priceValue = updates.get("discount");
            double newDiscount = ((Number) priceValue).doubleValue();
            discount.setDiscount(newDiscount);
        }

        if (updates.containsKey("itemId")) {
            Object itemIdValue = updates.get("itemId");

            Long itemId = ((Number) itemIdValue).longValue();
            if (!PriceController.prices.containsKey(itemId)) {
                return ResponseEntity.notFound().build();
            }
            Price price = PriceController.prices.get(itemId);
            discount.setPrice(price);
        }

        discounts.put(id, discount);
        return ResponseEntity.ok(discount);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDiscount(@PathVariable Long id) {
        if (!discounts.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        discounts.remove(id);
//        return ResponseEntity.internalServerError().body(null);
        return ResponseEntity.noContent().build();
    }


    @DeleteMapping
    public ResponseEntity<Void> deleteAllDiscounts() {
        discounts.clear();
        return ResponseEntity.noContent().build();
    }
}
