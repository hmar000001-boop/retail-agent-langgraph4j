package com.example.retail.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** In-memory catalog (the system of record). The vector store only indexes its text. */
@Component
public class ProductCatalog {
    private final List<Product> products = List.of(
            new Product("p-101", "Trail Runner X", "footwear", "lightweight trail running shoes with aggressive grip for muddy mountain trails", 89.0, 12),
            new Product("p-102", "Road Glide 3", "footwear", "cushioned road running shoes for marathon training on asphalt", 129.0, 5),
            new Product("p-103", "Peak Trail GTX", "footwear", "waterproof trail running shoes with rock plate for technical trails", 149.0, 4),
            new Product("p-201", "Fit Tracker Pro", "wearables", "gps fitness tracker with heart rate for running and cycling", 99.0, 20),
            new Product("p-202", "Hydration Vest 5L", "accessories", "running hydration vest with soft flasks for long trail runs", 59.0, 8),
            new Product("p-301", "Merino Run Socks", "apparel", "cushioned merino wool running socks that prevent blisters", 18.0, 50),
            new Product("p-302", "Rain Shell Jacket", "apparel", "packable waterproof running jacket for rainy weather", 119.0, 7),
            new Product("p-401", "Yoga Mat Grip", "fitness", "non slip yoga mat for home workouts", 35.0, 15),
            new Product("p-402", "Adjustable Dumbbell Set", "fitness", "adjustable dumbbells for strength training at home", 199.0, 3),
            new Product("p-501", "Compact Espresso Maker", "kitchen", "compact espresso machine for small kitchens", 149.0, 6));

    public List<Product> all() {
        return products;
    }

    public Optional<Product> byId(String id) {
        return products.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
