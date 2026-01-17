package com.example.api.controller;

import com.example.api.model.Discount;
import com.example.api.model.Point;
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
            return ResponseEntity.notFound().build();
        }
        if (!DiscountController.discounts.containsKey(point.getDiscount().getId())) {
            return ResponseEntity.notFound().build();
        }

//        if (point.getPoints() < 0) {
//            return ResponseEntity.internalServerError().body(null);
//        }
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

        if (updates.containsKey("point")) {
            Object pointValue = updates.get("point");
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
        if (point.getDiscount().getPrice().getPrice() < 0){
            return ResponseEntity.internalServerError().body(null);
        }
//        return ResponseEntity.internalServerError().body(null);
        points.remove(id);
        return ResponseEntity.noContent().build();
    }



    @DeleteMapping
    public ResponseEntity<Void> deleteAllPoints() {
        points.clear();
        return ResponseEntity.noContent().build();
    }
}
