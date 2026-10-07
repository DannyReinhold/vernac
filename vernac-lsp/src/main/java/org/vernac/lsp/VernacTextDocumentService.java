// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.lsp;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;
import org.vernac.language.VernacNames;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VernacTextDocumentService implements TextDocumentService {

    private static final Set<String> DSL_KEYWORDS = Set.of(
            "namespace", "package", "import", "as", "id",
            "aggregate", "value", "entity", "event", "outbox", "memory", "service", "external", "schema",
            "repository", "for", "table", "find", "custom", "validates", "require",
            "mut", "invariant", "mapping",
            "port", "adapter", "rest", "on", "throw", "throws",
            "usecase", "use", "load", "save", "listener"
    );

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "instanceof", "int", "interface", "long", "native", "new", "null",
            "public", "private", "protected", "record",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
            "true", "false"
    );

    private final VernacProjectDiagnostics projects;

    public VernacTextDocumentService() {
        this(new VernacProjectDiagnostics());
    }

    VernacTextDocumentService(VernacProjectDiagnostics projects) {
        this.projects = projects;
    }

    private static void collectTypeOffsets(ParseTree tree, Set<Integer> offsets) {
        if (tree instanceof VernacParser.TypeNameContext name) offsets.add(name.getStart().getStartIndex());
        if (tree instanceof VernacParser.TypeContext type && type.rawType != null)
            offsets.add(type.rawType.getStop().getStartIndex());
        for (int i = 0; i < tree.getChildCount(); i++) collectTypeOffsets(tree.getChild(i), offsets);
    }

    public void setClient(LanguageClient client) {
        projects.connect(client);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var document = params.getTextDocument();
        projects.open(document.getUri(), document.getText(), document.getVersion());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        if (!params.getContentChanges().isEmpty()) {
            var document = params.getTextDocument();
            projects.change(document.getUri(), params.getContentChanges().getLast().getText(), document.getVersion());
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        projects.close(params.getTextDocument().getUri());
    }

    // ==========================================
    // 2. Semantic Highlighting Tokens
    // ==========================================

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        projects.refresh();
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        String content = projects.text(params.getTextDocument().getUri());
        if (content == null || content.isEmpty()) {
            return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
        }

        CharStream stream = CharStreams.fromString(content);
        VernacLexer lexer = new VernacLexer(stream);
        lexer.removeErrorListeners();

        List<RawToken> collected = new ArrayList<>();

        String[] sourceLines = content.split("\n", -1);
        Set<Integer> typeOffsets = new HashSet<>();
        var parser = new VernacParser(new CommonTokenStream(new VernacLexer(CharStreams.fromString(content))));
        parser.removeErrorListeners();
        collectTypeOffsets(parser.compilationUnit(), typeOffsets);
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            String text = token.getText();
            int line = token.getLine() - 1;
            int startChar = sourceLines[line].offsetByCodePoints(0, token.getCharPositionInLine());
            Integer type;
            if (typeOffsets.contains(token.getStartIndex())) {
                type = 1;
            } else {
                type = classifyToken(text);
            }
            if (type == null) continue;
            // LSP tokens cannot span lines unless the client explicitly supports it.
            String[] fragments = text.split("\n", -1);
            for (int i = 0; i < fragments.length; i++) {
                String fragment = fragments[i];
                if (fragment.endsWith("\r")) fragment = fragment.substring(0, fragment.length() - 1);
                if (!fragment.isEmpty()) collected.add(new RawToken(line + i, i == 0 ? startChar : 0,
                        fragment.length(), type));
            }
        }

        // Strictly sort: line first, then column; on equality, longer token first
        collected.sort(Comparator.comparingInt((RawToken t) -> t.line)
                .thenComparingInt(t -> t.startChar)
                .thenComparingInt(t -> -t.length));

        // Filter duplicates & overlaps
        List<RawToken> nonOverlapping = new ArrayList<>();
        int curLine = -1;
        int curEndChar = -1;

        for (RawToken t : collected) {
            if (t.line != curLine) {
                curLine = t.line;
                curEndChar = t.startChar + t.length;
                nonOverlapping.add(t);
            } else if (t.startChar >= curEndChar) {
                curEndChar = t.startChar + t.length;
                nonOverlapping.add(t);
            }
        }

        // Calculate relative deltas exactly according to LSP specification
        List<Integer> data = new ArrayList<>();
        int prevLine = 0;
        int prevChar = 0;

        for (RawToken t : nonOverlapping) {
            int deltaLine = t.line - prevLine;
            int deltaChar = (deltaLine == 0) ? (t.startChar - prevChar) : t.startChar;

            data.add(deltaLine);
            data.add(deltaChar);
            data.add(t.length);
            data.add(t.tokenType);
            data.add(0);

            prevLine = t.line;
            prevChar = t.startChar;
        }

        return CompletableFuture.completedFuture(new SemanticTokens(data));
    }

    private Integer classifyToken(String tokenText) {
        if (DSL_KEYWORDS.contains(tokenText) || JAVA_KEYWORDS.contains(tokenText)) {
            return 0; // Keyword
        }
        if (tokenText.startsWith("//") || tokenText.startsWith("/*")) {
            return 5; // Comment
        }
        if (tokenText.startsWith("\"") && tokenText.endsWith("\"")) {
            return 3; // String
        }
        if (tokenText.matches("\\d+(\\.\\d+)?")) {
            return 4; // Number
        }
        if (!tokenText.isEmpty() && Character.isUpperCase(tokenText.codePointAt(0)) && VernacNames.isIdentifier(tokenText)) {
            return 1; // Type
        }
        return null;
    }

    // ==========================================
    // 3. Autocompletion (Context-Aware)
    // ==========================================

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        String uri = position.getTextDocument().getUri();
        String content = projects.text(uri);
        if (content == null || content.isEmpty()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }

        var symbols = projects.complete(uri, position.getPosition());
        if (symbols != null) return CompletableFuture.completedFuture(Either.forLeft(symbols));

        Position pos = position.getPosition();
        String prefix = getPrefixUpToCursor(content, pos.getLine(), pos.getCharacter());

        List<CompletionItem> items = new ArrayList<>();

        // Context A: After "for" in repositories -> Only aggregates
        if (prefix.matches("(?s).*\\brepository\\s+[\\p{L}\\p{N}\\p{M}_$]+\\s+for\\s+[\\p{L}\\p{N}\\p{M}_$]*$")) {
            addAggregateCompletions(items, content);
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        // Context A2: After "listener" -> Only declared events
        if (prefix.matches("(?s).*\\blistener\\s+[\\p{L}\\p{N}\\p{M}_$]*$")) {
            addEventCompletions(items, content);
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        // Context C1: Inside a usecase { ... } block
        if (isInsideUseCaseBlock(prefix)) {
            addKeywordCompletion(items, "use", "use ${1:Repository};");
            addKeywordCompletion(items, "load", "load ${1:Aggregate} by ${2:id};");
            addKeywordCompletion(items, "save", "save ${1:instance};");
            addKeywordCompletion(items, "return", "return ($1);");
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }
        // Context C2: Inside a repository { ... } block
        if (isInsideRepositoryBlock(prefix)) {
            addKeywordCompletion(items, "table", "table: \"${1:table_name}\";");
            addKeywordCompletion(items, "find", "find ${1:ReturnType} ${2:methodName}(${3:params});");
            addKeywordCompletion(items, "custom", "custom ${1:ReturnType} ${2:methodName}(${3:params});");
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        // Context D: Top-level keywords
        addTopLevelCompletions(items);

        return CompletableFuture.completedFuture(Either.forLeft(items));
    }

    private String getPrefixUpToCursor(String content, int lineIndex, int charIndex) {
        String[] lines = content.split("\r?\n", -1);
        if (lineIndex < 0 || lineIndex >= lines.length) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineIndex; i++) {
            sb.append(lines[i]).append("\n");
        }
        String currentLine = lines[lineIndex];
        if (charIndex >= 0 && charIndex <= currentLine.length()) {
            sb.append(currentLine, 0, charIndex);
        } else {
            sb.append(currentLine);
        }
        return sb.toString();
    }

    private boolean isInsideUseCaseBlock(String prefix) {
        int lastUseCase = prefix.lastIndexOf("usecase ");
        if (lastUseCase == -1) return false;
        int lastBraceOpen = prefix.lastIndexOf('{');
        int lastBraceClose = prefix.lastIndexOf('}');
        return lastBraceOpen > lastUseCase && lastBraceOpen > lastBraceClose;
    }

    private boolean isInsideRepositoryBlock(String prefix) {
        int lastRepo = prefix.lastIndexOf("repository ");
        if (lastRepo == -1) return false;
        int lastBraceOpen = prefix.lastIndexOf('{');
        int lastBraceClose = prefix.lastIndexOf('}');
        return lastBraceOpen > lastRepo && lastBraceOpen > lastBraceClose;
    }

    private void addAggregateCompletions(List<CompletionItem> items, String content) {
        Pattern pattern = Pattern.compile("\\baggregate\\s+(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String aggregateName = matcher.group(1);
            if (!VernacNames.isTypeName(aggregateName)) continue;
            CompletionItem item = new CompletionItem(aggregateName);
            item.setKind(CompletionItemKind.Class);
            item.setDetail("Vernac Aggregate Root");
            item.setInsertText(aggregateName);
            items.add(item);
        }
    }

    private void addTopLevelCompletions(List<CompletionItem> items) {
        addKeywordCompletion(items, "namespace", "namespace ${1:com.example};");
        addKeywordCompletion(items, "import", "import ${1:package.Type};");
        addKeywordCompletion(items, "aggregate", "aggregate ${1:Name}[${2:IdType} id](\n    $0\n);");
        addKeywordCompletion(items, "value", "value ${1:Name}(${2:Type} value);");
        addKeywordCompletion(items, "value (enum)", "value ${1:Name} = ${2:CONST1} | ${3:CONST2};");
        addKeywordCompletion(items, "entity", "entity ${1:Name}[${2:IdType} id](\n    $0\n);");
        addKeywordCompletion(items, "event (outbox)", "outbox event ${1:Name}(${2:Type} value);");
        addKeywordCompletion(items, "event (memory)", "memory event ${1:Name}(${2:Type} value);");
        addKeywordCompletion(items, "listener", "listener ${1:EventName} {\n    $0\n}");
        addKeywordCompletion(items, "port", "port ${1:Name} {\n    $0\n}");
        addKeywordCompletion(items, "repository", "repository ${1:Name} for ${2:Aggregate} {\n    table: \"${3:table_name}\";\n    $0\n};");
        addKeywordCompletion(items, "id", "id ${1:Name}Id;");
        addKeywordCompletion(items, "usecase", "usecase ${1:Name}(${2:params}) {\n    $0\n}");
        addKeywordCompletion(items, "service", "service ${1:Name}(${2:params}) : ${3:ReturnType} {\n    $0\n}");
    }

    private void addKeywordCompletion(List<CompletionItem> list, String label, String insertSnippet) {
        CompletionItem item = new CompletionItem(label);
        item.setKind(CompletionItemKind.Keyword);
        item.setInsertText(insertSnippet);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        list.add(item);
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        return CompletableFuture.completedFuture(unresolved);
    }

    // ==========================================
    // 4. Hover
    // ==========================================

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        MarkupContent content = new MarkupContent(MarkupKind.MARKDOWN, "**Vernac Domain Model**");
        return CompletableFuture.completedFuture(new Hover(content));
    }

    // ==========================================
    // 5. Go to Definition
    // ==========================================

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.completedFuture(Either.forLeft(
                projects.definition(params.getTextDocument().getUri(), params.getPosition())));
    }

    // ==========================================
    // Internal Helper Structures
    // ==========================================

    private void addEventCompletions(List<CompletionItem> items, String content) {
        Pattern pattern = Pattern.compile("\\b(?:outbox\\s+|memory\\s+)?event\\s+(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String eventName = matcher.group(1);
            if (!VernacNames.isTypeName(eventName)) continue;
            CompletionItem item = new CompletionItem(eventName);
            item.setKind(CompletionItemKind.Event);
            item.setDetail("Vernac Domain Event");
            item.setInsertText(eventName);
            items.add(item);
        }
    }

    private static class RawToken {
        final int line;
        final int startChar;
        final int length;
        final int tokenType;

        RawToken(int line, int startChar, int length, int tokenType) {
            this.line = line;
            this.startChar = startChar;
            this.length = length;
            this.tokenType = tokenType;
        }
    }
}