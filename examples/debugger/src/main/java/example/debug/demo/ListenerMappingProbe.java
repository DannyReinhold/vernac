// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.debug.demo;

import example.debug.adapter.inbound.event.DeliverLabel;
import example.debug.adapter.inbound.event.ObserveLabel;
import example.debug.domain.Label;
import example.debug.domain.LabelPrepared;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.vernac.runtime.events.EventHandler;

/**
 * Source-mapping probe only. Invokes real generated handlers directly, synchronously or on a
 * separate thread. It does not publish events, persist deliveries, open database transactions or
 * exercise the JDBC worker. Use persistence-demo for the complete delivery lifecycle.
 */
public final class ListenerMappingProbe {
    public static void main(String[] args) throws Exception {
        var factory = new DefaultListableBeanFactory();
        var handlers = factory.getBeanProvider(EventHandler.class);
        var event = LabelPrepared.create(Label.of("parcel"));
        var synchronous = new ObserveLabel(handlers);
        var deferred = new DeliverLabel(handlers);
        if (synchronous.deferred() || !deferred.deferred())
            throw new AssertionError("Delivery modes");
        if (args.length > 0 && args[0].equals("background")) {
            try (var executor =
                    java.util.concurrent.Executors.newSingleThreadExecutor(
                            task -> new Thread(task, "vernac-listener-mapping-probe"))) {
                executor.submit(() -> deferred.handle(event)).get();
            }
        } else runSynchronous(synchronous, event);
    }

    private static void runSynchronous(ObserveLabel listener, LabelPrepared event) {
        listener.handle(event);
    }
}
