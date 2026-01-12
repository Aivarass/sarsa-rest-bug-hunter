package com.example.api.model;

public class Price {

    private Long id;
    private Item item;
    private double price;

    // Default constructor for Jackson
    public Price() {}

    public Price(Long id, Item item, double price) {
        this.id = id;
        this.item = item;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }


}
