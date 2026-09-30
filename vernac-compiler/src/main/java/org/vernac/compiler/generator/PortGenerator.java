package org.vernac.compiler.generator;

import com.squareup.javapoet.*;
import org.vernac.compiler.ast.*;

import javax.lang.model.element.Modifier;
import java.util.*;

public class PortGenerator {

    private static final ClassName REST_TEMPLATE = ClassName.get("org.springframework.web.client", "RestTemplate");
    private static final ClassName COMPONENT = ClassName.get("org.springframework.stereotype", "Component");

    private static String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
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
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

            MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC);

            for (FieldNode field : schema.fields()) {
                if (field == null) continue;
                TypeName fieldType = TypeResolver.resolve(field.type(), defaultAdapterPackage, explicitImports);
                String fieldName = field.name() != null ? field.name() : "field";

                schemaClass.addField(FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE, Modifier.FINAL).build());
                ctor.addParameter(fieldType, fieldName);
                ctor.addStatement("this.$N = $N", fieldName, fieldName);

                MethodSpec getter = MethodSpec.methodBuilder(fieldName)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(fieldType)
                        .addStatement("return this.$N", fieldName)
                        .build();
                schemaClass.addMethod(getter);
            }

            schemaClass.addMethod(ctor.build());
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
                if (customAdapter.delegateName().isPresent()) {
                    JavaFile delegateFile = generateCustomDelegate(port, method, customAdapter, adapterPackage, domainPackage, explicitImports);
                    files.add(delegateFile);
                }
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

        adapterClass.addField(REST_TEMPLATE, "restTemplate", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        ctor.addParameter(REST_TEMPLATE, "restTemplate");
        ctor.addStatement("this.restTemplate = $T.requireNonNull(restTemplate, \"restTemplate must not be null\")", Objects.class);
        adapterClass.addMethod(ctor.build());

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

        String url = restAdapter.configs().stream()
                .filter(c -> !c.key().equals("accept") && !c.key().equals("content-type"))
                .map(RestConfigNode::value)
                .findFirst()
                .orElse("/api/" + toSnakeCase(method.name()));

        methodImpl.addStatement("// TODO: HTTP Call implementation for endpoint: $L", url);

        if (method.returnType().name().equals("Optional")) {
            methodImpl.addStatement("return $T.empty()", Optional.class);
        } else if (!method.returnType().name().equals("void")) {
            methodImpl.addStatement("return null");
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
        String delegateName = customAdapter.delegateName().get();
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