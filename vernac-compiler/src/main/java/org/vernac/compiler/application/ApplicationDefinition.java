// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.application;

import java.nio.file.Path;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.parser.*;
import org.vernac.compiler.pipeline.ResolvedProject;
import org.vernac.compiler.symbols.*;
import org.vernac.language.VernacNames;

/** Parsed application composition. No runtime effects or configuration access. */
public record ApplicationDefinition(String name,List<Activation> activations,List<Binding> bindings) {
    public static final String FILE="vernac-application.vapplication";
    public record Activation(String module,SourceLocation location) { }
    public record Binding(String port,String adapter,String property,SourceLocation location) { }
    public ApplicationDefinition {activations=List.copyOf(activations);bindings=List.copyOf(bindings);}

    public static ApplicationDefinition parse(Path path,String text) {
        List<CompilerDiagnostic> errors=new ArrayList<>();
        var listener=new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?,?> r,Object symbol,int line,int column,String message,RecognitionException e) {
                errors.add(CompilerDiagnostic.error(new SourceLocation(path.toString(),line,column+1),"Application definition syntax error: "+message));
            }
        };
        var lexer=new VernacLexer(CharStreams.fromString(text));lexer.removeErrorListeners();lexer.addErrorListener(listener);
        var parser=new VernacParser(new CommonTokenStream(lexer));parser.removeErrorListeners();parser.addErrorListener(listener);
        var tree=parser.applicationDefinition();
        if(!errors.isEmpty())throw new SemanticValidationException(errors);
        SourceLocation header=new SourceLocation(path.toString(),tree.name.getStart().getLine(),tree.name.getStart().getCharPositionInLine()+1);
        // Applications may use a single valid namespace segment, just like their initial model.
        if(!VernacNames.isNamespace(tree.name.getText()))errors.add(CompilerDiagnostic.error(header,"Invalid application identifier."));
        List<Activation> activations=new ArrayList<>();List<Binding> bindings=new ArrayList<>();
        Set<String> modules=new HashSet<>(),ports=new HashSet<>();
        for(var entry:tree.applicationEntry()) {
            var location=new SourceLocation(path.toString(),entry.getStart().getLine(),entry.getStart().getCharPositionInLine()+1);
            if(entry.moduleName!=null) {
                String name=entry.moduleName.getText();qualified(name,location,errors);
                if(!modules.add(name))errors.add(CompilerDiagnostic.error(location,"Duplicate module activation: "+name));
                activations.add(new Activation(name,location));
            } else {
                String port=entry.port.getText();qualified(port,location,errors);
                if(!ports.add(port))errors.add(CompilerDiagnostic.error(location,"Duplicate binding for port: "+port));
                String adapter=entry.adapter==null?null:entry.adapter.getText(),property=null;
                if(adapter!=null)qualified(adapter,location,errors);
                if(entry.property!=null) {
                    try {property=ModuleDescriptor.JSON.readValue(entry.property.getText(),String.class);}
                    catch(Exception e) {errors.add(CompilerDiagnostic.error(location,"Configuration key must be a valid escaped string."));}
                    if(property!=null&&(property.isBlank()||!property.equals(property.strip())||property.chars().anyMatch(Character::isISOControl)))
                        errors.add(CompilerDiagnostic.error(location,"Configuration key must be nonblank, without surrounding whitespace or control characters."));
                }
                bindings.add(new Binding(port,adapter,property,location));
            }
        }
        if(!errors.isEmpty())throw new SemanticValidationException(errors);
        return new ApplicationDefinition(tree.name.getText(),activations,bindings);
    }
    private static void qualified(String name,SourceLocation at,List<CompilerDiagnostic> errors) {
        if(!VernacNames.isNamespace(name)||!name.contains("."))errors.add(CompilerDiagnostic.error(at,"Expected a fully qualified identity: "+name));
    }

    /** Validate identities and fixed implementation relationships without executing Spring. */
    public List<CompilerDiagnostic> diagnostics(ResolvedProject resolved,List<ModuleDescriptor> modules) {
        List<CompilerDiagnostic> errors=new ArrayList<>();
        for(var activation:activations) {
            var module=modules.stream().filter(m->m.module().equals(activation.module())).findFirst().orElse(null);
            if(module==null)errors.add(CompilerDiagnostic.error(activation.location(),"Unknown module '"+activation.module()+"'. Add its Maven compile dependency and rebuild."));
            else if(!module.activatable())
                errors.add(CompilerDiagnostic.error(activation.location(),"Module '"+activation.module()+"' has no activatable functions. Type/port contracts need no activation; select adapters through bindings."));
        }
        for(var binding:bindings) {
            var port=resolved.project().symbols().find(binding.port()).orElse(null);
            if(port==null||port.kind()!=TypeSymbol.Kind.PORT)errors.add(CompilerDiagnostic.error(binding.location(),"Binding requires an available exported or local Vernac port: "+binding.port()));
            if(binding.adapter()!=null) {
                var adapter=resolved.project().symbols().find(binding.adapter()).orElse(null);
                if(adapter==null||adapter.kind()!=TypeSymbol.Kind.OUTBOUND_ADAPTER)errors.add(CompilerDiagnostic.error(binding.location(),"Binding requires an available exported or local outbound adapter: "+binding.adapter()));
                else for(var source:resolved.project().sources())for(var definition:source.unit().definitions()) {
                    if(definition instanceof OutboundAdapterNode node && source.path().equals(adapter.sourceFile()) && node.name().equals(adapter.identity().name())) {
                        var target=resolved.types().get(node.portType());
                        if(target instanceof ResolvedType.Declared declared&&!declared.symbol().identity().qualifiedName().equals(binding.port()))
                            errors.add(CompilerDiagnostic.error(binding.location(),"Adapter '"+binding.adapter()+"' implements '"+declared.symbol().identity().qualifiedName()+"', not '"+binding.port()+"'."));
                    }
                }
            }
        }
        if(errors.isEmpty())try {
            org.vernac.runtime.application.ActivationPlan.resolve(ApplicationBindings.nodes(resolved,modules),activations.stream().map(Activation::module).toList(),bindings.stream().map(Binding::adapter).filter(Objects::nonNull).toList(),bindings.stream().anyMatch(b->b.property()!=null)?List.of():ApplicationBindings.requiredFunctions(resolved,modules),true);
        }catch(IllegalArgumentException e) {errors.add(CompilerDiagnostic.error(new SourceLocation(ModuleArtifacts.projectDirectory(resolved.project().sourceRoot()).resolve(FILE).toString(),1,1),e.getMessage()));}
        return List.copyOf(errors);
    }

    /** Compatibility hook: runtime support is checked through the shared activation plan. */
    public void requireBuildSupport() { }
}
