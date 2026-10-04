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
        if (ctx.idDeclaration() != null) return visitIdDeclaration(ctx.idDeclaration());
        if (ctx.valueDefinition() != null) return visitValueDefinition(ctx.valueDefinition());
        if (ctx.aggregateDefinition() != null) return visitAggregateDefinition(ctx.aggregateDefinition());
        if (ctx.entityDefinition() != null) return visitEntityDefinition(ctx.entityDefinition());
        if (ctx.eventDefinition() != null) return visitEventDefinition(ctx.eventDefinition());
        if (ctx.portDefinition() != null) return visitPortDefinition(ctx.portDefinition());
        if (ctx.repositoryDefinition() != null) return visitRepositoryDefinition(ctx.repositoryDefinition());
        if (ctx.usecaseDefinition() != null) return visitUsecaseDefinition(ctx.usecaseDefinition());
        if (ctx.domainServiceDefinition() != null) return visitDomainServiceDefinition(ctx.domainServiceDefinition());
        return null;
    }

    @Override
    public IdDeclarationNode visitIdDeclaration(VernacParser.IdDeclarationContext ctx) {
        String name = ctx.name.getText();
        Optional<String> customPackage = Optional.empty();

        if (ctx.idMember() != null) {
            for (VernacParser.IdMemberContext member : ctx.idMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                }
            }
        }

        return new IdDeclarationNode(toLocation(ctx), name, customPackage);
    }

    @Override
    public ValueObjectNode visitValueDefinition(VernacParser.ValueDefinitionContext ctx) {
        String name = ctx.name.getText();

        // 1. Parameter für normale Value Objects
        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p, true))
                .orElse(Collections.emptyList());

        // 2. Enum-Konstanten (falls vorhanden)
        List<EnumConstantNode> enumConstants = new ArrayList<>();
        if (ctx.enumConstantList() != null) {
            for (VernacParser.EnumConstantContext ecCtx : ctx.enumConstantList().enumConstant()) {
                String constName = ecCtx.name.getText();
                Optional<String> customDbValue = ecCtx.dbValue != null
                        ? Optional.of(unquote(ecCtx.dbValue.getText()))
                        : Optional.empty();
                enumConstants.add(new EnumConstantNode(toLocation(ecCtx), constName, customDbValue));
            }
        }

        // 3. Validierungen
        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = valCtx.condition.getText();
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        // 4. Methoden & Package-Override
        Optional<String> customPackage = Optional.empty();
        List<MethodNode> voMethods = new ArrayList<>();

        if (ctx.valueMember() != null) {
            for (VernacParser.ValueMemberContext member : ctx.valueMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                } else if (member.methodDefinition() != null) {
                    voMethods.add(toMethodNode(member.methodDefinition()));
                }
            }
        }

        // 5. First-Class Collections
        Optional<CollectionDefinitionNode> collection = Optional.empty();
        if (ctx.collectionDefinition() != null) {
            VernacParser.CollectionDefinitionContext collCtx = ctx.collectionDefinition();
            Optional<String> collectionName = Optional.ofNullable(collCtx.collectionName).map(ParserRuleContext::getText);

            List<MethodNode> collMethods = new ArrayList<>();
            Optional<String> collPackage = Optional.empty();

            if (collCtx.collectionMember() != null) {
                for (VernacParser.CollectionMemberContext member : collCtx.collectionMember()) {
                    if (member.packageDeclarationStatement() != null) {
                        collPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                    } else if (member.methodDefinition() != null) {
                        collMethods.add(toMethodNode(member.methodDefinition()));
                    }
                }
            }
            collection = Optional.of(new CollectionDefinitionNode(toLocation(collCtx), collectionName, collMethods, collPackage));
        }

        return new ValueObjectNode(
                toLocation(ctx),
                name,
                fields,
                enumConstants,
                validations,
                voMethods,
                collection,
                customPackage
        );
    }

    @Override
    public AggregateNode visitAggregateDefinition(VernacParser.AggregateDefinitionContext ctx) {
        String name = ctx.name.getText();

        VernacParser.IdReferenceContext idCtx = ctx.idReference();
        TypeNode idType = new TypeNode(toLocation(idCtx), idCtx.idType.getText(), Collections.emptyList(), false);
        IdReferenceNode idDef = new IdReferenceNode(toLocation(idCtx), idType);

        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p, false))
                .orElse(Collections.emptyList());

        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = valCtx.condition.getText();
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

        return new AggregateNode(toLocation(ctx), name, idDef, fields, validations, methods, customPackage);
    }

    @Override
    public EntityNode visitEntityDefinition(VernacParser.EntityDefinitionContext ctx) {
        String name = ctx.name.getText();

        VernacParser.IdReferenceContext idCtx = ctx.idReference();
        TypeNode idType = new TypeNode(toLocation(idCtx), idCtx.idType.getText(), Collections.emptyList(), false);
        IdReferenceNode idDef = new IdReferenceNode(toLocation(idCtx), idType);

        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p, false))
                .orElse(Collections.emptyList());

        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = valCtx.condition.getText();
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

        Optional<CollectionDefinitionNode> collection = Optional.empty();
        if (ctx.collectionDefinition() != null) {
            VernacParser.CollectionDefinitionContext collCtx = ctx.collectionDefinition();
            Optional<String> collectionName = Optional.ofNullable(collCtx.collectionName).map(ParserRuleContext::getText);

            List<MethodNode> collMethods = new ArrayList<>();
            Optional<String> collPackage = Optional.empty();

            if (collCtx.collectionMember() != null) {
                for (VernacParser.CollectionMemberContext member : collCtx.collectionMember()) {
                    if (member.packageDeclarationStatement() != null) {
                        collPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                    } else if (member.methodDefinition() != null) {
                        collMethods.add(toMethodNode(member.methodDefinition()));
                    }
                }
            }
            collection = Optional.of(new CollectionDefinitionNode(toLocation(collCtx), collectionName, collMethods, collPackage));
        }

        return new EntityNode(toLocation(ctx), name, idDef, fields, validations, methods, collection, customPackage);
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
                methods.add(new RepositoryMethodNode(
                        toLocation(findCtx),
                        toTypeNode(findCtx.returnType),
                        findCtx.name.getText(),
                        Optional.ofNullable(findCtx.parameterList()).map(p -> extractParameters(p, false)).orElse(Collections.emptyList()),
                        false
                ));
            } else if (member.repositoryCustomMethod() != null) {
                VernacParser.RepositoryCustomMethodContext customCtx = member.repositoryCustomMethod();
                methods.add(new RepositoryMethodNode(
                        toLocation(customCtx),
                        toTypeNode(customCtx.returnType),
                        customCtx.name.getText(),
                        Optional.ofNullable(customCtx.parameterList()).map(p -> extractParameters(p, false)).orElse(Collections.emptyList()),
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
                .map(p -> extractParameters(p, false))
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
                ? extractParameters(ctx.parameterList(), false)
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
                .map(p -> extractParameters(p, false))
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

    private List<FieldNode> extractParameters(VernacParser.ParameterListContext ctx, boolean isSingleValueFallback) {
        List<VernacParser.ParameterContext> params = ctx.parameter();
        boolean isSingle = params.size() == 1;

        return params.stream()
                .map(p -> {
                    boolean isMut = p.isMut != null;
                    TypeNode type = toTypeNode(p.paramType);
                    String fieldName;

                    if (p.name != null) {
                        fieldName = p.name.getText();
                    } else if (isSingle && isSingleValueFallback) {
                        fieldName = "value";
                    } else {
                        fieldName = deriveFieldName(type.name());
                    }

                    return new FieldNode(toLocation(p), type, fieldName, isMut);
                })
                .toList();
    }

    private String deriveFieldName(String typeName) {
        if (typeName == null || typeName.isEmpty()) return "value";

        if (TypeUtils.isPrimitive(typeName)) {
            return typeName + "Value";
        }

        if (typeName.length() > 1 && Character.isUpperCase(typeName.charAt(0)) && Character.isUpperCase(typeName.charAt(1))) {
            return typeName.toLowerCase(Locale.ROOT);
        }
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
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

    private SourceLocation locationOf(org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null) {
            return new SourceLocation(0, 0); // Sicherer Fallback
        }
        return new SourceLocation(
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public UseCaseNode visitUsecaseDefinition(VernacParser.UsecaseDefinitionContext ctx) {
        String name = ctx.name.getText();

        List<FieldNode> parameters = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p, false))
                .orElse(Collections.emptyList());

        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = valCtx.condition.getText();
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        Optional<String> customPackage = Optional.empty();
        List<UseDependencyNode> dependencies = new ArrayList<>();
        List<UseCaseStatementNode> statements = new ArrayList<>();
        Optional<ReturnStatementNode> returnStatement = Optional.empty();

        if (ctx.usecaseMember() != null) {
            for (VernacParser.UsecaseMemberContext member : ctx.usecaseMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                } else if (member.useDependencyStatement() != null) {
                    VernacParser.UseDependencyStatementContext depCtx = member.useDependencyStatement();
                    String typeName = depCtx.typeName().getText();
                    Optional<String> instanceName = depCtx.variableName() != null
                            ? Optional.of(depCtx.variableName().getText())
                            : Optional.empty();
                    dependencies.add(new UseDependencyNode(toLocation(depCtx), typeName, instanceName));
                } else if (member.usecaseStatement() != null) {
                    VernacParser.UsecaseStatementContext stmtCtx = member.usecaseStatement();

                    if (stmtCtx.loadStatement() != null) {
                        VernacParser.LoadStatementContext loadCtx = stmtCtx.loadStatement();
                        String aggType = loadCtx.aggregateType.getText();
                        Optional<String> instance = Optional.ofNullable(loadCtx.instanceName).map(RuleContext::getText);
                        Optional<String> repo = Optional.ofNullable(loadCtx.repositoryName).map(RuleContext::getText);
                        Optional<String> idExpr = Optional.ofNullable(loadCtx.idExpression).map(RuleContext::getText);
                        statements.add(new LoadStatementNode(toLocation(loadCtx), aggType, instance, repo, idExpr));
                    } else if (stmtCtx.saveStatement() != null) {
                        VernacParser.SaveStatementContext saveCtx = stmtCtx.saveStatement();
                        String instance = saveCtx.instanceName.getText();
                        Optional<String> repo = Optional.ofNullable(saveCtx.repositoryName).map(RuleContext::getText);
                        statements.add(new SaveStatementNode(toLocation(saveCtx), instance, repo));
                    } else if (stmtCtx.singleReturnStatement() != null) {
                        VernacParser.SingleReturnStatementContext retCtx = stmtCtx.singleReturnStatement();
                        Optional<String> expr = Optional.ofNullable(retCtx.expression()).map(RuleContext::getText);
                        returnStatement = Optional.of(new SingleReturnNode(toLocation(retCtx), expr));
                    } else if (stmtCtx.tupleReturnStatement() != null) {
                        VernacParser.TupleReturnStatementContext retCtx = stmtCtx.tupleReturnStatement();
                        List<TupleElementNode> elements = retCtx.tupleElement().stream()
                                .map(te -> new TupleElementNode(
                                        toLocation(te),
                                        te.expression().getText(),
                                        Optional.ofNullable(te.alias).map(RuleContext::getText)
                                ))
                                .toList();
                        returnStatement = Optional.of(new TupleReturnNode(toLocation(retCtx), elements));
                    } else if (stmtCtx.rawJavaStatement() != null) {
                        VernacParser.RawJavaStatementContext rawCtx = stmtCtx.rawJavaStatement();
                        String code = extractRawSource(rawCtx);
                        statements.add(new RawJavaStatementNode(toLocation(rawCtx), code));
                    }
                }
            }
        }

        return new UseCaseNode(
                toLocation(ctx),
                name,
                parameters,
                validations,
                dependencies,
                statements,
                returnStatement,
                customPackage
        );
    }

    public DomainServiceNode visitDomainServiceDefinition(VernacParser.DomainServiceDefinitionContext ctx) {
        String name = ctx.name.getText();

        List<FieldNode> parameters = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p, false))
                .orElse(Collections.emptyList());

        Optional<TypeNode> returnType = Optional.ofNullable(ctx.returnType)
                .map(this::toTypeNode);

        List<ValidationRuleNode> validations = new ArrayList<>();
        if (ctx.validationBlock() != null) {
            for (VernacParser.ValidationStatementContext valCtx : ctx.validationBlock().validationStatement()) {
                String condition = valCtx.condition.getText();
                String message = valCtx.message != null ? unquote(valCtx.message.getText()) : "";
                validations.add(new ValidationRuleNode(toLocation(valCtx), condition, message));
            }
        }

        Optional<String> customPackage = Optional.empty();
        List<UseCaseStatementNode> statements = new ArrayList<>();
        Optional<ReturnStatementNode> returnStatement = Optional.empty();

        if (ctx.domainServiceMember() != null) {
            for (VernacParser.DomainServiceMemberContext member : ctx.domainServiceMember()) {
                if (member.packageDeclarationStatement() != null) {
                    customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
                } else if (member.singleReturnStatement() != null) {
                    VernacParser.SingleReturnStatementContext retCtx = member.singleReturnStatement();
                    Optional<String> expr = Optional.ofNullable(retCtx.expression()).map(RuleContext::getText);
                    returnStatement = Optional.of(new SingleReturnNode(toLocation(retCtx), expr));
                } else if (member.tupleReturnStatement() != null) {
                    VernacParser.TupleReturnStatementContext retCtx = member.tupleReturnStatement();
                    List<TupleElementNode> elements = retCtx.tupleElement().stream()
                            .map(te -> new TupleElementNode(
                                    toLocation(te),
                                    te.expression().getText(),
                                    Optional.ofNullable(te.alias).map(RuleContext::getText)
                            ))
                            .toList();
                    returnStatement = Optional.of(new TupleReturnNode(toLocation(retCtx), elements));
                } else if (member.rawJavaStatement() != null) {
                    String code = extractRawSource(member.rawJavaStatement());
                    statements.add(new RawJavaStatementNode(toLocation(member.rawJavaStatement()), code));
                }
            }
        }

        return new DomainServiceNode(
                toLocation(ctx),
                name,
                parameters,
                returnType,
                validations,
                statements,
                returnStatement,
                customPackage
        );
    }
}