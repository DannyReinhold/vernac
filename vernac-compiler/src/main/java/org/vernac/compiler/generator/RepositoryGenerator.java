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

    private static String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    public List<JavaFile> generate(
            RepositoryNode repo,
            AggregateNode agg,
            Map<String, EntityNode> entities,
            Map<String, ValueObjectNode> valueObjects,
            String packageName,
            List<String> explicitImports
    ) {
        List<JavaFile> files = new ArrayList<>();

        ClassName aggType = ClassName.get(packageName, agg.name());
        TypeName idType = TypeResolver.resolve(agg.idDefinition().type(), packageName, explicitImports);
        String customInterfaceName = repo.name() + "Custom";
        ClassName customType = ClassName.get(packageName, customInterfaceName);

        // 1. Custom-Fragment Interface
        if (repo.hasCustomMethods()) {
            TypeSpec.Builder customSpec = TypeSpec.interfaceBuilder(customInterfaceName)
                    .addModifiers(Modifier.PUBLIC);

            for (RepositoryMethodNode m : repo.customMethods()) {
                MethodSpec.Builder mb = MethodSpec.methodBuilder(m.name())
                        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                        .returns(TypeResolver.resolve(m.returnType(), packageName, explicitImports));
                for (FieldNode p : m.parameters()) {
                    mb.addParameter(TypeResolver.resolve(p.type(), packageName, explicitImports), p.name());
                }
                customSpec.addMethod(mb.build());
            }
            files.add(JavaFile.builder(packageName, customSpec.build()).skipJavaLangImports(true).build());
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
                    .returns(TypeResolver.resolve(m.returnType(), packageName, explicitImports));
            for (FieldNode p : m.parameters()) {
                mb.addParameter(TypeResolver.resolve(p.type(), packageName, explicitImports), p.name());
            }
            repoInterface.addMethod(mb.build());
        }

        files.add(JavaFile.builder(packageName, repoInterface.build()).skipJavaLangImports(true).build());

        // 3. JDBC-Implementierung
        String jdbcClassName = "Jdbc" + repo.name();
        String tableName = repo.tableName().orElse(toSnakeCase(agg.name()) + "s");

        TypeSpec.Builder jdbcClass = TypeSpec.classBuilder(jdbcClassName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(ClassName.get(packageName, repo.name()))
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
                        .returns(TypeResolver.resolve(m.returnType(), packageName, explicitImports));
                List<String> paramNames = new ArrayList<>();
                for (FieldNode p : m.parameters()) {
                    mb.addParameter(TypeResolver.resolve(p.type(), packageName, explicitImports), p.name());
                    paramNames.add(p.name());
                }
                mb.addStatement("return this.customDelegate.$L($L)", m.name(), String.join(", ", paramNames));
                jdbcClass.addMethod(mb.build());
            }
        }
        jdbcClass.addMethod(ctor.build());

        // CRUD & Mapper
        jdbcClass.addMethod(buildByIdMethod(agg, aggType, idType, tableName));
        jdbcClass.addMethod(buildSaveMethod(agg, aggType));
        jdbcClass.addMethod(buildInsertMethod(agg, aggType, tableName, valueObjects));
        jdbcClass.addMethod(buildUpdateMethod(agg, aggType, tableName, valueObjects));
        jdbcClass.addMethod(buildDeleteMethod(agg, aggType, tableName));
        jdbcClass.addMethod(buildMapRowMethod(agg, aggType, valueObjects, entities, packageName, explicitImports));

        // Child Entities (z. B. List<OrderLine>) synchronisieren und nachladen
        for (FieldNode field : agg.fields()) {
            if (field.type().name().equals("List") && !field.type().typeArguments().isEmpty()) {
                TypeNode elemType = field.type().typeArguments().getFirst();
                if (entities.containsKey(elemType.name())) {
                    EntityNode childEntity = entities.get(elemType.name());
                    jdbcClass.addMethod(buildFetchChildEntitiesMethod(agg, childEntity, field.name(), valueObjects, packageName, explicitImports));
                    jdbcClass.addMethod(buildSyncChildEntitiesMethod(agg, childEntity, field.name(), valueObjects, packageName, explicitImports));
                }
            }
        }

        // Deklarierte Find-Methoden
        for (RepositoryMethodNode m : repo.findMethods()) {
            jdbcClass.addMethod(buildFindMethod(m, aggType, tableName, packageName, explicitImports));
        }

        files.add(JavaFile.builder(packageName, jdbcClass.build()).skipJavaLangImports(true).build());

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

    private MethodSpec buildInsertMethod(AggregateNode agg, ClassName aggType, String tableName, Map<String, ValueObjectNode> valueObjects) {
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
            bindFieldParameters(mb, f, "aggregate." + f.name() + "()", cols, vals, valueObjects);
        }

        mb.addStatement("String sql = \"INSERT INTO $L ($L) VALUES ($L)\"", tableName, String.join(", ", cols), String.join(", ", vals));
        mb.addStatement("this.jdbcTemplate.update(sql, params)");

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List")) {
                mb.addStatement("sync$L(aggregate.id(), aggregate.$L())", capitalize(f.name()), f.name());
            }
        }

        mb.addStatement("return aggregate.reconstituteWithVersion(nextVersion)");
        return mb.build();
    }

    private MethodSpec buildUpdateMethod(AggregateNode agg, ClassName aggType, String tableName, Map<String, ValueObjectNode> valueObjects) {
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
        List<String> colsDummy = new ArrayList<>();
        List<String> valsDummy = new ArrayList<>();

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List")) continue;
            int startSize = colsDummy.size();
            bindFieldParameters(mb, f, "aggregate." + f.name() + "()", colsDummy, valsDummy, valueObjects);
            for (int i = startSize; i < colsDummy.size(); i++) {
                setClauses.add(colsDummy.get(i) + " = " + valsDummy.get(i));
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
                mb.addStatement("sync$L(aggregate.id(), aggregate.$L())", capitalize(f.name()), f.name());
            }
        }

        mb.addStatement("return aggregate.reconstituteWithVersion(nextVersion)");
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
            String packageName,
            List<String> explicitImports
    ) {
        MethodSpec.Builder mb = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PRIVATE)
                .returns(aggType)
                .addParameter(ResultSet.class, "rs")
                .addException(SQLException.class);

        // ID extrahieren
        TypeName idBaseType = TypeResolver.resolve(agg.idDefinition().type(), packageName, explicitImports);
        mb.addStatement("$T id = new $T(rs.getObject(\"id\", $T.class))", idBaseType, idBaseType, UUID.class);

        // Felder extrahieren
        List<String> reconstituteArgs = new ArrayList<>();
        reconstituteArgs.add("id");

        for (FieldNode f : agg.fields()) {
            if (f.type().name().equals("List") && !f.type().typeArguments().isEmpty()) {
                String childMethod = "fetch" + capitalize(f.name()) + "(id)";
                reconstituteArgs.add(childMethod);
            } else {
                String varName = f.name();
                readFieldFromResultSet(mb, f, varName, valueObjects, packageName, explicitImports);
                reconstituteArgs.add(varName);
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

    private MethodSpec buildFetchChildEntitiesMethod(
            AggregateNode agg,
            EntityNode entity,
            String fieldName,
            Map<String, ValueObjectNode> valueObjects,
            String packageName,
            List<String> explicitImports
    ) {
        String methodName = "fetch" + capitalize(fieldName);
        String childTable = toSnakeCase(entity.name()) + "s";
        String parentFkColumn = toSnakeCase(agg.name()) + "_id";

        TypeName aggIdType = TypeResolver.resolve(agg.idDefinition().type(), packageName, explicitImports);
        ClassName entityType = ClassName.get(packageName, entity.name());
        TypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), entityType);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .returns(listType)
                .addParameter(aggIdType, "aggregateId");

        mb.addStatement("String sql = \"SELECT * FROM $L WHERE $L = :parentId\"", childTable, parentFkColumn);
        mb.beginControlFlow("return this.jdbcTemplate.query(sql, $T.of(\"parentId\", aggregateId.value()), (rs, rowNum) ->", Map.class);

        List<String> entityArgs = new ArrayList<>();
        // ID des Entity
        TypeName entityIdType = TypeResolver.resolve(entity.idDefinition().type(), packageName, explicitImports);
        mb.addStatement("$T id = new $T(rs.getObject(\"id\", $T.class))", entityIdType, entityIdType, UUID.class);
        entityArgs.add("id");

        for (FieldNode f : entity.fields()) {
            String varName = f.name();
            readFieldFromResultSet(mb, f, varName, valueObjects, packageName, explicitImports);
            entityArgs.add(varName);
        }

        mb.addStatement("return $T.reconstitute($L)", entityType, String.join(", ", entityArgs));
        mb.endControlFlow(")");

        return mb.build();
    }

    private MethodSpec buildSyncChildEntitiesMethod(
            AggregateNode agg,
            EntityNode entity,
            String fieldName,
            Map<String, ValueObjectNode> valueObjects,
            String packageName,
            List<String> explicitImports
    ) {
        String methodName = "sync" + capitalize(fieldName);
        String childTable = toSnakeCase(entity.name()) + "s";
        String parentFkColumn = toSnakeCase(agg.name()) + "_id";

        TypeName aggIdType = TypeResolver.resolve(agg.idDefinition().type(), packageName, explicitImports);
        ClassName entityType = ClassName.get(packageName, entity.name());
        TypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), entityType);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(aggIdType, "aggregateId")
                .addParameter(listType, "items");

        mb.addStatement("String selectExistingSql = \"SELECT id FROM $L WHERE $L = :parentId\"", childTable, parentFkColumn);
        mb.addStatement("$T<$T> existingIds = new $T<>(this.jdbcTemplate.query(selectExistingSql, $T.of(\"parentId\", aggregateId.value()), (rs, rowNum) -> rs.getObject(\"id\", $T.class)))",
                Set.class, Object.class, HashSet.class, Map.class, Object.class);

        mb.addStatement("$T<$T> incomingIds = items.stream().map(i -> i.id().value()).collect($T.toSet())",
                Set.class, Object.class, java.util.stream.Collectors.class);

        mb.addStatement("$T<$T> toDelete = existingIds.stream().filter(id -> !incomingIds.contains(id)).toList()", List.class, Object.class);
        mb.addStatement("$T<$T> toInsert = items.stream().filter(i -> !existingIds.contains(i.id().value())).toList()", List.class, entityType);
        mb.addStatement("$T<$T> toUpdate = items.stream().filter(i -> existingIds.contains(i.id().value())).toList()", List.class, entityType);

        mb.beginControlFlow("if (!toDelete.isEmpty())")
                .addStatement("this.jdbcTemplate.update(\"DELETE FROM $L WHERE id IN (:ids)\", $T.of(\"ids\", toDelete))", childTable, Map.class)
                .endControlFlow();

        // Batch Insert
        mb.beginControlFlow("if (!toInsert.isEmpty())");
        List<String> insertCols = new ArrayList<>(List.of("id", parentFkColumn));
        List<String> insertVals = new ArrayList<>(List.of(":id", ":parentId"));

        for (FieldNode f : entity.fields()) {
            if (valueObjects.containsKey(f.type().name())) {
                ValueObjectNode vo = valueObjects.get(f.type().name());
                if (vo.fields().size() == 1) {
                    insertCols.add(toSnakeCase(f.name()));
                    insertVals.add(":" + f.name());
                } else {
                    for (FieldNode vof : vo.fields()) {
                        insertCols.add(toSnakeCase(f.name()) + "_" + toSnakeCase(vof.name()));
                        insertVals.add(":" + f.name() + capitalize(vof.name()));
                    }
                }
            } else {
                insertCols.add(toSnakeCase(f.name()));
                insertVals.add(":" + f.name());
            }
        }

        mb.addStatement("String insertSql = \"INSERT INTO $L ($L) VALUES ($L)\"", childTable, String.join(", ", insertCols), String.join(", ", insertVals));
        mb.addStatement("$T[] batchParams = toInsert.stream().map(i -> buildEntityParamSource(aggregateId, i)).toArray($T[]::new)",
                MAP_PARAM_SOURCE, MAP_PARAM_SOURCE);
        mb.addStatement("this.jdbcTemplate.batchUpdate(insertSql, batchParams)");
        mb.endControlFlow();

        // Batch Update
        mb.beginControlFlow("if (!toUpdate.isEmpty())");
        List<String> updateSets = new ArrayList<>();
        for (FieldNode f : entity.fields()) {
            if (valueObjects.containsKey(f.type().name())) {
                ValueObjectNode vo = valueObjects.get(f.type().name());
                if (vo.fields().size() == 1) {
                    updateSets.add(toSnakeCase(f.name()) + " = :" + f.name());
                } else {
                    for (FieldNode vof : vo.fields()) {
                        updateSets.add(toSnakeCase(f.name()) + "_" + toSnakeCase(vof.name()) + " = :" + f.name() + capitalize(vof.name()));
                    }
                }
            } else {
                updateSets.add(toSnakeCase(f.name()) + " = :" + f.name());
            }
        }

        mb.addStatement("String updateSql = \"UPDATE $L SET $L WHERE id = :id AND $L = :parentId\"", childTable, String.join(", ", updateSets), parentFkColumn);
        mb.addStatement("$T[] batchParams = toUpdate.stream().map(i -> buildEntityParamSource(aggregateId, i)).toArray($T[]::new)",
                MAP_PARAM_SOURCE, MAP_PARAM_SOURCE);
        mb.addStatement("this.jdbcTemplate.batchUpdate(updateSql, batchParams)");
        mb.endControlFlow();

        return mb.build();
    }

    private void bindFieldParameters(
            MethodSpec.Builder mb,
            FieldNode f,
            String getterPath,
            List<String> cols,
            List<String> vals,
            Map<String, ValueObjectNode> valueObjects
    ) {
        String typeName = f.type().name();
        if (valueObjects.containsKey(typeName)) {
            ValueObjectNode vo = valueObjects.get(typeName);
            if (vo.fields().size() == 1) {
                // Wrapper VO (z. B. CustomerId)
                cols.add(toSnakeCase(f.name()));
                vals.add(":" + f.name());
                mb.addStatement("params.addValue(\"$L\", $L != null ? $L.value() : null)", f.name(), getterPath, getterPath);
            } else {
                // Multi-Value VO (z. B. Money)
                for (FieldNode vof : vo.fields()) {
                    String col = toSnakeCase(f.name()) + "_" + toSnakeCase(vof.name());
                    String param = f.name() + capitalize(vof.name());
                    cols.add(col);
                    vals.add(":" + param);
                    mb.addStatement("params.addValue(\"$L\", $L != null ? $L.$L() : null)", param, getterPath, getterPath, vof.name());
                }
            }
        } else {
            // Skalarer Typ
            cols.add(toSnakeCase(f.name()));
            vals.add(":" + f.name());
            mb.addStatement("params.addValue(\"$L\", $L)", f.name(), getterPath);
        }
    }

    private void readFieldFromResultSet(
            MethodSpec.Builder mb,
            FieldNode f,
            String varName,
            Map<String, ValueObjectNode> valueObjects,
            String packageName,
            List<String> explicitImports
    ) {
        String typeName = f.type().name();
        TypeName targetType = TypeResolver.resolve(f.type(), packageName, explicitImports);

        if (valueObjects.containsKey(typeName)) {
            ValueObjectNode vo = valueObjects.get(typeName);
            if (vo.fields().size() == 1) {
                FieldNode inner = vo.fields().getFirst();
                TypeName innerType = TypeResolver.resolve(inner.type(), packageName, explicitImports);
                String col = toSnakeCase(f.name());
                mb.addStatement("$T $L = rs.getObject(\"$L\") != null ? new $T(rs.getObject(\"$L\", $T.class)) : null",
                        targetType, varName, col, targetType, col, innerType);
            } else {
                // Multi-value VO: Rekonstruiere aus den Präfix-Spalten
                List<String> ctorArgs = new ArrayList<>();
                for (FieldNode vof : vo.fields()) {
                    String subCol = toSnakeCase(f.name()) + "_" + toSnakeCase(vof.name());
                    String subVar = varName + "_" + vof.name();
                    TypeName subType = TypeResolver.resolve(vof.type(), packageName, explicitImports);
                    mb.addStatement("$T $L = rs.getObject(\"$L\", $T.class)", subType, subVar, subCol, subType);
                    ctorArgs.add(subVar);
                }
                mb.addStatement("$T $L = new $T($L)", targetType, varName, targetType, String.join(", ", ctorArgs));
            }
        } else {
            String col = toSnakeCase(f.name());
            mb.addStatement("$T $L = rs.getObject(\"$L\", $T.class)", targetType, varName, col, targetType);
        }
    }

    private MethodSpec buildFindMethod(
            RepositoryMethodNode m,
            ClassName aggType,
            String tableName,
            String packageName,
            List<String> explicitImports
    ) {
        TypeName returnType = TypeResolver.resolve(m.returnType(), packageName, explicitImports);
        MethodSpec.Builder mb = MethodSpec.methodBuilder(m.name())
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        StringBuilder whereClause = new StringBuilder();
        mb.addStatement("$T params = new $T()", MAP_PARAM_SOURCE, MAP_PARAM_SOURCE);

        for (int i = 0; i < m.parameters().size(); i++) {
            FieldNode p = m.parameters().get(i);
            TypeName pType = TypeResolver.resolve(p.type(), packageName, explicitImports);
            mb.addParameter(pType, p.name());

            if (i > 0) whereClause.append(" AND ");
            whereClause.append(toSnakeCase(p.name())).append(" = :").append(p.name());
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