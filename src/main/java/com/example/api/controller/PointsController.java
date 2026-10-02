package com.example.api.controller;

import com.example.api.model.Discount;
import com.example.api.model.Point;
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
@RequestMapping("/api/points")
public class PointsController {

    private final Map<Long, Point> points = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    // GET all points
    @GetMapping
    public ResponseEntity<List<Point>> getAllPoints() {
        return ResponseEntity.ok(new ArrayList<>(points.values()));
    }

    // GET single point
    @GetMapping("/{id}")
    public ResponseEntity<Point> getPoints(@PathVariable Long id) {
        Point point = points.get(id);
        if (point == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(point);
    }


    @PostMapping
    public ResponseEntity<Point> createPoints(@RequestBody Point point) {
        if (point.getDiscount() == null || point.getDiscount().getId() == null) {
            return ResponseEntity.internalServerError().body(null);
        }
        if (!DiscountController.discounts.containsKey(point.getDiscount().getId())) {
            return ResponseEntity.notFound().build();
        }

        if (point.getPoints() < 0) {
            return ResponseEntity.internalServerError().body(null);
        }
        // Link the STORED discount, not the client's {"id":N} stub.
        point.setDiscount(DiscountController.discounts.get(point.getDiscount().getId()));
        Long id = idCounter.getAndIncrement();
        point.setId(id);
        points.put(id, point);
        return ResponseEntity.status(HttpStatus.CREATED).body(point);
    }


    @PutMapping("/{id}")
    public ResponseEntity<Point> updatePoints(@PathVariable Long id, @RequestBody Point point) {
        if (!points.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        if (point.getDiscount() != null && point.getDiscount().getId() != null
            && !DiscountController.discounts.containsKey(point.getDiscount().getId())) {
            return ResponseEntity.notFound().build();
        }
        point.setId(id);
        points.put(id, point);
        return ResponseEntity.ok(point);
    }

    // PATCH - partial update
    @PatchMapping("/{id}")
    public ResponseEntity<Point> patchPoints(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        Point point = points.get(id);
        if (point == null) {
            return ResponseEntity.notFound().build();
        }

        // Field is "points" on the model and in every generated payload; "point" made
        // this branch unreachable, so PATCH /points was a guaranteed no-op.
        if (updates.containsKey("points")) {
            Object pointValue = updates.get("points");
            int newPoints = ((Number) pointValue).intValue();
            point.setPoints(newPoints);
        }

        if (updates.containsKey("discountId")) {
            Object itemIdValue = updates.get("discountId");

            Long discountId = ((Number) itemIdValue).longValue();
            if (!DiscountController.discounts.containsKey(discountId)) {
                return ResponseEntity.notFound().build();
            }
            Discount discount = DiscountController.discounts.get(discountId);
            point.setDiscount(discount);
        }

        points.put(id, point);
        return ResponseEntity.ok(point);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePoints(@PathVariable Long id) {
        if (!points.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        Point point = points.get(id);
        Discount linkedDiscount = point.getDiscount();
        Price linkedPrice = linkedDiscount == null ? null : linkedDiscount.getPrice();
        boolean negativePrice = linkedPrice != null && linkedPrice.getPrice() < 0;

        //sarsa
        // HIDDEN DEFECT 2 (two dependencies): negative linked price AND negative
        // discount. Must be tested BEFORE defect 1 - its condition is a strict subset,
        // so the opposite order makes this branch unreachable dead code.
        if (negativePrice && linkedDiscount.getDiscount() < 0) {
            throw new ArithmeticException("points delete: negative price and negative discount");
        }
        // HIDDEN DEFECT 1 (one dependency): negative linked price.
        if (negativePrice) {
            throw new IllegalStateException("points delete: negative linked price");
        }
        return ResponseEntity.internalServerError().body(null);
    }


    @DeleteMapping
    public ResponseEntity<Void> deleteAllPoints() {
        points.clear();
        return ResponseEntity.noContent().build();
    }
}
