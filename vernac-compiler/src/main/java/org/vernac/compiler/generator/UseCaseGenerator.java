// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import com.palantir.javapoet.*;
import java.util.*;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.util.ConditionUtils;
import org.vernac.runtime.*;

/** Stateless Spring operation, isolated implementation and read-only preconditions. */
public final class UseCaseGenerator {
    private static final ClassName TRANSACTIONAL=ClassName.get("org.springframework.transaction.annotation","Transactional");
    private static final ClassName PROPAGATION=ClassName.get("org.springframework.transaction.annotation","Propagation");
    public List<JavaFile> generate(UseCaseNode u,CompilationUnitNode unit,ResolvedProject project) {
        ClassName owner=ClassName.get(unit.namespace()+".usecase",u.name());
        ClassName companion=BehaviorGenerator.companion(owner);
        ClassName read=ClassName.get(owner.packageName()+".access",u.name()+"Read");
        ClassName validator=ClassName.get(owner.packageName(),"__VernacValidation_"+u.name());
        var bean=TypeSpec.classBuilder(owner).addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class)
                .addAnnotation(ClassName.get("org.springframework.stereotype","Service"))
                .addJavadoc("Generated usecase. Do not edit.\n");
        var constructor=MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        for(var f:u.injections()) {
            TypeName type=raw(f.type(),project,owner);
            bean.addField(type,f.name(),Modifier.PRIVATE,Modifier.FINAL);
            constructor.addParameter(type,f.name()).addStatement("this.$N = $T.requireNonNull($N)",f.name(),Objects.class,f.name());
        }
        bean.addMethod(constructor.build());
        TypeName returns=returnType(u,project,owner);
        if(!u.resultFields().isEmpty()) bean.addType(result(u,project,owner));
        var execute=MethodSpec.methodBuilder("execute").addModifiers(Modifier.PUBLIC).returns(returns)
                .addAnnotation(AnnotationSpec.builder(TRANSACTIONAL).addMember("propagation","$T.REQUIRED",PROPAGATION).build());
        var bridge=MethodSpec.methodBuilder("execute").addModifiers(Modifier.STATIC).returns(returns);
        List<CodeBlock> call=new ArrayList<>(); List<String> names=new ArrayList<>();
        for(var f:u.parameters()) {
            TypeName type=raw(f.type(),project,owner);
            execute.addParameter(f.type().isOptional()?type.annotated(AnnotationSpec.builder(Nullable.class).build()):type,f.name());
            if(!f.type().isOptional()) execute.addStatement("$T.requireNonNull($N, $S)",DomainChecks.class,f.name(),u.name()+"."+f.name());
            bridge.addParameter(view(f.type(),project,owner),f.name()); names.add(f.name());
            call.add(f.type().isOptional()?CodeBlock.of("$T.ofNullable($N)",Optional.class,f.name()):CodeBlock.of("$N",f.name()));
        }
        List<JavaFile> files=new ArrayList<>();
        if(!u.validations().isEmpty()) {
            var readSpec=TypeSpec.interfaceBuilder(read).addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class);
            var input=TypeSpec.anonymousClassBuilder("").addSuperinterface(read);
            for(var f:u.parameters()) {
                var method=MethodSpec.methodBuilder(f.name()).returns(view(f.type(),project,owner));
                readSpec.addMethod(method.addModifiers(Modifier.PUBLIC,Modifier.ABSTRACT).build());
                var getter=MethodSpec.methodBuilder(f.name()).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(view(f.type(),project,owner));
                if(f.type().isOptional()) getter.addStatement("return $T.ofNullable($N)",Optional.class,f.name());
                else getter.addStatement("return $N",f.name());
                input.addMethod(getter.build());
            }
            execute.addStatement("$T.validate($L)",validator,input.build());
            var validation=TypeSpec.classBuilder(validator).addModifiers(Modifier.FINAL).addAnnotation(NullMarked.class)
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            var validate=MethodSpec.methodBuilder("validate").addModifiers(Modifier.STATIC).addParameter(read,"self");
            for(var rule:u.validations()) validate.beginControlFlow("if ($L)",ConditionUtils.negate(rule.condition()))
                    .addStatement("throw new $T($S)",DomainValidationException.class,rule.message().isBlank()?u.name()+": precondition failed":rule.message()).endControlFlow();
            validation.addMethod(validate.build());
            new BehaviorGenerator().addBodyImports(validation,owner,u.javaImports(),unit,project,u.validations().toString());
            files.add(file(read.packageName(),readSpec)); files.add(file(owner.packageName(),validation));
        }
        for(var f:u.injections()) {
            bridge.addParameter(raw(f.type(),project,owner),f.name());names.add(f.name());call.add(CodeBlock.of("this.$N",f.name()));
        }
        var invocation=CodeBlock.of("$T.execute($L)",companion,CodeBlock.join(call,", "));
        if(returns.equals(TypeName.VOID)) execute.addStatement("$L",invocation);
        else execute.addStatement("return $T.requireResult($L, $S)",BehaviorContractException.class,invocation,owner.canonicalName()+".execute");
        bean.addMethod(execute.build());
        var implementation=TypeSpec.classBuilder(companion).addModifiers(Modifier.FINAL).addAnnotation(NullMarked.class)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        if(!u.resultFields().isEmpty()) implementation.addJavadoc("@see $T\n",owner.nestedClass("Result"));
        var bodies=new StringBuilder(u.bodyCode());u.methods().forEach(m -> bodies.append("\n").append(m.bodyCode()));
        new BehaviorGenerator().addBodyImports(implementation,owner,u.javaImports(),unit,project,bodies.toString());
        if(u.implementation().isPresent()) {
            String target=u.implementation().get();
            if(!target.contains(".")) target=u.javaImports().stream().map(JavaImportNode::target).filter(t -> t.endsWith("."+u.implementation().get())).findFirst().orElse(owner.packageName()+"."+target);
            bridge.addStatement(returns.equals(TypeName.VOID)?"$L.execute($L)":"return $L.execute($L)",target,String.join(", ",names));
        } else bridge.addCode("$L\n",u.bodyCode());
        implementation.addMethod(bridge.build());
        for(var m:u.methods()) {
            var helper=MethodSpec.methodBuilder(m.name()).addModifiers(Modifier.PRIVATE,Modifier.STATIC).returns(view(m.returnType(),project,owner));
            for(var f:m.parameters()) helper.addParameter(view(f.type(),project,owner),f.name());
            helper.addCode("$L\n",m.bodyCode());implementation.addMethod(helper.build());
        }
        files.add(file(owner.packageName(),bean));
        var companionFile=JavaFile.builder(owner.packageName(),implementation.build()).indent("    ").skipJavaLangImports(true);
        if(!u.resultFields().isEmpty()) companionFile.addStaticImport(owner,"Result");
        files.add(companionFile.build());return files;
    }
    private TypeSpec result(UseCaseNode u,ResolvedProject project,ClassName owner) {
        var canonical=MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        for(var f:u.resultFields()) {
            canonical.addParameter(view(f.type(),project,owner),f.name());
            canonical.addStatement("this.$N = $T.requireNonNull($N, $S)",f.name(),DomainChecks.class,f.name(),"Result."+f.name());
        }
        var result=TypeSpec.recordBuilder("Result").addModifiers(Modifier.PUBLIC,Modifier.STATIC).addAnnotation(NullMarked.class).recordConstructor(canonical.build());
        var factory=MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC,Modifier.STATIC).returns(owner.nestedClass("Result"));
        List<CodeBlock> args=new ArrayList<>();
        for(var f:u.resultFields()) {
            var type=raw(f.type(),project,owner);factory.addParameter(f.type().isOptional()?type.annotated(AnnotationSpec.builder(Nullable.class).build()):type,f.name());
            args.add(f.type().isOptional()?CodeBlock.of("$T.ofNullable($N)",Optional.class,f.name()):CodeBlock.of("$N",f.name()));
        }
        factory.addStatement("return new Result($L)",CodeBlock.join(args,", "));result.addMethod(factory.build());
        if(u.resultFields().stream().anyMatch(f -> f.type().isOptional())) {
            var required=MethodSpec.methodBuilder("of").addModifiers(Modifier.PUBLIC,Modifier.STATIC).returns(owner.nestedClass("Result"));
            List<String> values=new ArrayList<>();
            for(var f:u.resultFields()) { if(!f.type().isOptional()) required.addParameter(raw(f.type(),project,owner),f.name());values.add(f.type().isOptional()?"null":f.name()); }
            required.addStatement("return of($L)",String.join(", ",values));result.addMethod(required.build());
        }
        return result.build();
    }
    private TypeName returnType(UseCaseNode u,ResolvedProject p,ClassName owner) {
        if(!u.resultFields().isEmpty()) return owner.nestedClass("Result");
        return u.resultType().map(t -> view(t,p,owner)).orElse(TypeName.VOID);
    }
    private TypeName view(TypeNode t,ResolvedProject p,ClassName owner) {
        var type=raw(t,p,owner);return t.isOptional()?ParameterizedTypeName.get(ClassName.get(Optional.class),type.box()):type;
    }
    private TypeName raw(TypeNode t,ResolvedProject p,ClassName owner) {
        if(t.name().equals("Result") && !p.types().containsKey(t)) return owner.nestedClass("Result");
        if(t.name().equals("void")) return TypeName.VOID;
        var resolved=p.typeOf(t);
        if(resolved instanceof ResolvedType.Declared d) {
            String suffix=d.symbol().kind()==TypeSymbol.Kind.USE_CASE?".usecase":".domain";
            return ClassName.get(d.symbol().identity().namespace()+suffix,d.symbol().identity().name());
        }
        return ResolvedJavaTypes.javaType(resolved);
    }
    private JavaFile file(String pkg,TypeSpec.Builder type) { return JavaFile.builder(pkg,type.build()).indent("    ").skipJavaLangImports(true).build(); }
}
