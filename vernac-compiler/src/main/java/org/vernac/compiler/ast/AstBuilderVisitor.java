// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.vernac.compiler.parser.VernacBaseVisitor;
import org.vernac.compiler.parser.VernacParser;

import java.util.*;

public class AstBuilderVisitor extends VernacBaseVisitor<AstNode> {
    private final String sourceName;

    public AstBuilderVisitor() {
        this("<memory>");
    }

    public AstBuilderVisitor(String sourceName) {
        this.sourceName = Objects.requireNonNull(sourceName);
    }


    @Override
    public CompilationUnitNode visitCompilationUnit(VernacParser.CompilationUnitContext ctx) {
        String namespace = ctx.namespaceDeclaration().qualifiedName().getText();

        List<ImportNode> imports = ctx.importDeclaration().stream()
                .map(i -> new ImportNode(toLocation(i), i.qualifiedName().getText(), i.getText().endsWith(".*;")))
                .toList();

        List<TopLevelDefinition> definitions = ctx.topLevelDeclaration().stream()
                .map(this::toTopLevelDefinition)
                .filter(Objects::nonNull)
                .toList();

        var terms=ctx.topLevelDeclaration().stream().map(VernacParser.TopLevelDeclarationContext::termDefinition)
            .filter(Objects::nonNull).map(t->org.vernac.compiler.vocabulary.Vocabulary.parse(sourceName,t)).toList();
        return new CompilationUnitNode(toLocation(ctx.namespaceDeclaration()), namespace, imports, definitions,terms);
    }

    private TopLevelDefinition toTopLevelDefinition(VernacParser.TopLevelDeclarationContext ctx) {
        if (ctx.idDeclaration() != null) return visitIdDeclaration(ctx.idDeclaration());
        if (ctx.valueDefinition() != null) return visitValueDefinition(ctx.valueDefinition());
        if (ctx.aggregateDefinition() != null) return visitAggregateDefinition(ctx.aggregateDefinition());
        if (ctx.entityDefinition() != null) return visitEntityDefinition(ctx.entityDefinition());
        if (ctx.eventDefinition() != null) return visitEventDefinition(ctx.eventDefinition());
        if (ctx.inboundHttpAdapterDefinition() != null) return visitInboundHttpAdapterDefinition(ctx.inboundHttpAdapterDefinition());
        if (ctx.outboundAdapterDefinition() != null) return visitOutboundAdapterDefinition(ctx.outboundAdapterDefinition());
        if (ctx.portDefinition() != null) return visitPortDefinition(ctx.portDefinition());
        if (ctx.repositoryDefinition() != null) return visitRepositoryDefinition(ctx.repositoryDefinition());
        if (ctx.usecaseDefinition() != null) return visitUsecaseDefinition(ctx.usecaseDefinition());
        if (ctx.domainServiceDefinition() != null) return visitDomainServiceDefinition(ctx.domainServiceDefinition());
        if (ctx.listenerDefinition() != null) return visitListenerDefinition(ctx.listenerDefinition());
        return null;
    }

    @Override
    public IdDeclarationNode visitIdDeclaration(VernacParser.IdDeclarationContext ctx) {
        String name = ctx.name.getText();
        return new IdDeclarationNode(toLocation(ctx), name, collections(ctx.collectionDefinition()));
    }

