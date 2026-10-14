// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.tooling;
import java.util.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.parser.VernacParser;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import org.vernac.compiler.tooling.BehaviorJavaProjection.*;

/** Java editor context mirrors the generated usecase implementation, not its Spring wrapper. */
final class UseCaseJavaProjection {
    List<Block> build(VernacProject project,FileTypeScope scope,CompilationUnitNode unit,UseCaseNode u,
            VernacParser.UsecaseDefinitionContext syntax,String text) {
        return build(project,scope,unit,u,syntax.usecaseBehaviorMember(),syntax.validationBlock(),text,".usecase");
    }
    List<Block> build(VernacProject project,FileTypeScope scope,CompilationUnitNode unit,UseCaseNode u,
            List<VernacParser.UsecaseBehaviorMemberContext> members,VernacParser.ValidationBlockContext validation,String text,String suffix) {
        try {
            String pkg=unit.namespace()+suffix, owner=pkg+"."+u.name();
            var imports=new TreeMap<>(BehaviorImports.visible(project,scope));
            u.javaImports().forEach(i -> imports.put(i.target().substring(i.target().lastIndexOf('.')+1),i.target()));
            StringBuilder header=new StringBuilder("package "+pkg+";\n");
            imports.values().stream().filter(n -> n.contains(".")).distinct().forEach(n -> header.append("import ").append(n).append(";\n"));
            if(!u.resultFields().isEmpty()) header.append("import ").append(owner).append(".Result;\n");
            header.append("@org.jspecify.annotations.NullMarked final class __VernacEditorBehavior_").append(u.name()).append(" {\n");
            List<Fragment> fragments=new ArrayList<>();
            for(var member:members) {
                if(member.rawJavaBlock()!=null) {
                    String result=u.resultFields().isEmpty()?u.resultType().map(t -> type(t,scope,owner,project)).orElse("void"):owner+".Result";
                    var params=new ArrayList<String>();
                    params.add(pkg+".access."+u.name()+"Access self");
                    u.parameters().forEach(p -> params.add(type(p.type(),scope,owner,project)+" "+p.name()));
                    add(fragments,member.rawJavaBlock(),text,"static "+result+" execute("+String.join(", ",params)+") {\n","}\n");
                } else if(member.behaviorMethod()!=null && member.behaviorMethod().rawJavaBlock()!=null) {
                    var m=member.behaviorMethod();
                    var node=u.methods().stream().filter(n -> n.location().line()==m.getStart().getLine() && n.name().equals(m.name.getText())).findFirst().orElseThrow();
                    var params=node.parameters().stream().map(p -> type(p.type(),scope,owner,project)+" "+p.name()).toList();
                    add(fragments,m.rawJavaBlock(),text,"private static "+type(node.returnType(),scope,owner,project)+" "+node.name()+"("+String.join(", ",params)+") {\n","}\n");
                }
            }
            List<Block> blocks=new ArrayList<>();
            close(blocks,u.name(),fragments,header.toString());
            if(validation!=null) {
                List<Fragment> validations=new ArrayList<>();
                for(var v:validation.validationStatement()) add(validations,v.condition,text,"if (",") {}\n");
                close(blocks,u.name()+"Validation",validations,header.toString().replace("__VernacEditorBehavior_","__VernacEditorValidation_")+"static void validate("+pkg+".access."+u.name()+"Read self) {\n", "}\n}\n");
            }
            return blocks;
        } catch(IllegalArgumentException | NoSuchElementException incomplete) { return List.of(); }
    }
    String type(TypeNode t,FileTypeScope scope,String owner,VernacProject project) {
        String name;
        if(t.name().equals("Result")) name=owner+".Result";
        else if(t.name().equals("void")) name="void";
        else {
            var resolved=scope.resolve(t.name(),t.location()).type().orElseThrow();
            if(resolved instanceof ResolvedType.Declared d) name=d.symbol().identity().namespace()+(d.symbol().kind()==TypeSymbol.Kind.USE_CASE?".usecase.":".domain.")+d.symbol().identity().name();
            else name=JavaTypeNames.canonicalName(resolved);
        }
        if(t.paginated()) {
            var collection=(ResolvedType.Declared)scope.resolve(t.name(),t.location()).type().orElseThrow();
            var declaration=CollectionTypes.find(project,collection.symbol().identity()).orElseThrow();
            String element=collection.symbol().identity().namespace()+".domain."+declaration.elementName();
            name="org.vernac.runtime.pagination."+t.pageKind().javaName()+"<"+element+", "+name+">";
        }
        return t.isOptional()?"java.util.Optional<"+name+">":name;
    }
    void add(List<Fragment> fragments,ParserRuleContext node,String text,String prefix,String suffix) {
        int start=node.getStart().getStartIndex(),end=node.getStop().getStopIndex()+1;
        if(end<start) end=start;
        fragments.add(new Fragment(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end),prefix,suffix));
    }
    void close(List<Block> blocks,String name,List<Fragment> fragments,String header) { close(blocks,name,fragments,header,"}\n"); }
    void close(List<Block> blocks,String name,List<Fragment> fragments,String header,String footer) {
        if(fragments.isEmpty()) return;
        var first=fragments.getFirst();fragments.set(0,new Fragment(first.start(),first.end(),header+first.prefix(),first.suffix()));
        var last=fragments.getLast();fragments.set(fragments.size()-1,new Fragment(last.start(),last.end(),last.prefix(),last.suffix()+footer));
        blocks.add(new Block(name,fragments));
    }
}
