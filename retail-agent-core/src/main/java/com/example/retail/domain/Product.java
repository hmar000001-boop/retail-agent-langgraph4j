package com.example.retail.domain;

import java.io.Serializable;

public record Product(String id, String name, String category, String description, double price, int stock)
        implements Serializable {}
