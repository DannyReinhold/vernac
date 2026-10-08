package org.example.tasks;

import org.example.tasks.domain.ProductName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductNameBehaviorTest {

    @Test
    void delegatesToExternalBehavior() {
        var name = ProductName.of("Notebook");

        assertEquals("[Notebook]", name.decorated());
    }
}