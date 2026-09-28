package org.vernac.compiler.ast;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.vernac.compiler.parser.VernacBaseVisitor;
import org.vernac.compiler.parser.VernacParser;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class AstBuilderVisitor extends VernacBaseVisitor<AstNode> {

    @Override
    public CompilationUnitNode visitCompilationUnit(VernacParser.CompilationUnitContext ctx) {
        Optional<String> packageName = Optional.ofNullable(ctx.packageDeclaration())
                .map(VernacParser.PackageDeclarationContext::qualifiedName)
                .map(ParserRuleContext::getText);

        List<ValueObjectNode> valueObjects = ctx.valueDefinition().stream()
                .map(this::visitValueDefinition)
                .toList();

        return new CompilationUnitNode(toLocation(ctx), packageName, valueObjects);
    }

    @Override
    public ValueObjectNode visitValueDefinition(VernacParser.ValueDefinitionContext ctx) {
        String name = ctx.name.getText();
        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(this::extractFields)
                .orElse(Collections.emptyList());

        return new ValueObjectNode(toLocation(ctx), name, fields);
    }

    private List<FieldNode> extractFields(VernacParser.ParameterListContext ctx) {
        return ctx.parameter().stream()
                .map(this::toFieldNode)
                .toList();
    }

    private FieldNode toFieldNode(VernacParser.ParameterContext ctx) {
        TypeNode type = toTypeNode(ctx.type());
        String name = ctx.name.getText();
        return new FieldNode(toLocation(ctx), type, name);
    }

    private TypeNode toTypeNode(VernacParser.TypeContext ctx) {
        String typeName = ctx.rawType.getText();
        List<TypeNode> typeArguments = ctx.typeArguments() != null
                ? ctx.typeArguments().type().stream().map(this::toTypeNode).toList()
                : Collections.emptyList();

        return new TypeNode(toLocation(ctx), typeName, typeArguments);
    }

    private SourceLocation toLocation(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        if (start != null) {
            return new SourceLocation(start.getLine(), start.getCharPositionInLine() + 1);
        }
        return SourceLocation.UNKNOWN;
    }
}