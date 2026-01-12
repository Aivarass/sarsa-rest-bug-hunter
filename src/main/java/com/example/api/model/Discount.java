package com.example.api.model;

public class Discount {

    private Long id;
    private Price price;
    private double discount;

    public Discount(Long id, Price price, double discount) {
        this.id = id;
        this.price = price;
        this.discount = discount;
    }

    public Discount() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Price getPrice() {
        return price;
    }

    public void setPrice(Price price) {
        this.price = price;
    }

    public double getDiscount() {
        return discount;
    }

    public void setDiscount(double discount) {
        this.discount = discount;
    }
}
