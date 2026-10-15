// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.debug.demo;

import example.debug.domain.Label;
import example.debug.domain.LabelPreparation;

/** Standalone domain service debug probe; no transaction or Spring context is required. */
public final class ServiceMappingProbe {
    public static void main(String[] args) {
        var service = new LabelPreparation();
        var result = service.prepare(Label.of(" parcel "), Label.of("!"));
        if (!result.string().equals("PARCEL!")) throw new AssertionError(result);
        System.out.println(result.string());
    }
}
