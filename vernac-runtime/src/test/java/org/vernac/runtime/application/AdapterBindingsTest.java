// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.application;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
class AdapterBindingsTest {
    interface Payments {String provider();}
    public static class Noop implements Payments {public String provider(){return "noop";}}
    public static class Remote implements Payments {
        static final AtomicInteger creations=new AtomicInteger();
        public Remote(org.springframework.core.env.Environment environment) {creations.incrementAndGet();if(!environment.containsProperty("remote.api-key"))throw new IllegalStateException("Missing remote config");}
        public String provider(){return "remote";}
    }
    private AnnotationConfigApplicationContext context(Map<String,Object> properties) {
        var context=new AnnotationConfigApplicationContext();context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",properties));
        context.addBeanFactoryPostProcessor(new AdapterBindings(context.getEnvironment(),List.of(new AdapterBindings.Binding(Payments.class,"example.Payments","payment-provider",null)),Map.of("example.Noop",Noop.class,"example.Remote",Remote.class)));
        return context;
    }
    @Test void selectsOnlyConfiguredProviderAndRequiresOnlyItsConfiguration() {
        Remote.creations.set(0);
        try(var local=context(Map.of("payment-provider","example.Noop"))) {local.refresh();assertEquals("noop",local.getBean(Payments.class).provider());assertEquals(0,Remote.creations.get());assertEquals(1,local.getBeansOfType(Payments.class).size());}
        try(var production=context(Map.of("payment-provider","example.Remote","remote.api-key","test-value"))) {production.refresh();assertEquals("remote",production.getBean(Payments.class).provider());assertEquals(1,Remote.creations.get());}
        try(var broken=context(Map.of("payment-provider","example.Remote"))) {assertThrows(RuntimeException.class,broken::refresh);}
    }
    @Test void missingUnknownAndCompetingProvidersFailWithoutEchoingSelection() {
        for(var properties:List.<Map<String,Object>>of(Map.of(),Map.of("payment-provider","not-an-export"))) {
            try(var context=context(properties)) {var e=assertThrows(RuntimeException.class,context::refresh);assertFalse(e.getMessage().contains("not-an-export"));}
        }
        try(var context=context(Map.of("payment-provider","example.Noop"))) {
            context.registerBean("competitor",Noop.class);assertThrows(RuntimeException.class,context::refresh);
        }
    }
    @Test void fixedBindingNeedsNoPropertyAndWrongTypeSelectionIsRejected() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.addBeanFactoryPostProcessor(new AdapterBindings(context.getEnvironment(),
                    List.of(new AdapterBindings.Binding(Payments.class,"example.Payments",null,"example.Noop")),Map.of("example.Noop",Noop.class)));
            context.refresh();assertEquals("noop",context.getBean(Payments.class).provider());
        }
        try(var context=new AnnotationConfigApplicationContext()) {
            context.addBeanFactoryPostProcessor(new AdapterBindings(context.getEnvironment(),
                    List.of(new AdapterBindings.Binding(Payments.class,"example.Payments",null,"example.Wrong")),Map.of("example.Wrong",String.class)));
            assertThrows(RuntimeException.class,context::refresh);
        }
    }
}
