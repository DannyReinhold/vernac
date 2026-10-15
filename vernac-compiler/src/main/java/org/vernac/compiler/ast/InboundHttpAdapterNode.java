// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

/** HTTP transport boundary for explicitly injected application usecases. */
public record InboundHttpAdapterNode(
        SourceLocation location,
        String name,
        String base,
        List<FieldNode> dependencies,
        List<Endpoint> endpoints,
        List<SchemaNode> schemas,
        List<MappingNode> mappings,
        Optional<String> errorImplementation,
        List<ErrorRule> errorRules,
        boolean mvc,
        Optional<String> errorView)
        implements TopLevelDefinition {
    public InboundHttpAdapterNode(
            SourceLocation location,
            String name,
            String base,
            List<FieldNode> dependencies,
            List<Endpoint> endpoints,
            List<SchemaNode> schemas,
            List<MappingNode> mappings,
            Optional<String> errorImplementation,
            List<ErrorRule> errorRules) {
        this(
                location,
                name,
                base,
                dependencies,
                endpoints,
                schemas,
                mappings,
                errorImplementation,
                errorRules,
                false,
                Optional.empty());
    }

    public record View(
            String template,
            Optional<String> modelName,
            Optional<String> redirect,
            Optional<String> notice,
            Optional<String> absentView) {}

    public record ErrorView(Optional<String> template, Optional<String> reload) {}

    public record Endpoint(
            SourceLocation location,
            String name,
            String verb,
            String route,
            List<Binding> bindings,
            Optional<TypeNode> bodyType,
            String dependency,
            List<MappingAssignmentNode> arguments,
            int status,
            Optional<TypeNode> responseType,
            Optional<String> responseMapping,
            Optional<Integer> absentStatus,
            Optional<String> errorImplementation,
            List<ErrorRule> errorRules,
            Optional<View> view) {
        public Endpoint(
                SourceLocation location,
                String name,
                String verb,
                String route,
                List<Binding> bindings,
                Optional<TypeNode> bodyType,
                String dependency,
                List<MappingAssignmentNode> arguments,
                int status,
                Optional<TypeNode> responseType,
                Optional<String> responseMapping,
                Optional<Integer> absentStatus,
                Optional<String> errorImplementation,
                List<ErrorRule> errorRules) {
            this(
                    location,
                    name,
                    verb,
                    route,
                    bindings,
                    bodyType,
                    dependency,
                    arguments,
                    status,
                    responseType,
                    responseMapping,
                    absentStatus,
                    errorImplementation,
                    errorRules,
                    Optional.empty());
        }
    }

    public record ErrorRule(
            SourceLocation location,
            String selector,
            Optional<TypeNode> rejectionType,
            String constant,
            String responsibility,
            String category,
            int status,
            Optional<String> code,
            Optional<String> message,
            Optional<ErrorView> view) {
        public ErrorRule(
                SourceLocation location,
                String selector,
                Optional<TypeNode> rejectionType,
                String constant,
                String responsibility,
                String category,
                int status,
                Optional<String> code,
                Optional<String> message) {
            this(
                    location,
                    selector,
                    rejectionType,
                    constant,
                    responsibility,
                    category,
                    status,
                    code,
                    message,
                    Optional.empty());
        }
    }

    public record Binding(
            SourceLocation location,
            String kind,
            TypeNode type,
            String name,
            String externalName,
            Optional<String> defaultValue,
            boolean sensitive) {
        public Binding(
                SourceLocation location,
                String kind,
                TypeNode type,
                String name,
                String externalName) {
            this(location, kind, type, name, externalName, Optional.empty(), false);
        }
    }
}
