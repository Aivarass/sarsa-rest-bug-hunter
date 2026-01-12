package com.example.api.controller;

import com.example.api.model.Item;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    public final static Map<Long, Item> items = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    // GET all items
    @GetMapping
    public ResponseEntity<List<Item>> getAllItems() {
        return ResponseEntity.ok(new ArrayList<>(items.values()));
    }

    // GET single item
    @GetMapping("/{id}")
    public ResponseEntity<Item> getItem(@PathVariable Long id) {
        Item item = items.get(id);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(item);
    }

    // POST - create new item
    @PostMapping
    public ResponseEntity<Item> createItem(@RequestBody Item item) {
        if(item.getQuantity() < 0 ){
            return ResponseEntity.internalServerError().body(null);
        }
        Long id = idCounter.getAndIncrement();
        item.setId(id);
        items.put(id, item);
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    // PUT - full update
    @PutMapping("/{id}")
    public ResponseEntity<Item> updateItem(@PathVariable Long id, @RequestBody Item item) {
        if (!items.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        item.setId(id);
        items.put(id, item);
        return ResponseEntity.ok(item);
    }

    // PATCH - partial update
    @PatchMapping("/{id}")
    public ResponseEntity<Item> patchItem(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        Item item = items.get(id);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }

        if (updates.containsKey("name")) {
            item.setName((String) updates.get("name"));
        }
        if (updates.containsKey("description")) {
            item.setDescription((String) updates.get("description"));
        }
        if (updates.containsKey("quantity")) {
            item.setQuantity((Integer) updates.get("quantity"));
        }

        items.put(id, item);
        return ResponseEntity.ok(item);
    }

    // DELETE single item
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long id) {
        if (!items.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }else{
            return ResponseEntity.internalServerError().body(null);
        }
//        items.remove(id);
//        return ResponseEntity.noContent().build();
    }

    // DELETE all items
    @DeleteMapping
    public ResponseEntity<Void> deleteAllItems() {
        items.clear();
        return ResponseEntity.noContent().build();
    }

    // HEAD - check if item exists
    @RequestMapping(value = "/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headItem(@PathVariable Long id) {
        if (items.containsKey(id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    // OPTIONS - return allowed methods
    @RequestMapping(value = "/{id}", method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> optionsItem() {
        return ResponseEntity.ok()
                .header("Allow", "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS")
                .build();
    }
}
