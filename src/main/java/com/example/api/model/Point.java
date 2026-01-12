package com.example.api.model;

public class Point {

    private Long id;
    private Discount discount;
    private int points;

    public Point() {
    }

    public Point(Long id, Discount discount, int points) {
        this.id = id;
        this.discount = discount;
        this.points = points;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Discount getDiscount() {
        return discount;
    }

    public void setDiscount(Discount discount) {
        this.discount = discount;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }
}
