// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.debug.demo;

import example.debug.domain.Label;
import example.debug.usecase.FormatLabel;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.vernac.runtime.events.EventHandler;

/**
 * Isolated debugger probe for generated execute/helper methods. This deliberately calls the
 * generated object directly: it does not start Spring or establish a transaction. Application code
 * must inject its usecase as a Spring bean to obtain REQUIRED transaction semantics.
 */
public final class UsecaseMappingProbe {
    public static void main(String[] args) {
        var factory = new DefaultListableBeanFactory();
        var operation = new FormatLabel(factory.getBeanProvider(EventHandler.class));
        var result = operation.execute(Label.of(" parcel "), Label.of("!"));
        if (!result.string().equals("PARCEL!")) throw new AssertionError(result);
        System.out.println(result.string());
    }
}
