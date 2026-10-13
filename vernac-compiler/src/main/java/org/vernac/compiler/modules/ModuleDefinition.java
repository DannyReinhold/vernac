// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;

import java.nio.file.Path;
import java.util.*;
import org.antlr.v4.runtime.*;
import org.vernac.compiler.ast.SourceLocation;
import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.parser.*;
import org.vernac.language.VernacNames;

/** Explicit publication manifest, parsed independently of model compilation units. */
public record ModuleDefinition(String name,List<Export> exports) {
    public static final String FILE="vernac-module.vmodule";
    public record Export(String name,SourceLocation location) { }
    public static ModuleDefinition parse(Path path,String text) {
        List<CompilerDiagnostic> errors=new ArrayList<>();
        var listener=new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?,?> r,Object symbol,int line,int column,String message,RecognitionException e) {
                errors.add(CompilerDiagnostic.error(new SourceLocation(path.toString(),line,column+1),"Module definition syntax error: "+message));
            }
        };
        var lexer=new VernacLexer(CharStreams.fromString(text));lexer.removeErrorListeners();lexer.addErrorListener(listener);
        var parser=new VernacParser(new CommonTokenStream(lexer));parser.removeErrorListeners();parser.addErrorListener(listener);
        var tree=parser.moduleDefinition();
        if(!errors.isEmpty())throw new SemanticValidationException(errors);
        String name=tree.name.getText();
        if(!VernacNames.isNamespace(name)||!name.contains("."))errors.add(CompilerDiagnostic.error(new SourceLocation(path.toString(),1,1),"Module identifier must be a Java-compatible qualified name."));
        Set<String> seen=new HashSet<>();List<Export> exports=new ArrayList<>();
        for(var item:tree.moduleExport()) {
            var location=new SourceLocation(path.toString(),item.getStart().getLine(),item.getStart().getCharPositionInLine()+1);
            String type=item.exportedType.getText();
            if(!VernacNames.isNamespace(type) || !type.contains("."))errors.add(CompilerDiagnostic.error(location,"Export requires a fully qualified Vernac definition."));
            if(!seen.add(type))errors.add(CompilerDiagnostic.error(location,"Duplicate export: "+type));
            exports.add(new Export(type,location));
        }
        if(!errors.isEmpty())throw new SemanticValidationException(errors);
        return new ModuleDefinition(name,List.copyOf(exports));
    }
}
