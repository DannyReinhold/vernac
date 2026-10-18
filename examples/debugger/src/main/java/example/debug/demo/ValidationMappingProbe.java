// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.debug.demo;

import example.debug.domain.*;
import example.debug.usecase.FormatLabel;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.events.EventHandler;

/** Isolated validation/debugging probe, with no Spring transaction interception or persistence. */
public final class ValidationMappingProbe {
    public static void main(String[] args) {
        String kind = args.length == 0 ? "value" : args[0];
        boolean invalid = args.length > 1 && args[1].equals("invalid");
        int amount = invalid ? 11 : 2;
        var label = Label.of(invalid ? " " : "parcel");
        try {
            switch (kind) {
                case "value" -> Units.of(invalid ? 0 : 2);
                case "entity" -> Parcel.create(label, Units.of(amount));
                case "aggregate" -> Batch.create(Units.of(amount));
                case "usecase" ->
                        new FormatLabel(
                                        new DefaultListableBeanFactory()
                                                .getBeanProvider(EventHandler.class))
                                .execute(label, Label.of("!"));
                case "service" -> new LabelPreparation().prepare(label, Label.of("!"));
                default -> throw new IllegalArgumentException("Unknown validation probe: " + kind);
            }
            if (invalid) throw new AssertionError("Expected a validation failure: " + kind);
            System.out.println("Validation passed: " + kind);
        } catch (DomainValidationException expected) {
            if (!invalid) throw expected;
            System.out.println("Expected validation failure: " + expected.getMessage());
        }
    }
}