    @Override
    public ValueObjectNode visitValueDefinition(VernacParser.ValueDefinitionContext ctx) {
        String name = ctx.name.getText();

        // 1. Parameter für normale Value Objects
        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p))
                .orElse(Collections.emptyList());

        // 2. Enum-Konstanten (falls vorhanden)
        List<EnumConstantNode> enumConstants = new ArrayList<>();
        if (ctx.enumConstantList() != null) {
            for (VernacParser.EnumConstantContext ecCtx : ctx.enumConstantList().enumConstant()) {
                String constName = ecCtx.name.getText();
                enumConstants.add(new EnumConstantNode(toLocation(ecCtx), constName));
            }
        }

        // 3. Validierungen
        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = extractRawSource(valCtx.condition);
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        List<MethodNode> voMethods = behaviorMethods(ctx.behaviorBlock());

        List<CollectionDefinitionNode> collections = collections(ctx.collectionDefinition());

        return new ValueObjectNode(
                toLocation(ctx),
                name,
                fields,
                enumConstants,
                validations,
                voMethods,
                collections,
                behaviorImports(ctx.behaviorBlock())
        );
    }

    private List<JavaImportNode> behaviorImports(VernacParser.BehaviorBlockContext ctx) {
        if (ctx == null || ctx.javaImports() == null) return List.of();
        return ctx.javaImports().qualifiedName().stream()
                .map(name -> new JavaImportNode(toLocation(name), name.getText())).toList();
    }

    private List<MethodNode> behaviorMethods(VernacParser.BehaviorBlockContext ctx) {
        if (ctx == null) return List.of();
        return ctx.behaviorMethod().stream().map(method -> new MethodNode(toLocation(method),
                method.visibility == null ? "public" : method.visibility.getText(), toTypeNode(method.returnType), method.name.getText(),
                method.parameterList() == null ? List.of() : extractParameters(method.parameterList()),
                method.rawJavaBlock() == null ? "" : extractRawSource(method.rawJavaBlock()),
                Optional.ofNullable(method.implementation).map(ParserRuleContext::getText),
                method.effect == null ? MethodNode.Mode.DEFAULT : MethodNode.Mode.valueOf(method.effect.getText().toUpperCase(java.util.Locale.ROOT)))).toList();
    }

    private List<CollectionDefinitionNode> collections(List<VernacParser.CollectionDefinitionContext> contexts) {
        return contexts.stream().map(ctx -> new CollectionDefinitionNode(toLocation(ctx),
                ctx.kind.getText().equals("list") ? CollectionDefinitionNode.Kind.LIST : CollectionDefinitionNode.Kind.SET,
                Optional.ofNullable(ctx.collectionName).map(ParserRuleContext::getText),
                behaviorMethods(ctx.behaviorBlock()), behaviorImports(ctx.behaviorBlock()))).toList();
    }

    @Override
    public AggregateNode visitAggregateDefinition(VernacParser.AggregateDefinitionContext ctx) {
        String name = ctx.name.getText();

        VernacParser.IdReferenceContext idCtx = ctx.idReference();
        TypeNode idType = toTypeNode(idCtx.idType);
        IdReferenceNode idDef = new IdReferenceNode(toLocation(idCtx), idType);

        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p))
                .orElse(Collections.emptyList());

        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = extractRawSource(valCtx.condition);
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        return new AggregateNode(toLocation(ctx), name, idDef, fields, validations,
                behaviorMethods(ctx.behaviorBlock()), collections(ctx.collectionDefinition()), behaviorImports(ctx.behaviorBlock()));
    }

    @Override
    public EntityNode visitEntityDefinition(VernacParser.EntityDefinitionContext ctx) {
        String name = ctx.name.getText();

        VernacParser.IdReferenceContext idCtx = ctx.idReference();
        TypeNode idType = toTypeNode(idCtx.idType);
        IdReferenceNode idDef = new IdReferenceNode(toLocation(idCtx), idType);

        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p))
                .orElse(Collections.emptyList());

        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = extractRawSource(valCtx.condition);
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        return new EntityNode(toLocation(ctx), name, idDef, fields, validations,
                behaviorMethods(ctx.behaviorBlock()), collections(ctx.collectionDefinition()), behaviorImports(ctx.behaviorBlock()));
    }

    @Override
    public TopLevelDefinition visitRepositoryDefinition(VernacParser.RepositoryDefinitionContext ctx) {
        String aggregateName = ctx.aggregateName.getText();
        String name = ctx.name != null ? ctx.name.getText() : aggregateName + "Repository";

        List<RepositoryMethodNode> methods = new ArrayList<>();
        for (var findCtx : ctx.repositoryFindMethod()) {
            var predicates = new ArrayList<RepositoryMethodNode.Predicate>();
            var condition = findCtx.repositoryExpression() == null
                    ? new RepositoryMethodNode.Junction("and", List.of())
                    : queryExpression(findCtx.repositoryExpression(), predicates);
            methods.add(new RepositoryMethodNode(
                    toLocation(findCtx), toTypeNode(findCtx.returnType), findCtx.name.getText(),
                    findCtx.parameterList() == null ? List.of() : extractParameters(findCtx.parameterList()),
                    predicates, findCtx.repositoryOrder().stream().map(o -> new RepositoryMethodNode.Order(
                            toLocation(o), o.field.getText(), o.direction != null && o.direction.getText().equals("desc"))).toList(),
                    condition, findCtx.pagination == null ? RepositoryMethodNode.Pagination.NONE
                            : RepositoryMethodNode.Pagination.valueOf(findCtx.pagination.getText().toUpperCase(java.util.Locale.ROOT)),
                    findCtx.counting != null));
        }
        return new RepositoryNode(toLocation(ctx), name, aggregateName, methods);
    }

    @Override
    public EventNode visitEventDefinition(VernacParser.EventDefinitionContext ctx) {
        return new EventNode(toLocation(ctx), ctx.name.getText(), ctx.recorded != null,
                ctx.parameterList() == null ? List.of() : extractParameters(ctx.parameterList()),
                ctx.validationBlock() == null ? List.of() : ctx.validationBlock().validationStatement().stream()
                    .map(v -> new ValidationRuleNode(toLocation(v), extractRawSource(v.condition), v.message == null ? "" : unquote(v.message.getText()))).toList());
    }

    @Override
    public PortNode visitPortDefinition(VernacParser.PortDefinitionContext ctx) {
        return new PortNode(ctx.name.getText(), ctx.portMethodDefinition().stream()
                .map(this::visitPortMethodDefinition).toList(), locationOf(ctx));
    }

    @Override
    public InboundHttpAdapterNode visitInboundHttpAdapterDefinition(VernacParser.InboundHttpAdapterDefinitionContext ctx) {
        var endpoints = ctx.inboundEndpoint().stream().map(e -> new InboundHttpAdapterNode.Endpoint(
                toLocation(e), e.name.getText(), e.verb.getText(), unquote(e.route.getText()),
                e.inboundBinding().stream().map(b -> {
                    var type = toTypeNode(b.valueType);
                    String name = b.name == null ? org.vernac.language.VernacNames.defaultMemberName(type.name()) : b.name.getText();
                    return new InboundHttpAdapterNode.Binding(toLocation(b), b.kind.getText(), type, name,
                            b.externalName == null ? name : unquote(b.externalName.getText()));
                }).toList(), Optional.ofNullable(e.bodyType).map(this::toTypeNode), e.dependency.getText(),
                e.mappingAssignment().stream().map(a -> new MappingAssignmentNode(toLocation(a), a.target.getText(),
                        List.of(a.path.getText().split("\\.")), Optional.ofNullable(a.mapper).map(ParserRuleContext::getText))).toList(),
                httpStatus(e.status.getText()), Optional.ofNullable(e.responseType).map(this::toTypeNode),
                Optional.ofNullable(e.responseMapping).map(ParserRuleContext::getText),
                Optional.ofNullable(e.absentStatus).map(t -> httpStatus(t.getText())),
                inboundImplementation(e.inboundErrorPolicy()), inboundRules(e.inboundErrorPolicy()))).toList();
        return new InboundHttpAdapterNode(toLocation(ctx), ctx.name.getText(), unquote(ctx.base.getText()),
                ctx.dependencies.stream().map(this::adapterParameter).toList(), endpoints,
                ctx.schemaDefinition().stream().map(this::visitSchemaDefinition).toList(),
                ctx.mappingDefinition().stream().map(this::visitMappingDefinition).toList(),
                inboundImplementation(ctx.inboundErrorPolicy()), inboundRules(ctx.inboundErrorPolicy()));
    }

    private Optional<String> inboundImplementation(VernacParser.InboundErrorPolicyContext policy) {
        return policy == null ? Optional.empty() : Optional.ofNullable(policy.implementation).map(ParserRuleContext::getText);
    }
    private List<InboundHttpAdapterNode.ErrorRule> inboundRules(VernacParser.InboundErrorPolicyContext policy) {
        if (policy == null) return List.of();
        return policy.inboundErrorRule().stream().map(rule -> {
            var selector = rule.inboundErrorSelector();
            String code = selector.rejectionCode == null ? "" : selector.rejectionCode.getText();
            int dot = code.lastIndexOf('.');
            return new InboundHttpAdapterNode.ErrorRule(toLocation(rule),
                selector.rejectionCode != null ? "rejection" : selector.responsibility != null ? "integration" : selector.getText(),
                dot < 0 ? Optional.empty() : Optional.of(new TypeNode(toLocation(selector.rejectionCode), code.substring(0,dot))),
                dot < 0 ? code : code.substring(dot+1),
                selector.responsibility == null ? "" : selector.responsibility.getText(),
                selector.category == null ? "" : selector.category.getText(), httpStatus(rule.status.getText()),
                Optional.ofNullable(rule.code).map(t -> errorString(t.getText(), toLocation(rule))),
                Optional.ofNullable(rule.message).map(t -> errorString(t.getText(), toLocation(rule))));
        }).toList();
    }
    private int httpStatus(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { return -1; }
    }

    public OutboundAdapterNode visitOutboundAdapterDefinition(VernacParser.OutboundAdapterDefinitionContext ctx) {
        var dependencies = ctx.dependencies.stream().map(this::adapterParameter).toList();
        var technical = ctx.javaUses() == null ? List.<FieldNode>of() :
                ctx.javaUses().dependencies.stream().map(this::adapterParameter).toList();
        var imports = ctx.javaImports() == null ? List.<JavaImportNode>of() : ctx.javaImports().qualifiedName().stream()
                .map(n -> new JavaImportNode(toLocation(n), n.getText())).toList();
        var methods = ctx.serviceMethod().stream().map(m -> new ServiceMethodNode(
                new MethodNode(toLocation(m), m.visibility.getText(), toTypeNode(m.returnType), m.name.getText(),
                    m.parameterList() == null ? List.of() : extractParameters(m.parameterList()),
                    m.rawJavaBlock() == null ? "" : extractRawSource(m.rawJavaBlock()),
                    Optional.ofNullable(m.implementation).map(ParserRuleContext::getText)),
                m.validationBlock() == null ? List.of() : m.validationBlock().validationStatement().stream()
                    .map(v -> new ValidationRuleNode(toLocation(v), extractRawSource(v.condition),
                        v.message == null ? "" : unquote(v.message.getText()))).toList())).toList();
        var configuration = ctx.configurationBlock() == null ? List.<AdapterConfigurationNode>of() :
            ctx.configurationBlock().configurationEntry().stream().map(c -> new AdapterConfigurationNode(
                toLocation(c), c.configType.getText(), c.name.getText(), unquote(c.property.getText()))).toList();
        Optional<RestAdapterNode> rest = Optional.ofNullable(ctx.restBlock()).map(this::rest);
        if (rest.isPresent()) {
            var http = rest.get();
            technical = new ArrayList<>(List.of(
                new FieldNode(http.location(), new TypeNode(http.location(), "org.vernac.runtime.http.HttpAdapterClient"), http.client(), false),
                new FieldNode(http.location(), new TypeNode(http.location(), "java.net.URI"), http.base(), false)));
            if (configuration.stream().anyMatch(c -> c.name().equals(http.base()))) technical.remove(1);
            methods = http.operations().stream().map(o -> new ServiceMethodNode(o.method(), List.of())).toList();
        }
        return new OutboundAdapterNode(toLocation(ctx),ctx.name.getText(),toTypeNode(ctx.portType),
                dependencies,technical,imports,methods,ctx.schemaDefinition().stream().map(this::visitSchemaDefinition).toList(),
                ctx.mappingDefinition().stream().map(this::visitMappingDefinition).toList(), rest, configuration);
    }
    private RestAdapterNode rest(VernacParser.RestBlockContext ctx) {
        return new RestAdapterNode(toLocation(ctx), ctx.client.getText(), ctx.base.getText(),
            ctx.restOperation().stream().map(o -> {
                var method = new MethodNode(toLocation(o), "public", toTypeNode(o.returnType), o.name.getText(),
                    o.parameterList() == null ? List.of() : extractParameters(o.parameterList()), "");
                return new RestAdapterNode.Operation(method, o.verb.getText(), unquote(o.route.getText()),
                    o.restBinding().stream().map(b -> new RestAdapterNode.Binding(toLocation(b), b.kind.getText(),
                        unquote(b.target.getText()), List.of(b.source.getText().split("\\.")))).toList(),
                    Optional.ofNullable(o.requestPath).map(path -> new RestAdapterNode.Body(toLocation(path),
                        List.of(path.getText().split("\\.")), o.requestMapping.getText())),
                    Optional.ofNullable(o.responseType).map(type -> new RestAdapterNode.Response(toTypeNode(type), o.responseMapping.getText())),
                    Optional.ofNullable(o.absentStatus).map(t -> t.getText()),
                    Optional.ofNullable(o.errorImplementation).map(ParserRuleContext::getText),
                    Optional.ofNullable(o.restErrors()).map(e -> new RestAdapterNode.Errors(
                        Optional.ofNullable(e.errorType).map(this::toTypeNode), e.restErrorRule().stream().map(rule -> {
                            var a = rule.restErrorAction();
                            return new RestAdapterNode.ErrorRule(toLocation(rule), errorOr(rule.restErrorOr()),
                                new RestAdapterNode.Action(toLocation(a), a.code != null ? "reject" : a.responsibility.getText(),
                                    a.code != null ? a.code.getText() : a.category.getText(), errorString(a.message.getText(), toLocation(a))));
                        }).toList())));
            }).toList());
    }
    private RestAdapterNode.Condition errorOr(VernacParser.RestErrorOrContext c) {
        return new RestAdapterNode.Logical("or", c.restErrorAnd().stream().map(this::errorAnd).toList());
    }
    private RestAdapterNode.Condition errorAnd(VernacParser.RestErrorAndContext c) {
        return new RestAdapterNode.Logical("and", c.restErrorNot().stream().map(this::errorNot).toList());
    }
    private RestAdapterNode.Condition errorNot(VernacParser.RestErrorNotContext c) {
        if (c.restErrorNot() != null) return new RestAdapterNode.Negation(errorNot(c.restErrorNot()));
        if (c.restErrorOr() != null) return errorOr(c.restErrorOr());
        var p = c.restErrorPredicate(); var literal = p.restErrorLiteral();
        String kind = literal == null ? "presence" : literal.STRING_LITERAL() != null ? "string"
                : literal.booleanValue != null ? "boolean" : "number";
        return new RestAdapterNode.Predicate(toLocation(p), List.of(p.field.getText().split("\\.")),
                p.operator != null ? p.operator.getText() : p.presence.getText(),
                literal == null ? "" : kind.equals("string") ? errorString(literal.getText(), toLocation(p)) : literal.getText(), kind);
    }
    private String errorString(String literal, SourceLocation at) {
        // DSL strings in error rules follow Java escape spelling; never emit raw source as Java.
        try { return unquote(literal).translateEscapes(); }
        catch (IllegalArgumentException ex) {
            throw new org.vernac.compiler.analyzer.SemanticValidationException(List.of(
                org.vernac.compiler.analyzer.CompilerDiagnostic.error(at, "Invalid escape in error-rule string literal.")));
        }
    }
    public MappingNode visitMappingDefinition(VernacParser.MappingDefinitionContext ctx) {
        var imports=ctx.javaImports()==null?List.<JavaImportNode>of():ctx.javaImports().qualifiedName().stream()
            .map(n->new JavaImportNode(toLocation(n),n.getText())).toList();
        return new MappingNode(toLocation(ctx),ctx.name.getText(),toTypeNode(ctx.targetType),toTypeNode(ctx.sourceType),
            ctx.mappingAssignment().stream().map(a->new MappingAssignmentNode(toLocation(a),a.target.getText(),
                List.of(a.path.getText().split("\\.")),Optional.ofNullable(a.mapper).map(ParserRuleContext::getText))).toList(),
            imports,Optional.ofNullable(ctx.rawJavaBlock()).map(this::extractRawSource),
            Optional.ofNullable(ctx.implementation).map(ParserRuleContext::getText));
    }
    public SchemaNode visitSchemaDefinition(VernacParser.SchemaDefinitionContext ctx) {
        var fields=ctx.schemaField().stream().map(f->{
            var type=toTypeNode(f.fieldType);
            String name=f.name==null ? org.vernac.language.VernacNames.defaultMemberName(type.name()) : f.name.getText();
            return new SchemaFieldNode(toLocation(f),type,name,f.patch!=null,f.explicitNull!=null,
                    Optional.ofNullable(f.externalName).map(t->decodeExternalName(t.getText(),toLocation(f))));
        }).toList();
        return new SchemaNode(toLocation(ctx),ctx.name.getText(),ctx.strategy==null ? "exact" : ctx.strategy.getText(),fields);
    }
    private String decodeExternalName(String token, SourceLocation location) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(token,String.class); }
        catch(java.io.IOException ex) { throw new org.vernac.compiler.analyzer.SemanticValidationException(List.of(
                org.vernac.compiler.analyzer.CompilerDiagnostic.error(location,"Invalid JSON field-name literal: use JSON string escapes."))); }
    }
    private FieldNode adapterParameter(VernacParser.ParameterContext p) {
        var type = toTypeNode(p.type());
        return new FieldNode(toLocation(p),type,p.name == null ? org.vernac.language.VernacNames.defaultMemberName(type.name()) : p.name.getText(),p.isMut != null,p.name != null);
    }

    @Override
    public TypeNode visitPageType(VernacParser.PageTypeContext ctx) { return toTypeNode(ctx); }

    public TypeNode visitType(VernacParser.TypeContext ctx) {
        // 1. Den Haupt-Typnamen auslesen (z.B. "Optional" oder "LocalDate")
        String name = ctx.rawType.getText();

        // 2. Generics / Typ-Argumente verarbeiten (z.B. "<HolidayCalendar>")
        List<TypeNode> typeArgs = new java.util.ArrayList<>();
        if (ctx.typeArguments() != null) {
            typeArgs = ctx.typeArguments().type().stream()
                    .map(t -> (TypeNode) visit(t))
                    .toList();
        }

        // 3. Optional-Flag setzen, falls ein '?' vorhanden ist
        boolean isOptional = ctx.isOptional != null;

        // 4. Den korrekten Konstruktor exakt nach deiner Definition aufrufen
        return new TypeNode(locationOf(ctx), name, typeArgs, isOptional);
    }

    @Override
    public PortMethodNode visitPortMethodDefinition(VernacParser.PortMethodDefinitionContext ctx) {
        return new PortMethodNode(ctx.name.getText(),toTypeNode(ctx.returnType),
                ctx.parameterList() == null ? List.of() : extractParameters(ctx.parameterList()),locationOf(ctx));
    }

    private String extractRawSource(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) {
            return "";
        }
        int startIndex = ctx.getStart().getStartIndex();
        int stopIndex = ctx.getStop().getStopIndex();

        var charStream = ctx.getStart().getInputStream();
        if (charStream == null || startIndex > stopIndex) {
            return ctx.getText();
        }

        String fullBlock = charStream.getText(org.antlr.v4.runtime.misc.Interval.of(startIndex, stopIndex)).trim();

        if (fullBlock.startsWith("{") && fullBlock.endsWith("}")) {
            fullBlock = fullBlock.substring(1, fullBlock.length() - 1).trim();
        }

        return fullBlock;
    }

    private List<FieldNode> extractParameters(VernacParser.ParameterListContext ctx) {
        List<VernacParser.ParameterContext> params = ctx.parameter();

        return params.stream()
                .map(p -> {
                    boolean isMut = p.isMut != null;
                    TypeNode type = toTypeNode(p.paramType);
                    String fieldName;

                    if (p.name != null) {
                        fieldName = p.name.getText();
                    } else {
                        fieldName = org.vernac.language.VernacNames.defaultMemberName(type.name());
                    }

                    return new FieldNode(toLocation(p), type, fieldName, isMut, p.name != null);
                })
                .toList();
    }

    private TypeNode toTypeNode(VernacParser.PageTypeContext ctx) {
        var base=toTypeNode(ctx.type());
        var kind=ctx.counted!=null?TypeNode.PageKind.COUNTED_OFFSET:ctx.pageMode==null?TypeNode.PageKind.NONE
                :ctx.pageMode.getText().equals("offset")?TypeNode.PageKind.OFFSET:TypeNode.PageKind.CURSOR;
        return new TypeNode(toLocation(ctx),base.name(),base.typeArguments(),base.isOptional(),kind);
    }
    private List<FieldNode> extractParameters(VernacParser.ResultParameterListContext ctx) {
        return ctx.resultParameter().stream().map(p->{
            var type=toTypeNode(p.paramType);
            String name=p.name==null?org.vernac.language.VernacNames.defaultMemberName(type.name()):p.name.getText();
            return new FieldNode(toLocation(p),type,name,p.isMut!=null,p.name!=null);
        }).toList();
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
            return new SourceLocation(sourceName, start.getLine(), start.getCharPositionInLine() + 1);
        }
        return new SourceLocation(sourceName, 0, 0);
    }

    private String unquote(String text) {
        if (text != null && text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private SourceLocation locationOf(ParserRuleContext ctx) {
        return ctx == null ? new SourceLocation(sourceName, 0, 0) : toLocation(ctx);
    }

    @Override
    public UseCaseNode visitUsecaseDefinition(VernacParser.UsecaseDefinitionContext ctx) {
        List<FieldNode> inputs=ctx.inputs==null?List.of():extractParameters(ctx.inputs);
        List<FieldNode> results=ctx.results==null?List.of():extractParameters(ctx.results);
        List<FieldNode> dependencies=ctx.dependencies.stream().map(p -> {
            TypeNode t=toTypeNode(p.type());
            String name=p.name==null?org.vernac.language.VernacNames.defaultMemberName(t.name()):p.name.getText();
            return new FieldNode(toLocation(p),t,name,p.getText().startsWith("mut"),p.name!=null);
        }).toList();
        List<ValidationRuleNode> rules=ctx.validationBlock()==null?List.of():ctx.validationBlock().validationStatement().stream()
                .map(v -> new ValidationRuleNode(toLocation(v),extractRawSource(v.condition),v.message==null?"":unquote(v.message.getText()))).toList();
        List<JavaImportNode> imports=ctx.javaImports()==null?List.of():ctx.javaImports().qualifiedName().stream()
                .map(n -> new JavaImportNode(toLocation(n),n.getText())).toList();
        List<MethodNode> helpers=new ArrayList<>(); String body=""; Optional<String> implementation=Optional.empty(); int count=0;
        for(var member:ctx.usecaseBehaviorMember()) {
            if(member.behaviorMethod()!=null) {
                var m=member.behaviorMethod();
                helpers.add(new MethodNode(toLocation(m),m.visibility==null?"public":m.visibility.getText(),toTypeNode(m.returnType),
                        m.name.getText(),m.parameterList()==null?List.of():extractParameters(m.parameterList()),
                        m.rawJavaBlock()==null?"":extractRawSource(m.rawJavaBlock()),Optional.ofNullable(m.implementation).map(ParserRuleContext::getText)));
            } else { count++; body=member.rawJavaBlock()==null?"":extractRawSource(member.rawJavaBlock());
                implementation=Optional.ofNullable(member.implementation).map(ParserRuleContext::getText); }
        }
        return new UseCaseNode(toLocation(ctx),ctx.name.getText(),inputs,rules,dependencies,
                Optional.ofNullable(ctx.resultType).map(this::toTypeNode),results,imports,helpers,body,implementation,count);
    }

    public DomainServiceNode visitDomainServiceDefinition(VernacParser.DomainServiceDefinitionContext ctx) {
        var dependencies = ctx.dependencies.stream().map(p -> {
            var type = toTypeNode(p.type());
            var name = p.name == null ? org.vernac.language.VernacNames.defaultMemberName(type.name()) : p.name.getText();
            return new FieldNode(toLocation(p), type, name, p.isMut != null, p.name != null);
        }).toList();
        var imports = ctx.javaImports() == null ? List.<JavaImportNode>of() : ctx.javaImports().qualifiedName().stream()
                .map(n -> new JavaImportNode(toLocation(n), n.getText())).toList();
        var methods = ctx.serviceMethod().stream().map(m -> new ServiceMethodNode(
                new MethodNode(toLocation(m), m.visibility.getText(), toTypeNode(m.returnType), m.name.getText(),
                        m.parameterList() == null ? List.of() : extractParameters(m.parameterList()),
                        m.rawJavaBlock() == null ? "" : extractRawSource(m.rawJavaBlock()),
                        Optional.ofNullable(m.implementation).map(ParserRuleContext::getText)),
                m.validationBlock() == null ? List.of() : m.validationBlock().validationStatement().stream()
                        .map(v -> new ValidationRuleNode(toLocation(v), extractRawSource(v.condition),
                                v.message == null ? "" : unquote(v.message.getText()))).toList())).toList();
        return new DomainServiceNode(toLocation(ctx), ctx.name.getText(), dependencies, imports, methods);
    }

    public ListenerNode visitListenerDefinition(VernacParser.ListenerDefinitionContext ctx) {
        var eventType = toTypeNode(ctx.eventType);
        var dependencies = ctx.dependencies.stream().map(p -> {
            var type = toTypeNode(p.type());
            return new FieldNode(toLocation(p), type, p.name == null ? org.vernac.language.VernacNames.defaultMemberName(type.name()) : p.name.getText(), p.isMut != null, p.name != null);
        }).toList();
        var imports = ctx.javaImports() == null ? List.<JavaImportNode>of() : ctx.javaImports().qualifiedName().stream().map(n -> new JavaImportNode(toLocation(n), n.getText())).toList();
        List<MethodNode> helpers = new ArrayList<>(); String body = ""; Optional<String> implementation = Optional.empty(); int count = 0;
        for (var member : ctx.usecaseBehaviorMember()) {
            if (member.behaviorMethod() != null) {
                var m = member.behaviorMethod();
                helpers.add(new MethodNode(toLocation(m), m.visibility == null ? "public" : m.visibility.getText(), toTypeNode(m.returnType), m.name.getText(),
                    m.parameterList() == null ? List.of() : extractParameters(m.parameterList()), m.rawJavaBlock() == null ? "" : extractRawSource(m.rawJavaBlock()), Optional.ofNullable(m.implementation).map(ParserRuleContext::getText)));
            } else { count++; body = member.rawJavaBlock() == null ? "" : extractRawSource(member.rawJavaBlock()); implementation = Optional.ofNullable(member.implementation).map(ParserRuleContext::getText); }
        }
        var operation = new UseCaseNode(toLocation(ctx), ctx.name.getText(), List.of(new FieldNode(toLocation(ctx), eventType, "event")), List.of(), dependencies,
                Optional.empty(), List.of(), imports, helpers, body, implementation, count);
        return new ListenerNode(toLocation(ctx), ctx.name.getText(), ctx.deliveryMode.getText().equals("deferred"), eventType, operation);
    }
    private RepositoryMethodNode.Expression queryExpression(VernacParser.RepositoryExpressionContext ctx,
            List<RepositoryMethodNode.Predicate> leaves) {
        return new RepositoryMethodNode.Junction("or", ctx.repositoryAnd().stream().map(and ->
                (RepositoryMethodNode.Expression) new RepositoryMethodNode.Junction("and",
                        and.repositoryNot().stream().map(n -> queryNot(n, leaves)).toList())).toList());
    }
    private RepositoryMethodNode.Expression queryNot(VernacParser.RepositoryNotContext ctx,
            List<RepositoryMethodNode.Predicate> leaves) {
        if (ctx.repositoryNot() != null) return new RepositoryMethodNode.Negation(queryNot(ctx.repositoryNot(), leaves));
        if (ctx.repositoryExpression() != null) return queryExpression(ctx.repositoryExpression(), leaves);
        var p = ctx.repositoryPredicate();
        var leaf = new RepositoryMethodNode.Predicate(toLocation(p), p.field.getText(),
                p.operator != null ? p.operator.getText() : p.presence.getText(),
                p.parameterName == null ? "" : p.parameterName.getText());
        leaves.add(leaf);
        return leaf;
    }
}