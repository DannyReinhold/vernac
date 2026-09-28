package org.vernac.compiler.ast;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.vernac.compiler.parser.VernacBaseVisitor;
import org.vernac.compiler.parser.VernacParser;

import java.util.*;

public class AstBuilderVisitor extends VernacBaseVisitor<AstNode> {

    @Override
    public CompilationUnitNode visitCompilationUnit(VernacParser.CompilationUnitContext ctx) {
        Optional<String> packageName = Optional.ofNullable(ctx.packageDeclaration())
                .map(VernacParser.PackageDeclarationContext::qualifiedName)
                .map(ParserRuleContext::getText);

        List<String> imports = ctx.importDeclaration().stream()
                .map(i -> i.qualifiedName().getText() + (i.getText().contains(".*") ? ".*" : ""))
                .toList();

        List<TopLevelDefinition> definitions = ctx.topLevelDeclaration().stream()
                .map(this::toTopLevelDefinition)
                .filter(Objects::nonNull)
                .toList();

        return new CompilationUnitNode(toLocation(ctx), packageName, imports, definitions);
    }

    private TopLevelDefinition toTopLevelDefinition(VernacParser.TopLevelDeclarationContext ctx) {
        if (ctx.valueDefinition() != null) return visitValueDefinition(ctx.valueDefinition());
        if (ctx.aggregateDefinition() != null) return visitAggregateDefinition(ctx.aggregateDefinition());
        if (ctx.eventDefinition() != null) return visitEventDefinition(ctx.eventDefinition());
        if (ctx.serviceDefinition() != null) return visitServiceDefinition(ctx.serviceDefinition());
        return null;
    }

    @Override
    public ValueObjectNode visitValueDefinition(VernacParser.ValueDefinitionContext ctx) {
        String name = ctx.name.getText();
        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(this::extractParameters)
                .orElse(Collections.emptyList());

        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = valCtx.condition.getText();
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        Optional<CollectionDefinitionNode> collection = Optional.empty();
        if (ctx.getText().contains("collection")) {
            Optional<String> collectionName = Optional.ofNullable(ctx.collectionName).map(ParserRuleContext::getText);
            List<MethodNode> methods = ctx.methodDefinition().stream()
                    .map(this::toMethodNode)
                    .toList();
            collection = Optional.of(new CollectionDefinitionNode(toLocation(ctx), collectionName, methods));
        }

        return new ValueObjectNode(toLocation(ctx), name, fields, validations, collection);
    }

    @Override
    public AggregateNode visitAggregateDefinition(VernacParser.AggregateDefinitionContext ctx) {
        String name = ctx.name.getText();

        // 1. Id Definition parsen
        VernacParser.IdDefinitionContext idCtx = ctx.idDefinition();
        TypeNode idType = toTypeNode(idCtx.idType);
        String idFieldName = idCtx.name != null ? idCtx.name.getText() : "id";
        IdDefinitionNode idDef = new IdDefinitionNode(toLocation(idCtx), idType, idFieldName);

        // 2. Parameter-Felder parsen
        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(this::extractParameters)
                .orElse(Collections.emptyList());

        // 3. Validierungsregeln parsen
        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = valCtx.condition.getText();
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        // 4. Methoden parsen
        List<MethodNode> methods = Optional.ofNullable(ctx.blockBody())
                .map(body -> body.methodDefinition().stream()
                        .map(this::toMethodNode)
                        .toList())
                .orElse(Collections.emptyList());

        return new AggregateNode(toLocation(ctx), name, idDef, fields, validations, methods);
    }

    private MethodNode toMethodNode(VernacParser.MethodDefinitionContext ctx) {
        String access = Optional.ofNullable(ctx.accessModifier()).map(ParserRuleContext::getText).orElse("public");
        TypeNode returnType = toTypeNode(ctx.returnType);
        String name = ctx.name.getText();
        List<FieldNode> parameters = Optional.ofNullable(ctx.parameterList())
                .map(this::extractParameters)
                .orElse(Collections.emptyList());
        String body = ctx.rawJavaBlock().getText().trim();

        return new MethodNode(toLocation(ctx), access, returnType, name, parameters, body);
    }

    @Override
    public EventNode visitEventDefinition(VernacParser.EventDefinitionContext ctx) {
        String name = ctx.name.getText();
        List<AnnotationNode> annotations = ctx.annotation().stream().map(this::toAnnotationNode).toList();
        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(this::extractParameters)
                .orElse(Collections.emptyList());

        return new EventNode(toLocation(ctx), name, annotations, fields);
    }

