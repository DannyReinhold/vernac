// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.format;

import java.util.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.vernac.compiler.parser.*;

/** Whitespace-only file formatter. The parser identifies Java islands; the lexer guards every token. */
public final class VernacFormatter {
    public enum Kind { MODEL, APPLICATION, MODULE }
    private VernacFormatter() {}
    public static String format(String source) {return format(source,Kind.MODEL);}
    public static String format(String source,Kind kind) {
        var errors=new int[]{0};
        var listener=new BaseErrorListener(){@Override public void syntaxError(Recognizer<?,?> r,Object token,int line,int column,String message,RecognitionException e){errors[0]++;}};
        var lexer=new VernacLexer(CharStreams.fromString(source));lexer.removeErrorListeners();lexer.addErrorListener(listener);
        var stream=new CommonTokenStream(lexer);var parser=new VernacParser(stream);parser.removeErrorListeners();parser.addErrorListener(listener);
        ParseTree tree=switch(kind){case MODEL->parser.compilationUnit();case APPLICATION->parser.applicationDefinition();case MODULE->parser.moduleDefinition();};
        stream.fill();if(errors[0]!=0)return source;
        List<Token> tokens=stream.getTokens();Map<Integer,Integer> java=new HashMap<>();Set<Integer> declarations=new HashSet<>(), members=new HashSet<>(), angles=new HashSet<>();
        walk(tree,tokens,java,declarations,members,angles);
        var out=new Layout(source.contains("\r\n")?"\r\n":"\n");
        int parentheses=0;String previous="";
        for(int i=0;i<tokens.size();i++) {
            Token token=tokens.get(i);if(token.getType()==Token.EOF)break;
            String text=token.getText();
            if(java.containsKey(i)) {
                int last=java.get(i);Token end=tokens.get(last);
                int from=source.offsetByCodePoints(0,token.getStartIndex()),to=source.offsetByCodePoints(0,end.getStopIndex()+1);
                out.space();out.append(source.substring(from,to));i=last;previous="}";
            } else if(token.getType()==VernacLexer.LINE_COMMENT||token.getType()==VernacLexer.BLOCK_COMMENT) {
                out.line();out.append(text);out.commentLine();previous="";
            } else {
                switch(text) {
                    case "{" -> {out.space();out.append("{");out.indent++;if(!next(tokens,i).equals("}"))out.line();}
                    case "}" -> {out.indent--;if(!previous.equals("{"))out.line();out.append("}");String next=next(tokens,i);
                        if(!Set.of(";",",","list","set","bag","map","behavior","validates").contains(next))out.line();}
                    case ";" -> {out.tight();out.append(";");if(parentheses==0)out.line();else out.space();}
                    case "(" -> {out.tight();out.append("(");parentheses++;}
                    case ")" -> {out.tight();out.append(")");parentheses--;}
                    case "[" -> {out.tight();out.append("[");}
                    case "]","?" -> {out.tight();out.append(text);}
                    case "." -> {out.tight();out.append(".");}
                    case ",",":" -> {out.tight();out.append(text);out.space();}
                    case "<",">" -> {if(angles.contains(i)){out.tight();out.append(text);}else{out.space();out.append(text);out.space();}}
                    default -> {
                        if(!previous.isEmpty()&&!Set.of("(","[",".","<","!").contains(previous))out.space();
                        out.append(text);
                    }
                }
                previous=text;
            }
            if(members.contains(i))out.line();
            if(declarations.contains(i)){out.line();out.blank();previous="";}
        }
        String result=out.finish();
        // No pretty-printing decision can change a token, comment, string or embedded Java body.
        return fingerprint(source).equals(fingerprint(result))?result:source;
    }
    private static void walk(ParseTree tree,List<Token> tokens,Map<Integer,Integer> java,Set<Integer> declarations,Set<Integer> members,Set<Integer> angles) {
        if(tree instanceof VernacParser.BehaviorMethodContext || tree instanceof VernacParser.ServiceMethodContext || tree instanceof VernacParser.UsecaseBehaviorMemberContext)
            members.add(((ParserRuleContext)tree).getStop().getTokenIndex());
        if(tree instanceof VernacParser.TypeContext type)for(int i=type.getStart().getTokenIndex();i<=type.getStop().getTokenIndex();i++)
            if(Set.of("<",">").contains(tokens.get(i).getText()))angles.add(i);
        if(tree instanceof VernacParser.RawJavaBlockContext block) {
            int start=block.getStart().getTokenIndex()-1,end=block.getStop().getTokenIndex()+1;
            while(start>=0&&tokens.get(start).getChannel()!=Token.DEFAULT_CHANNEL)start--;
            while(end<tokens.size()&&tokens.get(end).getChannel()!=Token.DEFAULT_CHANNEL)end++;
            if(start>=0&&end<tokens.size()&&tokens.get(start).getText().equals("{")&&tokens.get(end).getText().equals("}"))java.put(start,end);
            return;
        }
        if(tree instanceof VernacParser.TopLevelDeclarationContext declaration)declarations.add(declaration.getStop().getTokenIndex());
        if(tree instanceof VernacParser.NamespaceDeclarationContext declaration)declarations.add(declaration.getStop().getTokenIndex());
        for(int i=0;i<tree.getChildCount();i++)walk(tree.getChild(i),tokens,java,declarations,members,angles);
    }
    private static String next(List<Token> tokens,int i) {
        for(i++;i<tokens.size();i++)if(tokens.get(i).getChannel()==Token.DEFAULT_CHANNEL)return tokens.get(i).getText();return "";
    }
    private static List<String> fingerprint(String source) {
        var lexer=new VernacLexer(CharStreams.fromString(source));lexer.removeErrorListeners();
        return lexer.getAllTokens().stream().map(t->t.getType()+":"+t.getText()).toList();
    }
    private static final class Layout {
        final StringBuilder text=new StringBuilder();final String newline;int indent;
        Layout(String newline){this.newline=newline;}
        void append(String value){if(text.isEmpty()||text.charAt(text.length()-1)=='\n')text.append("    ".repeat(Math.max(0,indent)));text.append(value);}
        void tight(){while(!text.isEmpty()&&(text.charAt(text.length()-1)==' '||text.charAt(text.length()-1)=='\t'))text.setLength(text.length()-1);}
        void space(){if(!text.isEmpty()&&!Character.isWhitespace(text.charAt(text.length()-1)))text.append(' ');}
        void line(){tight();if(!text.isEmpty()&&text.charAt(text.length()-1)!='\n')text.append(newline);}
        void commentLine(){if(!text.isEmpty()&&text.charAt(text.length()-1)!='\n')text.append(newline);}
        void blank(){line();if(!text.isEmpty()&&!text.toString().endsWith(newline+newline))text.append(newline);}
        String finish(){String result=text.toString();while(result.endsWith(newline+newline))result=result.substring(0,result.length()-newline.length());return result.endsWith(newline)?result:result+newline;}
    }
}
