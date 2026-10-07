// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import org.vernac.language.VernacNames;

import com.palantir.javapoet.*;
import org.vernac.compiler.ast.*;

import javax.lang.model.element.Modifier;
import java.util.*;

public class PortGenerator {

    private static final ClassName REST_CLIENT = ClassName.get("org.springframework.web.client", "RestClient");
    private static final ClassName REST_CLIENT_BUILDER = ClassName.get("org.springframework.web.client", "RestClient", "Builder");
    private static final ClassName COMPONENT = ClassName.get("org.springframework.stereotype", "Component");
    private static final ClassName MEDIA_TYPE = ClassName.get("org.springframework.http", "MediaType");
    private static final ClassName HTTP_STATUS = ClassName.get("org.springframework.http", "HttpStatusCode");
    private static final ClassName VALUE_ANNOTATION = ClassName.get("org.springframework.beans.factory.annotation", "Value");

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return VernacNames.upperFirst(str);
    }

    private static String toSnakeCase(String camel) {
        return camel.replaceAll("(\\p{Ll})(\\p{Lu}+)", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public List<JavaFile> generate(
            PortNode port,
            String basePackage,
            List<String> explicitImports
    ) {
        List<JavaFile> files = new ArrayList<>();

        // 1. Domain Package & Port Interface
        String domainPackage = PackageResolver.resolveDomainPackage(basePackage, port.customPackage());
        ClassName portInterfaceType = ClassName.get(domainPackage, port.name());

        TypeSpec.Builder portInterface = TypeSpec.interfaceBuilder(port.name())
                .addModifiers(Modifier.PUBLIC);

        for (PortMethodNode method : port.methods()) {
            TypeName returnType = TypeResolver.resolve(method.returnType(), domainPackage, explicitImports);
            MethodSpec.Builder mb = MethodSpec.methodBuilder(method.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(returnType);

            for (FieldNode param : method.parameters()) {
                if (param == null) continue;
                TypeName paramType = TypeResolver.resolve(param.type(), domainPackage, explicitImports);
                mb.addParameter(paramType, param.name() != null ? param.name() : "arg");
            }

            for (String ex : method.thrownExceptions()) {
                mb.addException(ClassName.bestGuess(ex));
            }

            portInterface.addMethod(mb.build());
        }

        files.add(JavaFile.builder(domainPackage, portInterface.build()).skipJavaLangImports(true).build());

// 2. Schemas einmalig pro Port generieren (Infrastruktur-Ring)
        String defaultAdapterPackage = PackageResolver.resolveOutboundAdapterPackage(basePackage, port.name(), Optional.empty());
        for (SchemaNode schema : port.schemas()) {
            TypeSpec.Builder schemaClass = TypeSpec.classBuilder(schema.name())
                    .addModifiers(Modifier.PUBLIC);

            // Default-No-Args-Konstruktor für Jackson
            schemaClass.addMethod(MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .build());

            // All-Args-Konstruktor
            MethodSpec.Builder allArgsCtor = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC);

            for (FieldNode field : schema.fields()) {
                if (field == null) continue;
                TypeName fieldType = TypeResolver.resolve(field.type(), defaultAdapterPackage, explicitImports);
                String fieldName = field.name() != null ? field.name() : "field";

                // Non-final für Reflection-Deserialisierung
                schemaClass.addField(FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE).build());

                allArgsCtor.addParameter(fieldType, fieldName);
                allArgsCtor.addStatement("this.$N = $N", fieldName, fieldName);

                // Getter
                MethodSpec getter = MethodSpec.methodBuilder(fieldName)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(fieldType)
                        .addStatement("return this.$N", fieldName)
                        .build();
                schemaClass.addMethod(getter);

                // Setter (optional, hilft Reflection-Librarys)
                String setterName = "set" + capitalize(fieldName);
                MethodSpec setter = MethodSpec.methodBuilder(setterName)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(fieldType, fieldName)
                        .addStatement("this.$N = $N", fieldName, fieldName)
                        .build();
                schemaClass.addMethod(setter);
            }

            schemaClass.addMethod(allArgsCtor.build());
            files.add(JavaFile.builder(defaultAdapterPackage, schemaClass.build()).skipJavaLangImports(true).build());
        }
        // 3. Adapter pro Methode generieren
        for (PortMethodNode method : port.methods()) {
            AdapterNode adapter = method.adapter();
            if (adapter == null) continue;
            String adapterPackage = PackageResolver.resolveOutboundAdapterPackage(basePackage, port.name(), adapter.customPackage());

            if (adapter instanceof RestAdapterNode restAdapter) {
                JavaFile restAdapterFile = generateRestAdapter(port, method, restAdapter, portInterfaceType, adapterPackage, domainPackage, explicitImports);
                files.add(restAdapterFile);
            } else if (adapter instanceof CustomAdapterNode customAdapter) {
                JavaFile delegateFile = generateCustomDelegate(port, method, customAdapter, adapterPackage, domainPackage, explicitImports);
                files.add(delegateFile);
            }
        }

        return files;
    }

    private JavaFile generateRestAdapter(
            PortNode port,
            PortMethodNode method,
            RestAdapterNode restAdapter,
            ClassName portInterfaceType,
            String adapterPackage,
            String domainPackage,
            List<String> explicitImports
    ) {
        String adapterClassName = "Rest" + port.name() + capitalize(method.name()) + "Adapter";

        TypeSpec.Builder adapterClass = TypeSpec.classBuilder(adapterClassName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(portInterfaceType)
                .addAnnotation(COMPONENT);

        // 1. RestClient per Konstruktor injizieren
        adapterClass.addField(REST_CLIENT, "restClient", Modifier.PRIVATE, Modifier.FINAL);

        // Ermittle Property-Key (entweder explizit konfiguriert oder Konvention: vernac.outbound.<port-name>.base-url)
        String customProperty = restAdapter.configs().stream()
                .filter(c -> c.key().equals("baseUrlProperty") || c.key().equals("base-url-property"))
                .map(RestConfigNode::value)
                .findFirst()
                .orElse(null);

        String propertyKey = (customProperty != null)
                ? customProperty
                : "vernac.outbound." + PropertyUtils.resolvePropertyName(port.name()) + ".base-url";
        String valueAnnotationExpression = "${" + propertyKey + ":http://localhost:8080}";

        ParameterSpec baseUrlParam = ParameterSpec.builder(String.class, "baseUrl")
                .addAnnotation(AnnotationSpec.builder(VALUE_ANNOTATION)
                        .addMember("value", "$S", valueAnnotationExpression)
                        .build())
                .build();

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(REST_CLIENT_BUILDER, "restClientBuilder")
                .addParameter(baseUrlParam)
                .addStatement("this.restClient = $T.requireNonNull(restClientBuilder, $S).baseUrl(baseUrl).build()",
                        Objects.class, "restClientBuilder must not be null");

        adapterClass.addMethod(ctor.build());

        // 2. Methodensignatur aufbauen
        TypeName returnType = TypeResolver.resolve(method.returnType(), domainPackage, explicitImports);
        MethodSpec.Builder methodImpl = MethodSpec.methodBuilder(method.name())
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        for (FieldNode param : method.parameters()) {
            if (param == null) continue;
            TypeName paramType = TypeResolver.resolve(param.type(), domainPackage, explicitImports);
            methodImpl.addParameter(paramType, param.name() != null ? param.name() : "arg");
        }

        for (String ex : method.thrownExceptions()) {
            methodImpl.addException(ClassName.bestGuess(ex));
        }

        // 3. URL aus den REST-Configs ermitteln
        String url = restAdapter.configs().stream()
                .filter(c -> !c.key().equals("accept") && !c.key().equals("content-type"))
                .map(RestConfigNode::value)
                .findFirst()
                .orElse("/api/" + toSnakeCase(method.name()));

        // 4. URI & Query-Parameter dynamisch aufbauen
        StringBuilder uriTemplate = new StringBuilder(url);
        boolean first = true;
        for (FieldNode param : method.parameters()) {
            if (param != null && param.name() != null) {
                uriTemplate.append(first ? "?" : "&").append(param.name()).append("={").append(param.name()).append("}");
                first = false;
            }
        }

        // 5. RestClient Fluent Chain aufbauen
        methodImpl.addCode("var body = this.restClient.get()\n");
        methodImpl.addCode("    .uri($S", uriTemplate.toString());

        for (FieldNode param : method.parameters()) {
            if (param != null && param.name() != null) {
                methodImpl.addCode(", $N.value()", param.name());
            }
        }
        methodImpl.addCode(")\n");
        methodImpl.addCode("    .accept($T.APPLICATION_JSON)\n", MEDIA_TYPE);
        methodImpl.addCode("    .retrieve()\n");

        // Fehlerregeln einbauen (z.B. 404)
        for (RestErrorRuleNode rule : restAdapter.errorRules()) {
            if (rule.statusCode().equals("404")) {
                methodImpl.addCode("    .onStatus($T.valueOf(404)::equals, (req, res) -> {})\n", HTTP_STATUS);
            }
        }
        // Catch-All für alle restlichen HTTP-Fehler, um das Leaken von Spring-Exceptions zu verhindern
        methodImpl.addCode("    .onStatus($T::isError, (req, res) -> {\n", HTTP_STATUS);
        methodImpl.addCode("        throw new $T($S + res.getStatusCode());\n", RuntimeException.class, "External API call failed with status: ");
        methodImpl.addCode("    })\n");

        // 6. Auswertung des Schemas & Mappings
        String schemaName = port.schemas().isEmpty() ? null : port.schemas().getFirst().name();

        if (schemaName != null) {
            ClassName schemaType = ClassName.get(adapterPackage, schemaName);
            methodImpl.addCode("    .body($T.class);\n", schemaType);

            // Prüfe, ob die Rückgabe Optional ist, und extrahiere den echten Domänentyp
            // Variablen final machen, damit sie im Lambda genutzt werden dürfen
            final TypeName actualDomainType;
            final String domainSimpleName;
            final boolean isOptionalReturn;

            if (method.returnType().name().equals("Optional") && !method.returnType().typeArguments().isEmpty()) {
                actualDomainType = TypeResolver.resolve(method.returnType().typeArguments().getFirst(), domainPackage, explicitImports);
                domainSimpleName = method.returnType().typeArguments().getFirst().name();
                isOptionalReturn = true;
            } else {
                actualDomainType = returnType;
                domainSimpleName = method.returnType().name();
                isOptionalReturn = false;
            }

            // Auswertung des Mapping-Blocks, falls vorhanden
            if (method.mapping().isPresent() && !method.mapping().get().statements().isEmpty()) {
                MappingBlockNode mappingBlock = method.mapping().get();

                // Finde heraus, ob der Zieltyp eine "fromExternal" (Aggregate/Entity) oder "of" (Value Object) erwartet
                boolean isEntityOrAggregate = mappingBlock.statements().stream()
                        .anyMatch(stmt -> stmt.targetPath().equals(domainSimpleName + ".id") || stmt.targetPath().equals("id"));

                List<String> mappingArgs = new ArrayList<>();

                if (isEntityOrAggregate) {
                    // 1. Suche nach der gemappten ID
                    String idSource = mappingBlock.statements().stream()
                            .filter(stmt -> stmt.targetPath().endsWith(".id") || stmt.targetPath().equals("id"))
                            .map(stmt -> {
                                String[] parts = stmt.sourcePath().split("\\.");
                                return "body." + (parts.length > 1 ? parts[1] : parts[0]) + "()";
                            })
                            .findFirst()
                            .orElse("null");
                    mappingArgs.add(idSource);

                    // 2. Füge alle weiteren Felder an
                    for (MappingStatementNode stmt : mappingBlock.statements()) {
                        if (!stmt.targetPath().endsWith(".id") && !stmt.targetPath().equals("id")) {
                            String[] parts = stmt.sourcePath().split("\\.");
                            String sourceField = parts.length > 1 ? parts[1] : parts[0];
                            mappingArgs.add("body." + sourceField + "()");
                        }
                    }

                    String joinedArgs = String.join(", ", mappingArgs);
                    if (isOptionalReturn) {
                        methodImpl.addStatement("return body != null ? $T.of($T.fromExternal($L)) : $T.empty()",
                                Optional.class, actualDomainType, joinedArgs, Optional.class);
                    } else {
                        methodImpl.addStatement("return body != null ? $T.fromExternal($L) : null",
                                actualDomainType, joinedArgs);
                    }

                } else {
                    // Es ist ein Value Object, nutze .of(...)
                    for (MappingStatementNode stmt : mappingBlock.statements()) {
                        String[] parts = stmt.sourcePath().split("\\.");
                        String sourceField = parts.length > 1 ? parts[1] : parts[0];
                        mappingArgs.add("body." + sourceField + "()");
                    }
                    String joinedArgs = String.join(", ", mappingArgs);

                    if (isOptionalReturn) {
                        methodImpl.addStatement("return body != null ? $T.of($T.of($L)) : $T.empty()",
                                Optional.class, actualDomainType, joinedArgs, Optional.class);
                    } else {
                        methodImpl.addStatement("return body != null ? $T.of($L) : null",
                                actualDomainType, joinedArgs);
                    }
                }
            } else {
                // Fallback, wenn kein explizites Mapping definiert ist
                if (isOptionalReturn) {
                    methodImpl.addStatement("return body != null ? $T.of($T.of(body)) : $T.empty()", Optional.class, actualDomainType, Optional.class);
                } else {
                    methodImpl.addStatement("return body != null ? $T.of(body) : null", actualDomainType);
                }
            }
        } else {
            // Kein Schema vorhanden (z.B. reiner Void/String Call)
            if (method.returnType().name().equals("Optional")) {
                methodImpl.addStatement("return $T.empty()", Optional.class);
            } else {
                methodImpl.addStatement("return null");
            }
        }

        adapterClass.addMethod(methodImpl.build());
        return JavaFile.builder(adapterPackage, adapterClass.build()).skipJavaLangImports(true).build();
    }

    private JavaFile generateCustomDelegate(
            PortNode port,
            PortMethodNode method,
            CustomAdapterNode customAdapter,
            String adapterPackage,
            String domainPackage,
            List<String> explicitImports
    ) {
        String delegateName = customAdapter.delegateName().orElseGet(() ->
                port.name() + capitalize(method.name()) + "Delegate"
        );
        TypeSpec.Builder delegateInterface = TypeSpec.interfaceBuilder(delegateName)
                .addModifiers(Modifier.PUBLIC);

        TypeName returnType = TypeResolver.resolve(method.returnType(), domainPackage, explicitImports);
        MethodSpec.Builder mb = MethodSpec.methodBuilder(method.name())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(returnType);

        for (FieldNode param : method.parameters()) {
            if (param == null) continue;
            TypeName paramType = TypeResolver.resolve(param.type(), domainPackage, explicitImports);
            mb.addParameter(paramType, param.name() != null ? param.name() : "arg");
        }

        delegateInterface.addMethod(mb.build());
        return JavaFile.builder(adapterPackage, delegateInterface.build()).skipJavaLangImports(true).build();
    }
}