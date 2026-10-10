// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.ast;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.vernac.compiler.parser.VernacBaseVisitor;
import org.vernac.compiler.parser.VernacParser;
import org.vernac.compiler.util.TypeUtils;
import org.vernac.runtime.DispatchMode;

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

        return new CompilationUnitNode(toLocation(ctx.namespaceDeclaration()), namespace, imports, definitions);
    }

    private TopLevelDefinition toTopLevelDefinition(VernacParser.TopLevelDeclarationContext ctx) {
        if (ctx.idDeclaration() != null) return visitIdDeclaration(ctx.idDeclaration());
        if (ctx.valueDefinition() != null) return visitValueDefinition(ctx.valueDefinition());
        if (ctx.aggregateDefinition() != null) return visitAggregateDefinition(ctx.aggregateDefinition());
        if (ctx.entityDefinition() != null) return visitEntityDefinition(ctx.entityDefinition());
        if (ctx.eventDefinition() != null) return visitEventDefinition(ctx.eventDefinition());
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
        return new IdDeclarationNode(toLocation(ctx), name, collection(ctx.collectionDefinition()));
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

        Optional<CollectionDefinitionNode> collection = collection(ctx.collectionDefinition());

        return new ValueObjectNode(
                toLocation(ctx),
                name,
                fields,
                enumConstants,
                validations,
                voMethods,
                collection,
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

    private Optional<CollectionDefinitionNode> collection(VernacParser.CollectionDefinitionContext ctx) {
        if (ctx == null) return Optional.empty();
        return Optional.of(new CollectionDefinitionNode(toLocation(ctx),
                ctx.kind.getText().equals("list") ? CollectionDefinitionNode.Kind.LIST : CollectionDefinitionNode.Kind.SET,
                Optional.ofNullable(ctx.collectionName).map(ParserRuleContext::getText),
                behaviorMethods(ctx.behaviorBlock()), behaviorImports(ctx.behaviorBlock())));
    }

    @Override
    public AggregateNode visitAggregateDefinition(VernacParser.AggregateDefinitionContext ctx) {
        String name = ctx.name.getText();

        VernacParser.IdReferenceContext idCtx = ctx.idReference();
        TypeNode idType = new TypeNode(toLocation(idCtx), idCtx.idType.getText(), Collections.emptyList(), false);
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

        Optional<String> customPackage = Optional.empty();
        List<MethodNode> methods = new ArrayList<>();

        if (ctx.aggregateMember() != null) {
            for (VernacParser.AggregateMemberContext member : ctx.aggregateMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                } else if (member.methodDefinition() != null) {
                    methods.add(toMethodNode(member.methodDefinition()));
                }
            }
        }

        methods.addAll(behaviorMethods(ctx.behaviorBlock()));
        return new AggregateNode(toLocation(ctx), name, idDef, fields, validations, methods, customPackage, collection(ctx.collectionDefinition()), behaviorImports(ctx.behaviorBlock()));
    }

    @Override
    public EntityNode visitEntityDefinition(VernacParser.EntityDefinitionContext ctx) {
        String name = ctx.name.getText();

        VernacParser.IdReferenceContext idCtx = ctx.idReference();
        TypeNode idType = new TypeNode(toLocation(idCtx), idCtx.idType.getText(), Collections.emptyList(), false);
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

        Optional<String> customPackage = Optional.empty();
        List<MethodNode> methods = new ArrayList<>();

        if (ctx.entityMember() != null) {
            for (VernacParser.EntityMemberContext member : ctx.entityMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                } else if (member.methodDefinition() != null) {
                    methods.add(toMethodNode(member.methodDefinition()));
                }
            }
        }

        Optional<CollectionDefinitionNode> collection = collection(ctx.collectionDefinition());

        methods.addAll(behaviorMethods(ctx.behaviorBlock()));
        return new EntityNode(toLocation(ctx), name, idDef, fields, validations, methods, collection, customPackage, behaviorImports(ctx.behaviorBlock()));
    }

    @Override
    public TopLevelDefinition visitRepositoryDefinition(VernacParser.RepositoryDefinitionContext ctx) {
        String aggregateName = ctx.aggregateName.getText();
        String name = ctx.name != null ? ctx.name.getText() : aggregateName + "Repository";

        Optional<String> customPackage = Optional.empty();
        List<RepositoryMethodNode> methods = new ArrayList<>();

        for (VernacParser.RepositoryMemberContext member : ctx.repositoryMember()) {
            if (member.packageDeclarationStatement() != null) {
                customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
            } else if (member.repositoryFindMethod() != null) {
                VernacParser.RepositoryFindMethodContext findCtx = member.repositoryFindMethod();
                var predicates = new ArrayList<RepositoryMethodNode.Predicate>();
                var condition = findCtx.repositoryExpression() == null
                        ? new RepositoryMethodNode.Junction("and", List.of())
                        : queryExpression(findCtx.repositoryExpression(), predicates);
                methods.add(new RepositoryMethodNode(
                        toLocation(findCtx),
                        toTypeNode(findCtx.returnType),
                        findCtx.name.getText(),
                        Optional.ofNullable(findCtx.parameterList()).map(p -> extractParameters(p)).orElse(Collections.emptyList()),
                        false,
                        predicates,
                        findCtx.repositoryOrder().stream().map(o -> new RepositoryMethodNode.Order(
                                toLocation(o), o.field.getText(), o.direction != null && o.direction.getText().equals("desc"))).toList(), condition
                ));
            } else if (member.repositoryCustomMethod() != null) {
                VernacParser.RepositoryCustomMethodContext customCtx = member.repositoryCustomMethod();
                methods.add(new RepositoryMethodNode(
                        toLocation(customCtx),
                        toTypeNode(customCtx.returnType),
                        customCtx.name.getText(),
                        Optional.ofNullable(customCtx.parameterList()).map(p -> extractParameters(p)).orElse(Collections.emptyList()),
                        true
                ));
            }
        }

        return new RepositoryNode(toLocation(ctx), name, aggregateName, customPackage, methods);
    }

    @Override
    public EventNode visitEventDefinition(VernacParser.EventDefinitionContext ctx) {
        String name = ctx.name.getText();
        DispatchMode dispatchMode = DispatchMode.OUTBOX; // Default ist Outbox

        if (ctx.dispatchKind != null) {
            String kindText = ctx.dispatchKind.getText();
            if ("memory".equalsIgnoreCase(kindText)) {
                dispatchMode = DispatchMode.MEMORY;
            }
        }

        Optional<String> customPackage = Optional.empty();
        if (ctx.eventMember() != null) {
            for (VernacParser.EventMemberContext member : ctx.eventMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                }
            }
        }

        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p))
                .orElse(Collections.emptyList());

        return new EventNode(toLocation(ctx), name, dispatchMode, customPackage, fields);
    }

    @Override
    public PortNode visitPortDefinition(VernacParser.PortDefinitionContext ctx) {
        String name = ctx.name.getText();
        List<SchemaNode> schemas = new ArrayList<>();
        List<PortMethodNode> methods = new ArrayList<>();

        for (VernacParser.PortMemberContext memberCtx : ctx.portMember()) {
            if (memberCtx.schemaDefinition() != null) {
                schemas.add((SchemaNode) visit(memberCtx.schemaDefinition()));
            } else if (memberCtx.portMethodDefinition() != null) {
                methods.add((PortMethodNode) visit(memberCtx.portMethodDefinition()));
            }
        }
        return new PortNode(name, Optional.empty(), schemas, methods, locationOf(ctx));
    }

    @Override
    public SchemaNode visitSchemaDefinition(VernacParser.SchemaDefinitionContext ctx) {
        String name = ctx.name.getText();
        List<FieldNode> fields = ctx.schemaField().stream()
                .map(f -> (FieldNode) visit(f))
                .toList();
        return new SchemaNode(name, fields, locationOf(ctx));
    }

    @Override
    public FieldNode visitSchemaField(VernacParser.SchemaFieldContext ctx) {
        // 1. Prüfen, ob der Parser das Feld überhaupt erkannt hat
        if (ctx.fieldType == null) {
            throw new IllegalStateException("Parser-Fehler: 'fieldType' ist null. Hast du die ANTLR-Klassen neu generiert (mvn clean compile)? Gelesener Text: " + ctx.getText());
        }

        // 2. TypeNode über den Visitor auflösen
        TypeNode type = (TypeNode) visit(ctx.fieldType);

        // 3. Prüfen, ob der Visitor den Typ verarbeiten konnte
        if (type == null) {
            throw new IllegalStateException("Visitor-Fehler: visit(ctx.fieldType) hat null zurückgegeben für den Text: " + ctx.fieldType.getText());
        }

        String name = ctx.name.getText();
        return new FieldNode(locationOf(ctx), type, name, false);
    }

    @Override
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
    public RestAdapterNode visitAdapterRest(VernacParser.AdapterRestContext ctx) {
        Optional<String> customPkg = ctx.packageDeclarationStatement() != null
                ? Optional.of(ctx.packageDeclarationStatement().qualifiedName().getText())
                : Optional.empty();

        List<RestConfigNode> configs = ctx.restConfig().stream()
                .map(c -> (RestConfigNode) visit(c))
                .toList();

        List<RestErrorRuleNode> errorRules = ctx.restErrorRule().stream()
                .map(e -> (RestErrorRuleNode) visit(e))
                .toList();

        // 4 Parameter: customPkg an erster Stelle übergeben!
        return new RestAdapterNode(customPkg, configs, errorRules, locationOf(ctx));
    }

    @Override
    public CustomAdapterNode visitAdapterCustom(VernacParser.AdapterCustomContext ctx) {
        Optional<String> customPkg = ctx.packageDeclarationStatement() != null
                ? Optional.of(ctx.packageDeclarationStatement().qualifiedName().getText())
                : Optional.empty();

        Optional<String> delegateName = ctx.delegateName != null
                ? Optional.of(ctx.delegateName.getText())
                : Optional.empty();

        Optional<String> inlineCode = ctx.rawJavaBlock() != null
                ? Optional.of(extractRawSource(ctx.rawJavaBlock()))
                : Optional.empty();

        // 4 Parameter: customPkg ebenfalls an erster Stelle!
        return new CustomAdapterNode(customPkg, delegateName, inlineCode, locationOf(ctx));
    }

    @Override
    public PortMethodNode visitPortMethodDefinition(VernacParser.PortMethodDefinitionContext ctx) {
        TypeNode returnType = (TypeNode) visit(ctx.type());
        String name = ctx.methodName().getText();

        // Korrekt: extractParameters statt dem nicht existierenden visitParameterList
        List<FieldNode> parameters = ctx.parameterList() != null
                ? extractParameters(ctx.parameterList())
                : List.of();

        List<String> thrownExceptions = ctx.throwsClause() != null
                ? ctx.throwsClause().qualifiedName().stream().map(RuleContext::getText).toList()
                : List.of();

        AdapterNode adapter = (AdapterNode) visit(ctx.adapterDefinition());
        Optional<MappingBlockNode> mapping = ctx.mappingBlock() != null
                ? Optional.of((MappingBlockNode) visit(ctx.mappingBlock()))
                : Optional.empty();

        return new PortMethodNode(name, returnType, parameters, thrownExceptions, adapter, mapping, locationOf(ctx));
    }

    @Override
    public RestConfigNode visitRestConfig(VernacParser.RestConfigContext ctx) {
        String key = ctx.httpMethod() != null ? ctx.httpMethod().getText() : ctx.variableName().getText();
        String value = ctx.STRING_LITERAL().getText();

        // Quotes sicher entfernen, ohne externe Hilfsmethode
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }

        return new RestConfigNode(key, value, locationOf(ctx));
    }

    @Override
    public RestErrorRuleNode visitRestErrorRule(VernacParser.RestErrorRuleContext ctx) {
        String statusCode = ctx.statusCode().getText();

        Optional<String> returnExpr = ctx.expression() != null
                ? Optional.of(ctx.expression().getText())
                : Optional.empty();

        Optional<String> throwType = ctx.type() != null
                ? Optional.of(ctx.type().getText())
                : Optional.empty();

        return new RestErrorRuleNode(statusCode, returnExpr, throwType, locationOf(ctx));
    }

    @Override
    public MappingBlockNode visitMappingBlock(VernacParser.MappingBlockContext ctx) {
        List<MappingStatementNode> statements = ctx.mappingStatement().stream()
                .map(s -> (MappingStatementNode) visit(s))
                .toList();
        return new MappingBlockNode(statements, locationOf(ctx));
    }

    @Override
    public MappingStatementNode visitMappingStatement(VernacParser.MappingStatementContext ctx) {
        String direction = ctx.getChild(1).getText(); // Holt '->' oder '<-'
        String sourcePath = ctx.sourcePath().getText();
        String targetPath = ctx.targetPath().getText();

        return new MappingStatementNode(sourcePath, targetPath, direction, locationOf(ctx));
    }

    private MethodNode toMethodNode(VernacParser.MethodDefinitionContext ctx) {
        String access = Optional.ofNullable(ctx.accessModifier()).map(ParserRuleContext::getText).orElse("public");
        TypeNode returnType = toTypeNode(ctx.returnType);
        String name = ctx.name.getText();
        List<FieldNode> parameters = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p))
                .orElse(Collections.emptyList());

        String body = extractRawSource(ctx.rawJavaBlock());
        return new MethodNode(toLocation(ctx), access, returnType, name, parameters, body);
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
        String eventName = ctx.eventName.getText();
        Optional<String> customPackage = Optional.empty();
        List<UseDependencyNode> dependencies = new ArrayList<>();
        List<RawJavaStatementNode> statements = new ArrayList<>();

        if (ctx.listenerMember() != null) {
            for (VernacParser.ListenerMemberContext member : ctx.listenerMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                } else if (member.useDependencyStatement() != null) {
                    VernacParser.UseDependencyStatementContext depCtx = member.useDependencyStatement();
                    String typeName = depCtx.typeName().getText();
                    Optional<String> instanceName = depCtx.variableName() != null
                            ? Optional.of(depCtx.variableName().getText())
                            : Optional.empty();
                    dependencies.add(new UseDependencyNode(toLocation(depCtx), typeName, instanceName));
                } else if (member.rawJavaStatement() != null) {
                    String code = extractRawSource(member.rawJavaStatement());
                    statements.add(new RawJavaStatementNode(toLocation(member.rawJavaStatement()), code));
                }
            }
        }

        return new ListenerNode(
                toLocation(ctx),
                eventName,
                dependencies,
                statements,
                customPackage
        );
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