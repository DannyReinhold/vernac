package org.example.tasks;

import org.example.tasks.domain.ProductName;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ProductNameBehavior {

    private ProductNameBehavior() {
    }

    public static String decorated(ProductName self) {
        return "[" + self.string() + "]";
    }
}