// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.authorization;

import java.util.*;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** Startup contract inspection only. Never invokes an actor resolver, policy or usecase. */
@NullMarked
public final class UseCaseAccessAudit {
    private UseCaseAccessAudit() {}
    public record Entry(String identity,Class<?> type,String mode) {
        public Entry {
            Objects.requireNonNull(identity);Objects.requireNonNull(type);Objects.requireNonNull(mode);
            if(!Set.of("OMITTED","PUBLIC","AUTHENTICATED","PERMISSION","ALL","ANY").contains(mode))
                throw new IllegalArgumentException("Unsupported usecase access mode");
        }
    }
    public static void verify(ConfigurableListableBeanFactory factory,List<Entry> entries) {
        var inventory=List.copyOf(entries);
        var known=new HashSet<String>();var missing=new TreeSet<String>();
        for(var entry:inventory) {
            if(!known.add(entry.identity()))throw new IllegalStateException("Duplicate usecase access inventory: "+entry.identity());
            // include lazy definitions, but do not instantiate beans/FactoryBeans to discover types.
            if(entry.mode().equals("OMITTED") && factory.getBeanNamesForType(entry.type(),true,false).length>0)
                missing.add(entry.identity());
        }
        if(!missing.isEmpty())throw failure(missing);
        var omitted=inventory.stream().filter(e->e.mode().equals("OMITTED")).toList();
        if(!omitted.isEmpty())factory.addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String beanName) {
                var late=new TreeSet<String>();
                omitted.stream().filter(e->e.type().isInstance(bean)).forEach(e->late.add(e.identity()));
                if(!late.isEmpty())throw failure(late);
                return bean;
            }
        });
    }
    private static IllegalStateException failure(Set<String> missing) {
        return new IllegalStateException("Vernac explicit authorization audit failed. Registered usecases without a requires clause: "+
                String.join(", ",missing)+". Declare requires public, requires authenticated or an explicit permission requirement.");
    }
}
