package org.vernac.compiler.generator;

import com.squareup.javapoet.*;
import org.vernac.compiler.ast.*;

import javax.lang.model.element.Modifier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class RepositoryGenerator {

    private static final ClassName PROPAGATION = ClassName.get("org.springframework.transaction.annotation", "Propagation");
    private static final ClassName TRANSACTIONAL = ClassName.get("org.springframework.transaction.annotation", "Transactional");
    private static final ClassName REPOSITORY = ClassName.get("org.springframework.stereotype", "Repository");
    private static final ClassName JDBC_TEMPLATE = ClassName.get("org.springframework.jdbc.core.namedparam", "NamedParameterJdbcTemplate");
    private static final ClassName MAP_PARAM_SOURCE = ClassName.get("org.springframework.jdbc.core.namedparam", "MapSqlParameterSource");
    private static final ClassName NOT_FOUND_EX = ClassName.get("org.vernac.runtime", "AggregateNotFoundException");
    private static final ClassName OPTIMISTIC_LOCK_EX = ClassName.get("org.springframework.dao", "OptimisticLockingFailureException");

    public List<JavaFile> generate(
            RepositoryNode repo,
            AggregateNode agg,
            Map<String, EntityNode> entities,
            Map<String, ValueObjectNode> valueObjects,
            String basePackage,
            List<String> explicitImports
    ) {
        List<JavaFile> files = new ArrayList<>();

        String domainPackage = PackageResolver.resolveDomainPackage(basePackage, agg.customPackage());
        String adapterPackage = PackageResolver.resolveAdapterPackage(basePackage, repo.customPackage());

        ClassName aggType = ClassName.get(domainPackage, agg.name());
        TypeName idType = TypeResolver.resolve(agg.idDefinition().type(), domainPackage, explicitImports);
        String customInterfaceName = repo.name() + "Custom";
        ClassName customType = ClassName.get(domainPackage, customInterfaceName);
        ClassName repoInterfaceType = ClassName.get(domainPackage, repo.name());

        // 1. Custom-Fragment Interface
        if (repo.hasCustomMethods()) {
            TypeSpec.Builder customSpec = TypeSpec.interfaceBuilder(customInterfaceName)
                    .addModifiers(Modifier.PUBLIC);

            for (RepositoryMethodNode m : repo.customMethods()) {
                MethodSpec.Builder mb = MethodSpec.methodBuilder(m.name())
                        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                        .returns(TypeResolver.resolve(m.returnType(), domainPackage, explicitImports));
                for (FieldNode p : m.parameters()) {
                    mb.addParameter(TypeResolver.resolve(p.type(), domainPackage, explicitImports), p.name());
                }
                customSpec.addMethod(mb.build());
            }
            files.add(JavaFile.builder(domainPackage, customSpec.build()).skipJavaLangImports(true).build());
        }

        // 2. Haupt-Repository-Interface
        TypeSpec.Builder repoInterface = TypeSpec.interfaceBuilder(repo.name())
                .addModifiers(Modifier.PUBLIC);

        if (repo.hasCustomMethods()) {
            repoInterface.addSuperinterface(customType);
        }

        repoInterface.addMethod(MethodSpec.methodBuilder("byId")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(aggType)
                .addParameter(idType, "id")
                .build());

        repoInterface.addMethod(MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(aggType)
                .addParameter(aggType, "aggregate")
                .build());

        repoInterface.addMethod(MethodSpec.methodBuilder("delete")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(aggType, "aggregate")
                .build());

        for (RepositoryMethodNode m : repo.findMethods()) {
            MethodSpec.Builder mb = MethodSpec.methodBuilder(m.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(TypeResolver.resolve(m.returnType(), domainPackage, explicitImports));
            for (FieldNode p : m.parameters()) {
                mb.addParameter(TypeResolver.resolve(p.type(), domainPackage, explicitImports), p.name());
            }
            repoInterface.addMethod(mb.build());
        }

        files.add(JavaFile.builder(domainPackage, repoInterface.build()).skipJavaLangImports(true).build());

        // 3. JDBC-Implementierung
        String jdbcClassName = "Jdbc" + repo.name();
        String tableName = repo.tableName().orElse(PostgresSchemaUtils.toSnakeCase(agg.name()) + "s");

        TypeSpec.Builder jdbcClass = TypeSpec.classBuilder(jdbcClassName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(repoInterfaceType)
                .addAnnotation(REPOSITORY)
                .addAnnotation(AnnotationSpec.builder(TRANSACTIONAL)
                        .addMember("propagation", "$T.MANDATORY", PROPAGATION)
                        .build());

        jdbcClass.addField(JDBC_TEMPLATE, "jdbcTemplate", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        ctor.addParameter(JDBC_TEMPLATE, "jdbcTemplate");
        ctor.addStatement("this.jdbcTemplate = $T.requireNonNull(jdbcTemplate, \"jdbcTemplate must not be null\")", Objects.class);

        if (repo.hasCustomMethods()) {
            jdbcClass.addField(customType, "customDelegate", Modifier.PRIVATE, Modifier.FINAL);
            ctor.addParameter(customType, "customDelegate");
            ctor.addStatement("this.customDelegate = $T.requireNonNull(customDelegate, \"customDelegate must not be null\")", Objects.class);

            for (RepositoryMethodNode m : repo.customMethods()) {
                MethodSpec.Builder mb = MethodSpec.methodBuilder(m.name())
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(TypeResolver.resolve(m.returnType(), domainPackage, explicitImports));
                List<String> paramNames = new ArrayList<>();
                for (FieldNode p : m.parameters()) {
                    mb.addParameter(TypeResolver.resolve(p.type(), domainPackage, explicitImports), p.name());
                    paramNames.add(p.name());
                }
                mb.addStatement("return this.customDelegate.$L($L)", m.name(), String.join(", ", paramNames));
                jdbcClass.addMethod(mb.build());
            }
        }
        jdbcClass.addMethod(ctor.build());

        // CRUD & Mapping Methoden
        jdbcClass.addMethod(buildByIdMethod(agg, aggType, idType, tableName));
        jdbcClass.addMethod(buildSaveMethod(agg, aggType));
        jdbcClass.addMethod(buildInsertMethod(agg, aggType, tableName, valueObjects, domainPackage));
        jdbcClass.addMethod(buildUpdateMethod(agg, aggType, tableName, valueObjects, domainPackage));
        jdbcClass.addMethod(buildDeleteMethod(agg, aggType, tableName));
        jdbcClass.addMethod(buildMapRowMethod(agg, aggType, valueObjects, entities, domainPackage, explicitImports));

        for (FieldNode field : agg.fields()) {
            if (field.type().name().equals("List") && !field.type().typeArguments().isEmpty()) {
                TypeNode elemType = field.type().typeArguments().getFirst();
                if (entities.containsKey(elemType.name())) {
                    EntityNode childEntity = entities.get(elemType.name());
                    jdbcClass.addMethod(buildFetchChildEntitiesMethod(agg, childEntity, field.name(), valueObjects, domainPackage, explicitImports));
                    jdbcClass.addMethod(buildSyncChildEntitiesMethod(agg, childEntity, field.name(), valueObjects, domainPackage, explicitImports));
                    jdbcClass.addMethod(buildEntityParamSourceMethod(agg, childEntity, valueObjects, domainPackage));
                }
            }
        }

        for (RepositoryMethodNode m : repo.findMethods()) {
            jdbcClass.addMethod(buildFindMethod(m, aggType, tableName, domainPackage, explicitImports));
        }

        files.add(JavaFile.builder(adapterPackage, jdbcClass.build()).skipJavaLangImports(true).build());
        return files;
    }

    private MethodSpec buildByIdMethod(AggregateNode agg, ClassName aggType, TypeName idType, String tableName) {
        return MethodSpec.methodBuilder("byId")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(aggType)
                .addParameter(idType, "id")
                .addStatement("String sql = \"SELECT * FROM $L WHERE id = :id\"", tableName)
                .addStatement("$T<$T> results = this.jdbcTemplate.query(sql, $T.of(\"id\", id.value()), (rs, rowNum) -> mapRow(rs))",
                        List.class, aggType, Map.class)
                .beginControlFlow("if (results.isEmpty())")
                .addStatement("throw new $T($T.class, id.value())", NOT_FOUND_EX, aggType)
                .endControlFlow()
                .addStatement("return results.getFirst()")
                .build();
    }

    private MethodSpec buildSaveMethod(AggregateNode agg, ClassName aggType) {
        return MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(aggType)
                .addParameter(aggType, "aggregate")
                .beginControlFlow("if (aggregate.version() == 0L)")
                .addStatement("return insert(aggregate)")
                .nextControlFlow("else")
                .addStatement("return update(aggregate)")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildInsertMethod(
            AggregateNode agg,
            ClassName aggType,
            String tableName,
            Map<String, ValueObjectNode> valueObjects,
            String domainPackage
    ) {
        MethodSpec.Builder mb = MethodSpec.methodBuilder("insert")
                .addModifiers(Modifier.PRIVATE)
                .returns(aggType)
                .addParameter(aggType, "aggregate")
                .addStatement("long nextVersion = 1L")
                .addStatement("$T params = new $T()", MAP_PARAM_SOURCE, MAP_PARAM_SOURCE)
                .addStatement("params.addValue(\"id\", aggregate.id().value())")
                .addStatement("params.addValue(\"createdAt\", $T.from(aggregate.createdAt()))", java.sql.Timestamp.class)
                .addStatement("params.addValue(\"updatedAt\", $T.from(aggregate.updatedAt()))", java.sql.Timestamp.class)
                .addStatement("params.addValue(\"version\", nextVersion)");

        List<String> cols = new ArrayList<>(List.of("id", "created_at", "updated_at", "version"));
        List<String> vals = new ArrayList<>(List.of(":id", ":createdAt", ":updatedAt", ":version"));

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List")) continue;
            List<FlatColumn> flatCols = PostgresSchemaUtils.flattenField(f, "aggregate", valueObjects, domainPackage);
            for (FlatColumn c : flatCols) {
                cols.add(c.columnName());
                vals.add(":" + c.sqlParameterName());
                mb.addStatement("params.addValue($S, $L)", c.sqlParameterName(), c.propertyPath());
            }
        }

        mb.addStatement("String sql = \"INSERT INTO $L ($L) VALUES ($L)\"", tableName, String.join(", ", cols), String.join(", ", vals));
        mb.addStatement("this.jdbcTemplate.update(sql, params)");

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List")) {
                mb.addStatement("sync$L(aggregate.id(), aggregate.$L())", PostgresSchemaUtils.capitalize(f.name()), f.name());
            }
        }

        mb.addStatement("return aggregate.withVersion(nextVersion)");
        return mb.build();
    }

    private MethodSpec buildUpdateMethod(
            AggregateNode agg,
            ClassName aggType,
            String tableName,
            Map<String, ValueObjectNode> valueObjects,
            String domainPackage
    ) {
        MethodSpec.Builder mb = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PRIVATE)
                .returns(aggType)
                .addParameter(aggType, "aggregate")
                .addStatement("long currentVersion = aggregate.version()")
                .addStatement("long nextVersion = currentVersion + 1L")
                .addStatement("$T params = new $T()", MAP_PARAM_SOURCE, MAP_PARAM_SOURCE)
                .addStatement("params.addValue(\"id\", aggregate.id().value())")
                .addStatement("params.addValue(\"updatedAt\", $T.from(aggregate.updatedAt()))", java.sql.Timestamp.class)
                .addStatement("params.addValue(\"nextVersion\", nextVersion)")
                .addStatement("params.addValue(\"currentVersion\", currentVersion)");

        List<String> setClauses = new ArrayList<>(List.of("updated_at = :updatedAt", "version = :nextVersion"));

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List")) continue;
            List<FlatColumn> flatCols = PostgresSchemaUtils.flattenField(f, "aggregate", valueObjects, domainPackage);
            for (FlatColumn c : flatCols) {
                setClauses.add(c.columnName() + " = :" + c.sqlParameterName());
                mb.addStatement("params.addValue($S, $L)", c.sqlParameterName(), c.propertyPath());
            }
        }

        mb.addStatement("String sql = \"UPDATE $L SET $L WHERE id = :id AND version = :currentVersion\"",
                tableName, String.join(", ", setClauses));
        mb.addStatement("int rows = this.jdbcTemplate.update(sql, params)");
        mb.beginControlFlow("if (rows == 0)")
                .addStatement("throw new $T(\"Optimistic lock conflict updating $L: [\" + aggregate.id().value() + \"]\")", OPTIMISTIC_LOCK_EX, agg.name())
                .endControlFlow();

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List")) {
                mb.addStatement("sync$L(aggregate.id(), aggregate.$L())", PostgresSchemaUtils.capitalize(f.name()), f.name());
            }
        }

        mb.addStatement("return aggregate.withVersion(nextVersion)");
        return mb.build();
    }

    private MethodSpec buildDeleteMethod(AggregateNode agg, ClassName aggType, String tableName) {
        return MethodSpec.methodBuilder("delete")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(aggType, "aggregate")
                .addStatement("String sql = \"DELETE FROM $L WHERE id = :id AND version = :version\"", tableName)
                .addStatement("int rows = this.jdbcTemplate.update(sql, $T.of(\"id\", aggregate.id().value(), \"version\", aggregate.version()))", Map.class)
                .beginControlFlow("if (rows == 0)")
                .addStatement("throw new $T(\"Optimistic lock conflict deleting $L: [\" + aggregate.id().value() + \"]\")", OPTIMISTIC_LOCK_EX, agg.name())
                .endControlFlow()
                .build();
    }

    private MethodSpec buildMapRowMethod(
            AggregateNode agg,
            ClassName aggType,
            Map<String, ValueObjectNode> valueObjects,
            Map<String, EntityNode> entities,
            String targetPackage,
            List<String> explicitImports
    ) {
        MethodSpec.Builder mb = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PRIVATE)
                .returns(aggType)
                .addParameter(ResultSet.class, "rs")
                .addException(SQLException.class);

        TypeName idBaseType = TypeResolver.resolve(agg.idDefinition().type(), targetPackage, explicitImports);
        mb.addStatement("$T id = $T.of(rs.getObject(\"id\", $T.class))", idBaseType, idBaseType, UUID.class);

        List<String> reconstituteArgs = new ArrayList<>();
        reconstituteArgs.add("id");

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List") && !f.type().typeArguments().isEmpty()) {
                String childMethod = "fetch" + PostgresSchemaUtils.capitalize(f.name()) + "(id)";
                reconstituteArgs.add(childMethod);
            } else {
                readAndReconstruct(mb, f.type(), PostgresSchemaUtils.toSnakeCase(f.name()), f.name(), valueObjects, targetPackage, explicitImports);
                reconstituteArgs.add(f.name());
            }
        }

        mb.addStatement("$T createdAt = rs.getTimestamp(\"created_at\").toInstant()", java.time.Instant.class);
        mb.addStatement("$T updatedAt = rs.getTimestamp(\"updated_at\").toInstant()", java.time.Instant.class);
        mb.addStatement("long version = rs.getLong(\"version\")");

        reconstituteArgs.add("createdAt");
        reconstituteArgs.add("updatedAt");
        reconstituteArgs.add("version");

        mb.addStatement("return $T.reconstitute($L)", aggType, String.join(", ", reconstituteArgs));
        return mb.build();
    }

    private void readAndReconstruct(
            MethodSpec.Builder mb,
            TypeNode type,
            String colPrefix,
            String targetVar,
            Map<String, ValueObjectNode> valueObjects,
            String targetPackage,
            List<String> explicitImports
    ) {
        String typeName = type.name();
        TypeName resolvedType = TypeResolver.resolve(type, targetPackage, explicitImports);

        if (valueObjects.containsKey(typeName)) {
            ValueObjectNode vo = valueObjects.get(typeName);

            if (vo.fields().size() == 1) {
                // Single-Value Object
                FieldNode inner = vo.fields().getFirst();
                TypeName boxedType = PostgresSchemaUtils.resolveBoxedType(inner.type(), targetPackage);
                String readExpr = String.format("rs.getObject(\"%s\", %s.class)", colPrefix, boxedType);

                if (type.isOptional()) {
                    mb.addStatement("$T $L = rs.getObject($S) != null ? $T.of($L) : null",
                            resolvedType, targetVar, colPrefix, resolvedType, readExpr);
                } else {
                    mb.addStatement("$T $L = $T.of($L)", resolvedType, targetVar, resolvedType, readExpr);
                }
            } else {
                // Multi-Value Object: Rekursives Auslesen aller inneren Attribute
                List<String> ctorArgs = new ArrayList<>();
                for (FieldNode inner : vo.fields()) {
                    String subCol = colPrefix + "_" + PostgresSchemaUtils.toSnakeCase(inner.name());
                    String subVar = targetVar + "_" + inner.name();
                    readAndReconstruct(mb, inner.type(), subCol, subVar, valueObjects, targetPackage, explicitImports);
                    ctorArgs.add(subVar);
                }
                mb.addStatement("$T $L = $T.of($L)", resolvedType, targetVar, resolvedType, String.join(", ", ctorArgs));
            }
        } else {
            // Leaf-Typ
            TypeName boxedType = PostgresSchemaUtils.resolveBoxedType(type, targetPackage);
            mb.addStatement("$T $L = rs.getObject($S, $T.class)", resolvedType, targetVar, colPrefix, boxedType);
        }
    }

    private MethodSpec buildEntityParamSourceMethod(
            AggregateNode agg,
            EntityNode entity,
            Map<String, ValueObjectNode> valueObjects,
            String domainPackage
    ) {
        String methodName = "build" + entity.name() + "ParamSource";
        TypeName aggIdType = TypeResolver.resolve(agg.idDefinition().type(), domainPackage);
        ClassName entityType = ClassName.get(domainPackage, entity.name());

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .returns(MAP_PARAM_SOURCE)
                .addParameter(aggIdType, "aggregateId")
                .addParameter(entityType, "item");

        mb.addStatement("$T params = new $T()", MAP_PARAM_SOURCE, MAP_PARAM_SOURCE);
        mb.addStatement("params.addValue(\"id\", item.id().value())");
        mb.addStatement("params.addValue(\"parentId\", aggregateId.value())");

        for (FieldNode f : entity.fields()) {
            List<FlatColumn> flatCols = PostgresSchemaUtils.flattenField(f, "item", valueObjects, domainPackage);
            for (FlatColumn c : flatCols) {
                mb.addStatement("params.addValue($S, $L)", c.sqlParameterName(), c.propertyPath());
            }
        }

        mb.addStatement("return params");
        return mb.build();
    }

    private MethodSpec buildFetchChildEntitiesMethod(
            AggregateNode agg,
            EntityNode entity,
            String fieldName,
            Map<String, ValueObjectNode> valueObjects,
            String targetPackage,
            List<String> explicitImports
    ) {
        String methodName = "fetch" + PostgresSchemaUtils.capitalize(fieldName);
        String childTable = PostgresSchemaUtils.toSnakeCase(entity.name()) + "s";
        String parentFkColumn = PostgresSchemaUtils.toSnakeCase(agg.name()) + "_id";

        TypeName aggIdType = TypeResolver.resolve(agg.idDefinition().type(), targetPackage, explicitImports);
        ClassName entityType = ClassName.get(targetPackage, entity.name());
        TypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), entityType);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .returns(listType)
                .addParameter(aggIdType, "aggregateId");

        mb.addStatement("String sql = \"SELECT * FROM $L WHERE $L = :parentId\"", childTable, parentFkColumn);
        mb.beginControlFlow("return this.jdbcTemplate.query(sql, $T.of(\"parentId\", aggregateId.value()), (rs, rowNum) ->", Map.class);

        List<String> entityArgs = new ArrayList<>();
        TypeName entityIdType = TypeResolver.resolve(entity.idDefinition().type(), targetPackage, explicitImports);

        mb.addStatement("$T id = $T.of(rs.getObject(\"id\", $T.class))", entityIdType, entityIdType, UUID.class);
        entityArgs.add("id");

        for (FieldNode f : entity.fields()) {
            readAndReconstruct(mb, f.type(), PostgresSchemaUtils.toSnakeCase(f.name()), f.name(), valueObjects, targetPackage, explicitImports);
            entityArgs.add(f.name());
        }

        mb.addStatement("return $T.create($L)", entityType, String.join(", ", entityArgs));
        mb.endControlFlow(")");

        return mb.build();
    }

    private MethodSpec buildSyncChildEntitiesMethod(
            AggregateNode agg,
            EntityNode entity,
            String fieldName,
            Map<String, ValueObjectNode> valueObjects,
            String targetPackage,
            List<String> explicitImports
    ) {
        String methodName = "sync" + PostgresSchemaUtils.capitalize(fieldName);
        String childTable = PostgresSchemaUtils.toSnakeCase(entity.name()) + "s";
        String parentFkColumn = PostgresSchemaUtils.toSnakeCase(agg.name()) + "_id";
        String paramMethodName = "build" + entity.name() + "ParamSource";

        TypeName aggIdType = TypeResolver.resolve(agg.idDefinition().type(), targetPackage, explicitImports);
        ClassName entityType = ClassName.get(targetPackage, entity.name());
        TypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), entityType);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(aggIdType, "aggregateId")
                .addParameter(listType, "items");

        mb.addStatement("String selectExistingSql = \"SELECT id FROM $L WHERE $L = :parentId\"", childTable, parentFkColumn);
        mb.addStatement("$T<$T> existingIds = new $T<>(this.jdbcTemplate.query(selectExistingSql, $T.of(\"parentId\", aggregateId.value()), (rs, rowNum) -> rs.getObject(\"id\", $T.class)))",
                Set.class, UUID.class, HashSet.class, Map.class, UUID.class);

        mb.addStatement("$T<$T> incomingIds = items.stream().map(i -> i.id().value()).collect($T.toSet())",
                Set.class, UUID.class, java.util.stream.Collectors.class);

        mb.addStatement("$T<$T> toDelete = existingIds.stream().filter(id -> !incomingIds.contains(id)).toList()", List.class, UUID.class);
        mb.addStatement("$T<$T> toInsert = items.stream().filter(i -> !existingIds.contains(i.id().value())).toList()", List.class, entityType);
        mb.addStatement("$T<$T> toUpdate = items.stream().filter(i -> existingIds.contains(i.id().value())).toList()", List.class, entityType);

        mb.beginControlFlow("if (!toDelete.isEmpty())")
                .addStatement("this.jdbcTemplate.update(\"DELETE FROM $L WHERE id IN (:ids)\", $T.of(\"ids\", toDelete))", childTable, Map.class)
                .endControlFlow();

        mb.beginControlFlow("if (!toInsert.isEmpty())");
        List<String> insertCols = new ArrayList<>(List.of("id", parentFkColumn));
        List<String> insertVals = new ArrayList<>(List.of(":id", ":parentId"));

        for (FieldNode f : entity.fields()) {
            List<FlatColumn> flatCols = PostgresSchemaUtils.flattenField(f, "", valueObjects, targetPackage);
            for (FlatColumn c : flatCols) {
                insertCols.add(c.columnName());
                insertVals.add(":" + c.sqlParameterName());
            }
        }

        mb.addStatement("String insertSql = \"INSERT INTO $L ($L) VALUES ($L)\"", childTable, String.join(", ", insertCols), String.join(", ", insertVals));
        mb.addStatement("$T[] batchParams = toInsert.stream().map(i -> $L(aggregateId, i)).toArray($T[]::new)",
                MAP_PARAM_SOURCE, paramMethodName, MAP_PARAM_SOURCE);
        mb.addStatement("this.jdbcTemplate.batchUpdate(insertSql, batchParams)");
        mb.endControlFlow();

        mb.beginControlFlow("if (!toUpdate.isEmpty())");
        List<String> updateSets = new ArrayList<>();
        for (FieldNode f : entity.fields()) {
            List<FlatColumn> flatCols = PostgresSchemaUtils.flattenField(f, "", valueObjects, targetPackage);
            for (FlatColumn c : flatCols) {
                updateSets.add(c.columnName() + " = :" + c.sqlParameterName());
            }
        }

        mb.addStatement("String updateSql = \"UPDATE $L SET $L WHERE id = :id AND $L = :parentId\"", childTable, String.join(", ", updateSets), parentFkColumn);
        mb.addStatement("$T[] batchParams = toUpdate.stream().map(i -> $L(aggregateId, i)).toArray($T[]::new)",
                MAP_PARAM_SOURCE, paramMethodName, MAP_PARAM_SOURCE);
        mb.addStatement("this.jdbcTemplate.batchUpdate(updateSql, batchParams)");
        mb.endControlFlow();

        return mb.build();
    }

    private MethodSpec buildFindMethod(
            RepositoryMethodNode m,
            ClassName aggType,
            String tableName,
            String targetPackage,
            List<String> explicitImports
    ) {
        TypeName returnType = TypeResolver.resolve(m.returnType(), targetPackage, explicitImports);
        MethodSpec.Builder mb = MethodSpec.methodBuilder(m.name())
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        StringBuilder whereClause = new StringBuilder();
        mb.addStatement("$T params = new $T()", MAP_PARAM_SOURCE, MAP_PARAM_SOURCE);

        for (int i = 0; i < m.parameters().size(); i++) {
            FieldNode p = m.parameters().get(i);
            TypeName pType = TypeResolver.resolve(p.type(), targetPackage, explicitImports);
            mb.addParameter(pType, p.name());

            if (i > 0) whereClause.append(" AND ");
            whereClause.append(PostgresSchemaUtils.toSnakeCase(p.name())).append(" = :").append(p.name());
            mb.addStatement("params.addValue(\"$L\", $L)", p.name(), p.name());
        }

        mb.addStatement("String sql = \"SELECT * FROM $L WHERE $L\"", tableName, whereClause.toString());

        if (m.returnType().name().equals("Optional")) {
            mb.addStatement("$T<$T> list = this.jdbcTemplate.query(sql, params, (rs, rowNum) -> mapRow(rs))", List.class, aggType);
            mb.addStatement("return list.isEmpty() ? $T.empty() : $T.of(list.getFirst())", Optional.class, Optional.class);
        } else {
            mb.addStatement("return this.jdbcTemplate.query(sql, params, (rs, rowNum) -> mapRow(rs))");
        }

        return mb.build();
    }
}