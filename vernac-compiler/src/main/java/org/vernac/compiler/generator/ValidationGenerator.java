// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import org.jspecify.annotations.NullMarked;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.NativeValidation;
import javax.lang.model.element.Modifier;
import java.util.*;

/** Validation never receives the owner instance or privileged access to its fields. */
public final class ValidationGenerator {
    public static ClassName companion(ClassName owner) {
        return ClassName.get(owner.packageName(), "__VernacValidation_" + owner.simpleName());
    }
    public static MethodSpec delegate(ClassName owner) {
        return MethodSpec.methodBuilder("validate").addModifiers(Modifier.PRIVATE)
                .addStatement("$T.validate(new __ReadView())", companion(owner)).build();
    }
    public Optional<JavaFile> generate(ClassName owner, TopLevelDefinition definition, List<ValidationRuleNode> rules,
                                      List<JavaImportNode> imports, CompilationUnitNode unit, ResolvedProject project) {
        if (rules.isEmpty()) return Optional.empty();
        var type = TypeSpec.classBuilder(companion(owner)).addModifiers(Modifier.FINAL).addAnnotation(NullMarked.class)
                .addJavadoc("Generated state validation for $T. Do not edit.\n", owner)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        new BehaviorGenerator().addBodyImports(type, owner, imports, unit, project,
                String.join("\n", rules.stream().filter(rule -> rule.nativeRule().isEmpty()).map(ValidationRuleNode::condition).toList()));
        var readView = ClassName.get(owner.packageName() + ".access", owner.simpleName() + "Read");
        var validate = MethodSpec.methodBuilder("validate").addModifiers(Modifier.STATIC).addParameter(readView, "self");
        var nativeCompiler = new NativeValidationCompiler(definition, project);
        int index = 0;
        for (var rule : rules) {
            if (rule.nativeRule().isPresent()) {
                var nativeRule = rule.nativeRule().orElseThrow();
                String field = "nativeRule" + index++;
                type.addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(NativeValidation.Expression.class),
                                readView), field, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer(nativeCompiler.compile(nativeRule.predicate())).build());
                validate.beginControlFlow("if (!(boolean) $N.evaluate(self))", field)
                        .addStatement("throw new $T($S)", NativeValidation.Rejection.class, nativeRule.rejection())
                        .endControlFlow();
                continue;
            }
            String message = rule.message().isBlank() ? "Validation failed for: " + rule.condition() : rule.message();
            validate.beginControlFlow("if ($L)", ValidationConditions.failure(rule.condition()))
                    .addStatement("throw new $T($S)", DomainValidationException.class, owner.simpleName() + ": " + message)
                    .endControlFlow();
        }
        type.addMethod(validate.build());
        var file=JavaFile.builder(owner.packageName(),type.build()).indent("    ").skipJavaLangImports(true).build();
        BehaviorGenerator.addDebugFingerprint(type,file,unit,project);
        return Optional.of(JavaFile.builder(owner.packageName(),type.build()).indent("    ").skipJavaLangImports(true).build());
    }
}
