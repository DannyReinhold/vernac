package org.vernac.compiler.generator;
import com.palantir.javapoet.*;
import javax.lang.model.element.Modifier;
import java.util.*;
import org.jspecify.annotations.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
/** Only the domain contract; adapters are separate declarations. */
public final class PortGenerator {
    public List<JavaFile> generate(PortNode port, CompilationUnitNode unit, ResolvedProject project) {
        var type=TypeSpec.interfaceBuilder(port.name()).addModifiers(Modifier.PUBLIC).addAnnotation(NullMarked.class);
        for(var operation:port.methods()) type.addMethod(BeanBehaviorGenerator.signature(operation.method(),project)
                .addModifiers(Modifier.PUBLIC,Modifier.ABSTRACT).build());
        return List.of(JavaFile.builder(unit.namespace()+".domain",type.build()).indent("    ").skipJavaLangImports(true).build());
    }
}
