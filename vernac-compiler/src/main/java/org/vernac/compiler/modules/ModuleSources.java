// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.modules;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.antlr.v4.runtime.*;
import org.vernac.compiler.parser.*;

/** Optional sources improve navigation; missing attachments never affect compilation. */
public final class ModuleSources {
    private ModuleSources() { }
    public record View(Path path,String text,int start,int end) { }
    public static Map<String,View> locations(Path root,List<ModuleDescriptor> modules) throws java.io.IOException {
        Path index=ModuleArtifacts.projectDirectory(root).resolve("target/vernac-module-sources.json");
        if(!Files.isRegularFile(index))return Map.of();
        var attachments=ModuleDescriptor.JSON.readValue(Files.readString(index),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
        Map<String,View> result=new HashMap<>();
        for(var module:modules) {
            String attached=attachments.get(module.module());if(attached==null||!Files.isRegularFile(Path.of(attached)))continue;
            try(var jar=new JarFile(attached)) {
                for(var definition:module.exports()) {
                    String resource=definition.source();Path relative=Path.of(resource);
                    if(relative.isAbsolute()||relative.normalize().startsWith("..")||!resource.startsWith("vernac/"))continue;
                    var entry=jar.getJarEntry(resource);if(entry==null||entry.getSize()>5_000_000)continue;
                    String text;try(var stream=jar.getInputStream(entry)){text=new String(stream.readNBytes(5_000_001),java.nio.charset.StandardCharsets.UTF_8);}
                    var lexer=new VernacLexer(CharStreams.fromString(text));lexer.removeErrorListeners();var parser=new VernacParser(new CommonTokenStream(lexer));parser.removeErrorListeners();var tree=parser.compilationUnit();
                    int dot=definition.identity().lastIndexOf('.');String namespace=definition.identity().substring(0,dot),name=definition.identity().substring(dot+1);
                    if(tree.namespaceDeclaration()==null||!tree.namespaceDeclaration().qualifiedName().getText().equals(namespace))continue;
                    ParserRuleContext token=null;
                    for(var top:tree.topLevelDeclaration()) {
                        ParserRuleContext owner=top.idDeclaration()!=null?top.idDeclaration().name:top.valueDefinition()!=null?top.valueDefinition().name:null;
                        if(owner==null)continue;
                        if(owner.getText().equals(name))token=owner;
                        var collections=top.idDeclaration()!=null?top.idDeclaration().collectionDefinition():top.valueDefinition().collectionDefinition();
                        for(var c:collections)if((c.collectionName==null?owner.getText()+"s":c.collectionName.getText()).equals(name))token=c.collectionName==null?owner:c.collectionName;
                    }
                    if(token==null)continue;
                    Path path=ModuleArtifacts.projectDirectory(root).resolve("target/vernac-module-contracts").resolve(module.module()).resolve("attached").resolve(relative).normalize();
                    Files.createDirectories(path.getParent());
                    if(!Files.exists(path)||!Files.readString(path).equals(text)) {if(Files.exists(path))path.toFile().setWritable(true);Files.writeString(path,text);}
                    path.toFile().setWritable(false);
                    result.put(definition.identity(),new View(path,text,text.offsetByCodePoints(0,token.getStart().getStartIndex()),text.offsetByCodePoints(0,token.getStop().getStopIndex()+1)));
                }
            } catch(java.util.zip.ZipException ignored) { /* An unavailable attachment falls back to metadata. */ }
        }
        return Map.copyOf(result);
    }
}
