// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.application;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ActivationPlanTest {
    private ActivationPlan.Node function(String name,String module,String... dependencies) {
        return new ActivationPlan.Node(name,module,"USE_CASE",name,List.of(dependencies));
    }
    @Test void activatesRequiredFunctionsButNotPortOwnerOrUnrelatedModules() {
        var nodes=List.of(function("b.Execute","billing","c.Helper"),function("c.Helper","helpers"),function("unused.Execute","unused"),new ActivationPlan.Node("p.Noop","providers","OUTBOUND_ADAPTER","p.Noop",List.of("b.Port")));
        var plan=ActivationPlan.resolve(nodes,List.of("billing"),List.of("p.Noop"),List.of("b.Execute"));
        assertEquals(List.of("helpers","billing"),plan.modules());assertEquals(2,plan.beans().size());
        assertTrue(ActivationPlan.resolve(nodes,List.of(),List.of("p.Noop"),List.of()).modules().isEmpty());
    }
    @Test void selectedAdapterActivatesFunctionalDependencies() {
        var nodes=List.of(function("b.Execute","billing"),new ActivationPlan.Node("p.Remote","providers","OUTBOUND_ADAPTER","p.Remote",List.of("b.Execute")));
        assertEquals(List.of("billing"),ActivationPlan.resolve(nodes,List.of(),List.of("p.Remote"),List.of("b.Execute")).modules());
    }
    @Test void reportsCyclesMissingActivationAndUnknownRoots() {
        var nodes=List.of(function("b.Execute","billing","c.Helper"),function("c.Helper","helpers","b.Execute"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ActivationPlan.resolve(nodes,List.of("billing"),List.of(),List.of())).getMessage().contains("cycle"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ActivationPlan.resolve(nodes,List.of(),List.of(),List.of("b.Execute"))).getMessage().contains("activate billing;"));
        assertThrows(IllegalArgumentException.class,()->ActivationPlan.resolve(nodes,List.of("unknown"),List.of(),List.of()));
    }
    @Test void persistentImportsAreHarmlessButActivationAndSelectedAdaptersRemainBlocked() {
        var repository=new ActivationPlan.Node("b.Repository","billing","REPOSITORY","b.adapter.outbound.jdbc.JdbcRepository",List.of(),true);
        var adapter=new ActivationPlan.Node("b.Adapter","billing","OUTBOUND_ADAPTER","b.adapter.outbound.Adapter",List.of(),true);
        var nodes=List.of(repository,adapter);
        assertTrue(ActivationPlan.resolve(nodes,List.of(),List.of(),List.of()).modules().isEmpty());
        assertTrue(assertThrows(IllegalArgumentException.class,()->ActivationPlan.resolve(nodes,List.of("billing"),List.of(),List.of())).getMessage().contains("migration coordinator"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ActivationPlan.resolve(nodes,List.of(),List.of("b.Adapter"),List.of())).getMessage().contains("migration coordinator"));
        var caller=function("a.Caller","application","b.Repository");
        assertThrows(IllegalArgumentException.class,()->ActivationPlan.resolve(List.of(caller,repository),List.of("application"),List.of(),List.of()));
    }

}
