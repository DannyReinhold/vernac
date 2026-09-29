package org.vernac.lsp;

import org.antlr.v4.runtime.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.vernac.compiler.ast.AstBuilderVisitor;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VernacTextDocumentService implements TextDocumentService {

    private static final Set<String> DSL_KEYWORDS = Set.of(
            "package", "import", "as",
            "aggregate", "value", "entity", "event", "service", "external", "schema",
            "repository", "for", "table", "find", "custom", "validates", "require",
            "mut", "invariant", "mapping"
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

    private static final Pattern IDENTIFIER_OR_KEYWORD = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fFdDlL]?\\b");

    private final Map<String, String> documentContents = new ConcurrentHashMap<>();
    private LanguageClient client;

    public void setClient(LanguageClient client) {
        this.client = client;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        documentContents.put(uri, text);
        validateDocument(uri, text);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        if (!params.getContentChanges().isEmpty()) {
            String uri = params.getTextDocument().getUri();
            String text = params.getContentChanges().getFirst().getText();
            documentContents.put(uri, text);
            validateDocument(uri, text);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        documentContents.remove(uri);
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    // ==========================================
    // 1. Validierung (Syntax & Semantik)
    // ==========================================

    private void validateDocument(String uri, String content) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        CharStream stream = CharStreams.fromString(content);
        VernacLexer lexer = new VernacLexer(stream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                diagnostics.add(toDiagnostic(line, charPositionInLine, msg));
            }
        });

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        VernacParser parser = new VernacParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                diagnostics.add(toDiagnostic(line, charPositionInLine, msg));
            }
        });

        VernacParser.CompilationUnitContext tree = null;
        try {
            tree = parser.compilationUnit();
        } catch (Exception ignored) {
        }

        // Semantische Validierung via AST nur ausführen, wenn keine reinen Syntax-Fehler vorliegen
        if (diagnostics.isEmpty() && tree != null) {
            try {
                AstBuilderVisitor astBuilder = new AstBuilderVisitor();
                CompilationUnitNode ast = astBuilder.visitCompilationUnit(tree);
                if (ast != null) {
                    VernacSemanticValidator semanticValidator = new VernacSemanticValidator();
                    diagnostics.addAll(semanticValidator.validate(ast));
                }
            } catch (Exception ignored) {
                // Fängt Übergangszustände beim Tippen im Editor ab
            }
        }

        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
    }

    private Diagnostic toDiagnostic(int line, int charPositionInLine, String msg) {
        Position start = new Position(Math.max(0, line - 1), Math.max(0, charPositionInLine));
        Position end = new Position(Math.max(0, line - 1), Math.max(0, charPositionInLine + 1));
        Diagnostic d = new Diagnostic(new Range(start, end), msg);
        d.setSeverity(DiagnosticSeverity.Error);
        d.setSource("vernac");
        return d;
    }

    // ==========================================
    // 2. Semantic Highlighting Tokens
    // ==========================================

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        String content = documentContents.get(params.getTextDocument().getUri());
        if (content == null || content.isEmpty()) {
            return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
        }

        CharStream stream = CharStreams.fromString(content);
        VernacLexer lexer = new VernacLexer(stream);
        lexer.removeErrorListeners();

        List<RawToken> collected = new ArrayList<>();

        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            String text = token.getText();
            int line = token.getLine() - 1;
            int startChar = token.getCharPositionInLine();

            // 1. Einfache Direkt-Tokens (Keywords, Typen, Strings, etc.)
            Integer type = classifyToken(text.trim());
            if (type != null) {
                int leadingSpaces = Math.max(0, text.indexOf(text.trim()));
                collected.add(new RawToken(line, startChar + leadingSpaces, text.trim().length(), type));
            } else if (text.length() > 1 && (text.contains(" ") || text.contains("\n") || text.contains("{") || text.contains("("))) {
                // 2. Nur wenn es kein einzelnes Token war, Unterfragmente zerlegen
                lexCompositeFragment(text, line, startChar, collected);
            }
        }

        // Streng sortieren: Zuerst Zeile, dann Spalte, bei Gleichheit längeres Token zuerst
        collected.sort(Comparator.comparingInt((RawToken t) -> t.line)
                .thenComparingInt(t -> t.startChar)
                .thenComparingInt(t -> -t.length));

        // Duplikate & Überlappungen filtern
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

        // Relative Deltas exakt nach LSP-Spezifikation berechnen
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

    private void lexCompositeFragment(String fragmentText, int baseLine, int baseChar, List<RawToken> tokens) {
        String[] lines = fragmentText.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String currentLine = lines[i];
            int currentLineNum = baseLine + i;
            int offsetCorrection = (i == 0) ? baseChar : 0;

            Matcher stringMatcher = STRING_LITERAL.matcher(currentLine);
            while (stringMatcher.find()) {
                tokens.add(new RawToken(currentLineNum, stringMatcher.start() + offsetCorrection, stringMatcher.group().length(), 3));
            }

            Matcher numMatcher = NUMBER_LITERAL.matcher(currentLine);
            while (numMatcher.find()) {
                tokens.add(new RawToken(currentLineNum, numMatcher.start() + offsetCorrection, numMatcher.group().length(), 4));
            }

            Matcher idMatcher = IDENTIFIER_OR_KEYWORD.matcher(currentLine);
            while (idMatcher.find()) {
                String word = idMatcher.group();
                int start = idMatcher.start() + offsetCorrection;

                if (DSL_KEYWORDS.contains(word) || JAVA_KEYWORDS.contains(word)) {
                    tokens.add(new RawToken(currentLineNum, start, word.length(), 0));
                } else if (Character.isUpperCase(word.charAt(0))) {
                    tokens.add(new RawToken(currentLineNum, start, word.length(), 1));
                }
            }
        }
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
        if (!tokenText.isEmpty() && Character.isUpperCase(tokenText.charAt(0)) && tokenText.matches("[A-Z][a-zA-Z0-9_]*")) {
            return 1; // Type
        }
        return null;
    }

    // ==========================================
    // 3. Autovervollständigung (Context-Aware)
    // ==========================================

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        String uri = position.getTextDocument().getUri();
        String content = documentContents.get(uri);
        if (content == null || content.isEmpty()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }

        Position pos = position.getPosition();
        String prefix = getPrefixUpToCursor(content, pos.getLine(), pos.getCharacter());

        List<CompletionItem> items = new ArrayList<>();

        // Kontext A: Nach "for" bei Repositories -> Nur Aggregate
        if (prefix.matches("(?s).*\\brepository\\s+\\w+\\s+for\\s+\\w*$")) {
            addAggregateCompletions(items, content);
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        // Kontext B: Typ-Position (nach '[', '<', ':', 'mut', 'find', 'custom' oder in Parameterliste)
        if (isTypeExpected(prefix)) {
            Set<String> seenTypes = new HashSet<>();
            addModelDeclaredTypes(items, content, seenTypes);
            addStandardTypeCompletions(items, seenTypes);
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        // Kontext C: Innerhalb eines repository { ... } Blocks
        if (isInsideRepositoryBlock(prefix)) {
            addKeywordCompletion(items, "table", "table: \"${1:table_name}\";");
            addKeywordCompletion(items, "find", "find ${1:ReturnType} ${2:methodName}(${3:params});");
            addKeywordCompletion(items, "custom", "custom ${1:ReturnType} ${2:methodName}(${3:params});");
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        // Kontext D: Top-Level Keywords
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

    private boolean isTypeExpected(String prefix) {
        String trimmed = prefix.trim();

        if (trimmed.matches("(?s).*\\[\\s*\\w*$")) return true;
        if (trimmed.matches("(?s).*<\\s*\\w*$")) return true;
        if (trimmed.matches("(?s).*:\\s*\\w*$")) return true;
        if (trimmed.matches("(?s).*\\bmut\\s+\\w*$")) return true;
        if (trimmed.matches("(?s).*\\b(find|custom)\\s+\\w*$")) return true;
        if (trimmed.matches("(?s).*\\b(public|internal|private)\\s+\\w*$")) return true;

        int lastParenOpen = trimmed.lastIndexOf('(');
        int lastParenClose = trimmed.lastIndexOf(')');
        if (lastParenOpen > lastParenClose) {
            String insideParams = trimmed.substring(lastParenOpen + 1).trim();
            if (insideParams.isEmpty() || insideParams.matches(".*,\\s*\\w*$")) {
                return true;
            }
        }
        return false;
    }

    private boolean isInsideRepositoryBlock(String prefix) {
        int lastRepo = prefix.lastIndexOf("repository ");
        if (lastRepo == -1) return false;
        int lastBraceOpen = prefix.lastIndexOf('{');
        int lastBraceClose = prefix.lastIndexOf('}');
        return lastBraceOpen > lastRepo && lastBraceOpen > lastBraceClose;
    }

    private void addStandardTypeCompletions(List<CompletionItem> items, Set<String> alreadyAdded) {
        List<String> types = List.of(
                "UUID", "String", "Integer", "Long", "BigDecimal", "Boolean",
                "Instant", "LocalDate", "List<${1:Type}>", "Set<${1:Type}>"
        );

        for (String type : types) {
            String baseName = type.replaceAll("<.*>", "");
            if (alreadyAdded.add(baseName)) {
                CompletionItem item = new CompletionItem(baseName);
                item.setKind(CompletionItemKind.Class);
                item.setInsertText(type);
                if (type.contains("${")) {
                    item.setInsertTextFormat(InsertTextFormat.Snippet);
                }
                item.setDetail("Primitive / JDK Type");
                items.add(item);
            }
        }
    }

    private void addModelDeclaredTypes(List<CompletionItem> items, String content, Set<String> alreadyAdded) {
        Pattern pattern = Pattern.compile("\\b(value|entity|aggregate|event)\\s+([A-Z][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String kind = matcher.group(1);
            String typeName = matcher.group(2);

            if (alreadyAdded.add(typeName)) {
                CompletionItem item = new CompletionItem(typeName);
                item.setKind(kind.equals("value") ? CompletionItemKind.Struct : CompletionItemKind.Class);
                item.setDetail("Vernac " + kind);
                item.setInsertText(typeName);
                items.add(item);
            }
        }
    }

    private void addAggregateCompletions(List<CompletionItem> items, String content) {
        Pattern pattern = Pattern.compile("\\baggregate\\s+([A-Z][a-zA-Z0-9_]*)");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String aggregateName = matcher.group(1);
            CompletionItem item = new CompletionItem(aggregateName);
            item.setKind(CompletionItemKind.Class);
            item.setDetail("Vernac Aggregate Root");
            item.setInsertText(aggregateName);
            items.add(item);
        }
    }

    private void addTopLevelCompletions(List<CompletionItem> items) {
        addKeywordCompletion(items, "package", "package ${1:com.example.domain};");
        addKeywordCompletion(items, "import", "import ${1:package.Type};");
        addKeywordCompletion(items, "aggregate", "aggregate ${1:Name}[${2:IdType} id](\n    $0\n);");
        addKeywordCompletion(items, "value", "value ${1:Name}(${2:Type} value);");
        addKeywordCompletion(items, "entity", "entity ${1:Name}[${2:IdType} id](\n    $0\n);");
        addKeywordCompletion(items, "event", "event ${1:Name}(${2:Type} value);");
        addKeywordCompletion(items, "service", "service ${1:Name} {\n    $0\n}");
        addKeywordCompletion(items, "repository", "repository ${1:Name} for ${2:Aggregate} {\n    table: \"${3:table_name}\";\n    $0\n};");
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
        String uri = params.getTextDocument().getUri();
        String content = documentContents.get(uri);
        if (content == null || content.isEmpty()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }

        Position pos = params.getPosition();
        String wordUnderCursor = getWordAtPosition(content, pos.getLine(), pos.getCharacter());
        if (wordUnderCursor == null || wordUnderCursor.isBlank()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }

        Location targetLocation = findDeclaration(uri, content, wordUnderCursor);
        if (targetLocation != null) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of(targetLocation)));
        }

        return CompletableFuture.completedFuture(Either.forLeft(List.of()));
    }

    private String getWordAtPosition(String content, int lineIndex, int charIndex) {
        String[] lines = content.split("\r?\n", -1);
        if (lineIndex < 0 || lineIndex >= lines.length) {
            return null;
        }

        String line = lines[lineIndex];
        if (charIndex < 0 || charIndex > line.length()) {
            return null;
        }

        int start = charIndex;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }

        int end = charIndex;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
            end++;
        }

        if (start == end) {
            return null;
        }
        return line.substring(start, end);
    }

    private Location findDeclaration(String uri, String content, String targetName) {
        String[] lines = content.split("\r?\n", -1);
        Pattern pattern = Pattern.compile("\\b(aggregate|value|entity|event)\\s+(" + Pattern.quote(targetName) + ")\\b");

        for (int i = 0; i < lines.length; i++) {
            Matcher m = pattern.matcher(lines[i]);
            if (m.find()) {
                int startChar = m.start(2);
                int endChar = m.end(2);

                Range range = new Range(
                        new Position(i, startChar),
                        new Position(i, endChar)
                );
                return new Location(uri, range);
            }
        }
        return null;
    }

    // ==========================================
    // Interne Hilfsstrukturen
    // ==========================================

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