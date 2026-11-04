package com.example.retail.domain;

public record Product(String id, String name, String category, String description, double price, int stock) {}
