// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.intellij.debugger.DebuggerContext;
import com.intellij.debugger.engine.JavaValue;
import com.intellij.debugger.engine.evaluation.*;
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl;
import com.intellij.debugger.ui.tree.*;
import com.intellij.debugger.ui.tree.render.*;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.xdebugger.frame.XValueModifier;
import com.sun.jdi.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Presentation only: real facade type, stored readable properties, no new write capability. */
public final class VernacViewRenderer extends ClassRenderer {
    public VernacViewRenderer() {
        setEnabled(true);
        setIsApplicableChecker(t -> CompletableFuture.completedFuture(isApplicable(t)));
    }

    @Override
    public String getUniqueId() {
        return "vernac.access.view";
    }

    @Override
    public String getName() {
        return "Vernac Read/Write/Access views";
    }

    @Override
    public boolean isApplicable(Type type) {
        try {
            return VernacViewInspector.contract(type).isPresent();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static boolean verified(Project project, ReferenceType type) {
        try {
            return verifiedCurrent(project, type);
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean verifiedCurrent(Project project, ReferenceType type) {
        if (VernacDebugSettings.get(project).generatedJava) return false;
        var contract = VernacViewInspector.contract(type);
        if (contract.isEmpty()) return false;
        String owner = contract.get().owner();
        int dot = owner.lastIndexOf('.');
        String prefix = owner.substring(0, dot + 1), name = owner.substring(dot + 1);
        return VernacDebugSources.all(project).stream()
                .anyMatch(
                        b -> {
                            String companion = b.entry().javaClass();
                            return (companion.equals(prefix + "__VernacBehavior_" + name)
                                            || companion.equals(
                                                    prefix + "__VernacValidation_" + name))
                                    && VernacDebugSources.current(b)
                                    && type.virtualMachine().classesByName(companion).stream()
                                            .anyMatch(
                                                    t ->
                                                            Objects.equals(
                                                                            t.classLoader(),
                                                                            type.classLoader())
                                                                    && VernacPositionManager
                                                                            .matches(t, b));
                        });
    }

    @Override
    public String calcLabel(
            ValueDescriptor descriptor, EvaluationContext context, DescriptorLabelListener listener)
            throws EvaluateException {
        String label = super.calcLabel(descriptor, context, listener);
        if (!(descriptor.getValue() instanceof ObjectReference object)
                || !verified(context.getProject(), object.referenceType())) return label;
        var contract = VernacViewInspector.contract(object.referenceType()).orElseThrow();
        return label + " — " + contract.name() + " [" + contract.role() + " view; inspection only]";
    }

    @Override
    public void buildChildren(Value value, ChildrenBuilder builder, EvaluationContext context) {
        if (!(value instanceof ObjectReference object)
                || !verified(context.getProject(), object.referenceType())) {
            super.buildChildren(value, builder, context);
            return;
        }
        var properties = VernacViewInspector.properties(object);
        List<DebuggerTreeNode> nodes = new ArrayList<>();
        for (var property : properties) {
            var descriptor = new StoredProperty(context.getProject(), property);
            nodes.add(builder.getNodeManager().createNode(descriptor, context));
        }
        if (nodes.isEmpty())
            nodes.add(
                    builder.getNodeManager()
                            .createMessageNode(
                                    "No supported stored-property projection; use the raw view"
                                            + " or explicit Evaluate Expression."));
        builder.setChildren(nodes);
    }

    @Override
    public CompletableFuture<Boolean> isExpandableAsync(
            Value value, EvaluationContext context, NodeDescriptor parent) {
        if (value instanceof ObjectReference object
                && verified(context.getProject(), object.referenceType()))
            return CompletableFuture.completedFuture(true);
        return super.isExpandableAsync(value, context, parent);
    }

    @Override
    public PsiElement getChildValueExpression(DebuggerTreeNode node, DebuggerContext context)
            throws EvaluateException {
        if (node.getDescriptor() instanceof StoredProperty property)
            return JavaPsiFacade.getElementFactory(context.getProject())
                    .createExpressionFromText(property.property.expression(), null);
        return super.getChildValueExpression(node, context);
    }

    static final class StoredProperty extends ValueDescriptorImpl {
        private final VernacViewInspector.Property property;

        StoredProperty(Project project, VernacViewInspector.Property property) {
            super(project);
            this.property = property;
            setLvalue(false);
        }

        @Override
        public Value calcValue(EvaluationContextImpl context) {
            return property.value();
        }

        @Override
        public String getName() {
            return property.name() + (property.optional() ? " [optional stored value]" : "");
        }

        @Override
        public String calcValueName() {
            return getName();
        }

        @Override
        public String getDeclaredType() {
            return property.field().typeName();
        }

        @Override
        public boolean canSetValue() {
            return false;
        }

        @Override
        public XValueModifier getModifier(JavaValue value) {
            return null;
        }

        @Override
        public PsiExpression getDescriptorEvaluation(DebuggerContext context) {
            return JavaPsiFacade.getElementFactory(context.getProject())
                    .createExpressionFromText(property.expression(), null);
        }
    }
}
