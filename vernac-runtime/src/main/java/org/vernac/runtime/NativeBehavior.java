// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime;

import java.util.List;
import java.util.function.BiConsumer;

import org.vernac.runtime.NativeValidation.Expression;
import org.vernac.runtime.NativeValidation.Invocation;

/** Statement interpreter for compiler-checked Vernac behavior bodies. */
public final class NativeBehavior {
    private static final int FORMAT_VERSION = 1;
    private static final Object CONTINUE = new Object();

    private NativeBehavior() { }

    public sealed interface Step<S> permits Bind, Require, Assign, Send, Return, Branch { }

    public record Bind<S>(int slot, Expression<Invocation<S>> expression) implements Step<S> { }

    public record Require<S>(Expression<Invocation<S>> predicate, String rejection) implements Step<S> { }

    public record Assign<S>(Expression<Invocation<S>> expression, BiConsumer<S, Object> setter) implements Step<S> { }

    public record Send<S>(Expression<Invocation<S>> expression) implements Step<S> { }

    public record Return<S>(Expression<Invocation<S>> expression) implements Step<S> { }

    public record Branch<S>(Expression<Invocation<S>> condition, List<Step<S>> onTrue, List<Step<S>> onFalse)
            implements Step<S> {
        public Branch {
            onTrue = List.copyOf(onTrue);
            onFalse = List.copyOf(onFalse);
        }
    }

    public record Program<S>(int formatVersion, int localCount, List<Step<S>> steps) {
        public Program {
            if (formatVersion != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported Vernac behavior format " + formatVersion
                        + "; expected " + FORMAT_VERSION);
            }
            if (localCount < 0) throw new IllegalArgumentException("Negative Vernac behavior local count");
            steps = List.copyOf(steps);
        }

        public Object execute(S self, List<Object> arguments) {
            return execute(self, arguments, null, 0);
        }

        public Object execute(S self, List<Object> arguments, Class<?> implementation, int program) {
            var result = executeSteps(steps, new Invocation<>(self, arguments, localCount), implementation, program, "");
            return result == CONTINUE ? null : result;
        }
    }

    private static <S> Object executeSteps(List<Step<S>> steps, Invocation<S> invocation,
                                           Class<?> implementation, int program, String prefix) {
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            boolean descend = false;
            try {
                switch (step) {
                    case Bind<S> bind -> invocation.bind(bind.slot(), bind.expression().evaluate(invocation));
                    case Require<S> require -> {
                        if (!(boolean) require.predicate().evaluate(invocation)) {
                            throw new NativeValidation.Rejection(require.rejection());
                        }
                    }
                    case Assign<S> assign -> assign.setter().accept(invocation.self(), assign.expression().evaluate(invocation));
                    case Send<S> send -> send.expression().evaluate(invocation);
                    case Return<S> result -> {
                        return result.expression().evaluate(invocation);
                    }
                    case Branch<S> branch -> {
                        boolean condition = (boolean) branch.condition().evaluate(invocation);
                        var selected = condition ? branch.onTrue() : branch.onFalse();
                        descend = true;
                        var result = executeSteps(selected, invocation, implementation, program,
                                prefix + index + (condition ? "/t/" : "/f/"));
                        if (result != CONTINUE) return result;
                    }
                }
            } catch (RuntimeException failure) {
                if (!descend) NativeBehaviorSources.attach(failure, implementation, program, prefix + index);
                throw failure;
            }
        }
        return CONTINUE;
    }
}