    @Override
    public ServiceNode visitServiceDefinition(VernacParser.ServiceDefinitionContext ctx) {
        String name = ctx.name.getText();
        List<AnnotationNode> annotations = ctx.annotation().stream().map(this::toAnnotationNode).toList();
        List<ExternalSchemaNode> schemas = new ArrayList<>();
        List<ServiceMethodNode> methods = new ArrayList<>();

        for (VernacParser.ServiceMemberContext member : ctx.serviceMember()) {
            if (member.externalSchemaDefinition() != null) {
                var sCtx = member.externalSchemaDefinition();
                Map<String, TypeNode> schemaFields = new LinkedHashMap<>();
                for (var fCtx : sCtx.schemaField()) {
                    schemaFields.put(fCtx.name.getText(), toTypeNode(fCtx.type()));
                }
                schemas.add(new ExternalSchemaNode(toLocation(sCtx), sCtx.name.getText(), schemaFields));
            } else if (member.serviceMethodDefinition() != null) {
                methods.add(toServiceMethodNode(member.serviceMethodDefinition()));
            }
        }

        return new ServiceNode(toLocation(ctx), name, annotations, schemas, methods);
    }

    private ServiceMethodNode toServiceMethodNode(VernacParser.ServiceMethodDefinitionContext ctx) {
        String fullHttp = ctx.httpAnnotation().getText();
        int firstParen = fullHttp.indexOf('(');
        String httpMethod = fullHttp.substring(1, firstParen);
        String path = unquote(ctx.httpAnnotation().STRING_LITERAL().getText());

        TypeNode returnType = toTypeNode(ctx.returnType);
        String name = ctx.name.getText();
        List<FieldNode> params = Optional.ofNullable(ctx.parameterList())
                .map(this::extractParameters)
                .orElse(Collections.emptyList());

        List<String> thrown = Optional.ofNullable(ctx.throwsClause())
                .map(t -> t.qualifiedName().stream().map(ParserRuleContext::getText).toList())
                .orElse(Collections.emptyList());

        List<MappingStatementNode> mappings = new ArrayList<>();
        if (ctx.mappingBlock() != null) {
            for (var mCtx : ctx.mappingBlock().mappingStatement()) {
                mappings.add(new MappingStatementNode(toLocation(mCtx), mCtx.sourcePath().getText(), mCtx.targetPath().getText()));
            }
        }

        return new ServiceMethodNode(toLocation(ctx), httpMethod, path, returnType, name, params, thrown, mappings);
    }

    private AnnotationNode toAnnotationNode(VernacParser.AnnotationContext ctx) {
        String name = ctx.name.getText();
        if (ctx.annotationArgumentList() == null) {
            return new AnnotationNode(toLocation(ctx), name, Optional.empty(), Map.of());
        }

        var argList = ctx.annotationArgumentList();
        if (argList.annotationValue() != null) {
            return new AnnotationNode(toLocation(ctx), name, Optional.of(unquote(argList.annotationValue().getText())), Map.of());
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        for (var pair : argList.annotationPair()) {
            attributes.put(pair.key.getText(), unquote(pair.value.getText()));
        }
        return new AnnotationNode(toLocation(ctx), name, Optional.empty(), attributes);
    }

    private List<FieldNode> extractParameters(VernacParser.ParameterListContext ctx) {
        return ctx.parameter().stream()
                .map(p -> {
                    boolean isMut = p.isMut != null;
                    return new FieldNode(toLocation(p), toTypeNode(p.paramType), p.name.getText(), isMut);
                })
                .toList();
    }

    private TypeNode toTypeNode(VernacParser.TypeContext ctx) {
        String typeName = ctx.rawType.getText();
        List<TypeNode> typeArgs = ctx.typeArguments() != null
                ? ctx.typeArguments().type().stream().map(this::toTypeNode).toList()
                : Collections.emptyList();
        boolean isOptional = ctx.isOptional != null;

        return new TypeNode(toLocation(ctx), typeName, typeArgs, isOptional);
    }

    private SourceLocation toLocation(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        if (start != null) {
            return new SourceLocation(start.getLine(), start.getCharPositionInLine() + 1);
        }
        return SourceLocation.UNKNOWN;
    }

    private String unquote(String text) {
        if (text != null && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}