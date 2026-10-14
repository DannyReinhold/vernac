// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.vocabulary;

import java.nio.file.*;
import java.util.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.parser.VernacParser;

/** One vocabulary model for diagnostics, module documentation, hover and glossary output. */
public final class Vocabulary {
    private Vocabulary() { }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public record Key(String namespace,String name) { }
    public record Entry(String namespace,String name,String description,List<String> examples,List<String> notes,
                        String source,int line,int column) {
        public Entry {examples=List.copyOf(examples);notes=List.copyOf(notes);}
        public Key key(){return new Key(namespace,name);}
        public String qualifiedName(){return namespace+"."+name;}
        public boolean describes(String identity) {
            int dot=identity.lastIndexOf('.');
            return dot>0&&namespace.equals(identity.substring(0,dot))&&name.equals(identity.substring(dot+1));
        }
    }
    public record Glossary(int formatVersion,List<Entry> terms) {public Glossary {terms=List.copyOf(terms);}}
    private static void fail(SourceLocation at,String message) {
        throw new SemanticValidationException(List.of(CompilerDiagnostic.error(at,message)));
    }
    private static boolean blank(String text) {
        return text.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));
    }
    private static boolean edgeSpace(String text) {
        if(text.isEmpty())return false;
        int first=text.codePointAt(0),last=text.codePointBefore(text.length());
        return Character.isWhitespace(first)||Character.isSpaceChar(first)||Character.isWhitespace(last)||Character.isSpaceChar(last);
    }
    private static String string(String token,SourceLocation at) {
        try{return JSON.readValue(token,String.class);}catch(java.io.IOException e){fail(at,"Invalid vocabulary string: use JSON string escapes.");return "";}
    }
    public static TermNode parse(String source,VernacParser.TermDefinitionContext term) {
        var at=new SourceLocation(source,term.getStart().getLine(),term.getStart().getCharPositionInLine()+1);
        String name=term.name==null?string(term.label.getText(),at):term.name.getText();
        if(blank(name)||edgeSpace(name)||name.codePoints().anyMatch(Character::isISOControl))
            fail(at,"Term name must be nonblank, without surrounding whitespace or control characters.");
        String description=null;var examples=new ArrayList<String>();var notes=new ArrayList<String>();
        for(var item:term.termEntry()) {
            var location=new SourceLocation(source,item.getStart().getLine(),item.getStart().getCharPositionInLine()+1);
            String value=string(item.text.getText(),location);
            if(blank(value))fail(location,"Term "+item.kind.getText()+" must not be blank.");
            switch(item.kind.getText()) {
                case "description" -> {if(description!=null)fail(location,"Term must have exactly one description.");description=value;}
                case "example" -> examples.add(value);
                case "note" -> notes.add(value);
                default -> throw new IllegalStateException();
            }
        }
        if(description==null)fail(at,"Term must have exactly one nonblank description.");
        return new TermNode(at,name,description,examples,notes);
    }
    public static List<Entry> collect(Path root,List<VernacSourceFile> sources,List<CompilerDiagnostic> diagnostics) {
        Map<Key,Entry> result=new LinkedHashMap<>();
        for(var file:sources)for(var term:file.unit().terms()) {
            String path=file.external()?"dependency/"+file.unit().namespace().replace('.','/')+"/contract.vernac":root.relativize(file.path()).toString().replace('\\','/');
            var entry=new Entry(file.unit().namespace(),term.name(),term.description(),term.examples(),term.notes(),path,term.location().line(),term.location().column());
            if(result.putIfAbsent(entry.key(),entry)!=null)diagnostics.add(CompilerDiagnostic.error(term.location(),"Duplicate term '"+entry.qualifiedName()+"'. Quoted and unquoted spellings have the same identity."));
        }
        return result.values().stream().sorted(Comparator.comparing(Entry::namespace).thenComparing(Entry::name)).toList();
    }
    public static List<Entry> entries(VernacProject project) {
        var diagnostics=new ArrayList<CompilerDiagnostic>();var entries=collect(project.sourceRoot(),project.sources(),diagnostics);
        if(!diagnostics.isEmpty())throw new SemanticValidationException(diagnostics);return entries;
    }
    /** Escape documentation as plain text before inserting into Markdown/HTML consumers. */
    public static String escape(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\\","\\\\").replace("`","\\`").replace("*","\\*").replace("_","\\_").replace("[","\\[").replace("]","\\]").replace("#","\\#");
    }
    public static String markdown(Entry term) {
        StringBuilder text=new StringBuilder("**").append(escape(term.name())).append("** — ").append(escape(term.namespace())).append("\n\n").append(escape(term.description()));
        for(String example:term.examples())text.append("\n\n**Example:** ").append(escape(example));
        for(String note:term.notes())text.append("\n\n**Note:** ").append(escape(note));
        return text.toString();
    }
    public static List<com.palantir.javapoet.JavaFile> documentJava(VernacProject project,List<com.palantir.javapoet.JavaFile> files) {
        Map<String,Entry> documentation=new HashMap<>();
        for(var term:entries(project))project.symbols().find(term.qualifiedName())
            .filter(symbol->term.describes(symbol.identity().qualifiedName()))
            .ifPresent(symbol->documentation.put(org.vernac.compiler.symbols.JavaTypeNames.canonicalName(
                new org.vernac.compiler.symbols.ResolvedType.Declared(symbol)),term));
        var result=new ArrayList<com.palantir.javapoet.JavaFile>();
        for(var file:files) {
            var term=documentation.get(file.packageName()+"."+file.typeSpec().name());
            if(term==null){result.add(file);continue;}
            var doc=new StringBuilder("\n<h2>Domain vocabulary</h2>\n<p>").append(html(term.description())).append("</p>\n");
            for(String example:term.examples())doc.append("<p>Example: ").append(html(example)).append("</p>\n");
            for(String note:term.notes())doc.append("<p>Note: ").append(html(note)).append("</p>\n");
            var spec=file.typeSpec().toBuilder().addJavadoc("$L",doc.toString()).build();
            result.add(com.palantir.javapoet.JavaFile.builder(file.packageName(),spec).indent("    ").skipJavaLangImports(true).build());
        }
        return List.copyOf(result);
    }
    private static String html(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("*/","*&#47;");
    }
    public static String source(Entry term) {
        try {
            var text=new StringBuilder("term ").append(JSON.writeValueAsString(term.name())).append(" {\n    description ")
                .append(JSON.writeValueAsString(term.description())).append(";\n");
            for(String example:term.examples())text.append("    example ").append(JSON.writeValueAsString(example)).append(";\n");
            for(String note:term.notes())text.append("    note ").append(JSON.writeValueAsString(note)).append(";\n");
            return text.append("}\n").toString();
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public static void write(Path directory,VernacProject project) throws java.io.IOException {
        var terms=entries(project);Files.createDirectories(directory);
        Files.writeString(directory.resolve("vernac-glossary.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(new Glossary(1,terms))+"\n");
        StringBuilder text=new StringBuilder("# Vernac project glossary\n\nGenerated from the resolved model. Terms are documentation, not executable types.\n");
        for(var term:terms)text.append("\n---\n\n").append(markdown(term)).append("\n");
        Files.writeString(directory.resolve("vernac-glossary.md"),text.toString());
    }
}
