package org.vernac.compiler.ast;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.vernac.compiler.parser.VernacBaseVisitor;
import org.vernac.compiler.parser.VernacParser;
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
        if (ctx.valueDefinition() != null) return visitValueDefinition(ctx.valueDefinition());
        if (ctx.aggregateDefinition() != null) return visitAggregateDefinition(ctx.aggregateDefinition());
        if (ctx.entityDefinition() != null) return visitEntityDefinition(ctx.entityDefinition());
        if (ctx.eventDefinition() != null) return visitEventDefinition(ctx.eventDefinition());
        if (ctx.serviceDefinition() != null) return visitServiceDefinition(ctx.serviceDefinition());
        if (ctx.repositoryDefinition() != null) return visitRepositoryDefinition(ctx.repositoryDefinition());
        return null;
    }

    @Override
    public ValueObjectNode visitValueDefinition(VernacParser.ValueDefinitionContext ctx) {
        String name = ctx.name.getText();
        List<FieldNode> fields = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p, true))
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

        Optional<CollectionDefinitionNode> collection = Optional.empty();
        if (ctx.collectionDefinition() != null) {
            VernacParser.CollectionDefinitionContext collCtx = ctx.collectionDefinition();
            Optional<String> collectionName = Optional.ofNullable(collCtx.collectionName).map(ParserRuleContext::getText);

            List<MethodNode> collMethods = new ArrayList<>();
            if (collCtx.methodDefinition() != null) {
                for (VernacParser.MethodDefinitionContext mCtx : collCtx.methodDefinition()) {
                    collMethods.add(toMethodNode(mCtx));
                }
            }
            collection = Optional.of(new CollectionDefinitionNode(toLocation(collCtx), collectionName, collMethods));
        }

        return new ValueObjectNode(toLocation(ctx), name, fields, validations, voMethods, collection, customPackage);
    }

    @Override
    public AggregateNode visitAggregateDefinition(VernacParser.AggregateDefinitionContext ctx) {
        String name = ctx.name.getText();

        VernacParser.IdDefinitionContext idCtx = ctx.idDefinition();
        TypeNode idType = toTypeNode(idCtx.idType);
        String idFieldName = idCtx.name != null ? idCtx.name.getText() : "id";
        IdDefinitionNode idDef = new IdDefinitionNode(toLocation(idCtx), idType, idFieldName);

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

        VernacParser.IdDefinitionContext idCtx = ctx.idDefinition();
        TypeNode idType = toTypeNode(idCtx.idType);
        String idFieldName = idCtx.name != null ? idCtx.name.getText() : "id";
        IdDefinitionNode idDef = new IdDefinitionNode(toLocation(idCtx), idType, idFieldName);

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

        return new EntityNode(toLocation(ctx), name, idDef, fields, validations, methods, customPackage);
    }

    @Override
    public TopLevelDefinition visitRepositoryDefinition(VernacParser.RepositoryDefinitionContext ctx) {
        String aggregateName = ctx.aggregateName.getText();
        String name = ctx.name != null ? ctx.name.getText() : aggregateName + "Repository";

        Optional<String> customPackage = Optional.empty();
        Optional<String> tableName = Optional.empty();
        List<RepositoryMethodNode> methods = new ArrayList<>();

        for (VernacParser.RepositoryMemberContext member : ctx.repositoryMember()) {
            if (member.packageDeclarationStatement() != null) {
                customPackage = Optional.of(member.packageDeclarationStatement().qualifiedName().getText());
            } else if (member.tableDeclaration() != null) {
                tableName = Optional.of(unquote(member.tableDeclaration().tableName.getText()));
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

        return new RepositoryNode(toLocation(ctx), name, aggregateName, customPackage, tableName, methods);
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
    public ServiceNode visitServiceDefinition(VernacParser.ServiceDefinitionContext ctx) {
        String name = ctx.name.getText();
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

        return new ServiceNode(toLocation(ctx), name, schemas, methods);
    }

    private ServiceMethodNode toServiceMethodNode(VernacParser.ServiceMethodDefinitionContext ctx) {
        String fullHttp = ctx.httpAnnotation().getText();
        int firstParen = fullHttp.indexOf('(');
        String httpMethod = fullHttp.substring(1, firstParen);
        String path = unquote(ctx.httpAnnotation().STRING_LITERAL().getText());

        TypeNode returnType = toTypeNode(ctx.returnType);
        String name = ctx.name.getText();
        List<FieldNode> params = Optional.ofNullable(ctx.parameterList())
                .map(p -> extractParameters(p, false))
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
}