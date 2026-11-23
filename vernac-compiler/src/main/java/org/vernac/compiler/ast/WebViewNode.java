// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.ast;

import java.util.List;
import java.util.Optional;

public record WebViewNode(SourceLocation location, String name, Optional<FieldNode> model,
                          boolean component, String title, Optional<String> layout, Optional<String> theme, List<Element> elements) implements TopLevelDefinition {
    public static final String TEMPLATE_PREFIX = "vernac-generated/";

    public static String template(String namespace, String name) {
        return TEMPLATE_PREFIX + namespace.replace('.', '/') + "/" + name;
    }

    public sealed interface Element permits Text, Repeat, Form, Include { SourceLocation location(); }
    public record Include(SourceLocation location, String component, String argument) implements Element {}
    public record Text(SourceLocation location, boolean heading, Optional<String> literal,
                       Optional<String> path) implements Element {}
    public record Repeat(SourceLocation location, String path, String item, List<Element> body,
                         List<Element> empty) implements Element {}
    public record Form(SourceLocation location, String adapter, String endpoint,
                       List<Control> controls) implements Element {}
    public record Control(SourceLocation location, Optional<String> field, String label,
                          Optional<String> variant) {}
}
