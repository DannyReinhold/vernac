package org.vernac.lsp;

import org.antlr.v4.runtime.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.vernac.compiler.parser.VernacLexer;
import org.vernac.compiler.parser.VernacParser;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VernacTextDocumentService implements TextDocumentService {

    // DSL & Module Keywords
    private static final Set<String> DSL_KEYWORDS = Set.of(
            "package", "import", "as",
            "aggregate", "value", "entity", "event", "repository", "for",
            "validates", "require", "mut"
    );
    // Java Keywords für Code-Blöcke
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
    private static final Pattern COMMENT_LINE = Pattern.compile("//.*");
    private static final Pattern COMMENT_BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
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

    // --- 1. Syntax Validierung ---

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

        try {
            parser.compilationUnit();
        } catch (Exception ignored) {
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

    // --- 2. Semantic Highlighting Tokens ---

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

            // 1. Einfache Direkt-Matches (einzelne saubere Tokens)
            Integer type = classifyToken(text.trim());
            if (type != null) {
                // Bei getrimmten Tokens den führenden Leerraum-Offset berücksichtigen
                int leadingSpaces = text.indexOf(text.trim());
                collected.add(new RawToken(line, startChar + Math.max(0, leadingSpaces), text.trim().length(), type));
            } else if (text.length() > 1 && (text.contains(" ") || text.contains("\n") || text.contains("{") || text.contains("("))) {
                // 2. Verbundene Fragmente (Methodenköpfe wie "public void foo()", Signaturen oder { ... }-Blöcke)
                lexCompositeFragment(text, line, startChar, collected);
            }
        }

        collected.sort(Comparator.comparingInt((RawToken t) -> t.line).thenComparingInt(t -> t.startChar));

        List<Integer> data = new ArrayList<>();
        int prevLine = 0;
        int prevChar = 0;

        for (RawToken t : collected) {
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

            // Strings
            Matcher stringMatcher = STRING_LITERAL.matcher(currentLine);
            while (stringMatcher.find()) {
                tokens.add(new RawToken(currentLineNum, stringMatcher.start() + offsetCorrection, stringMatcher.group().length(), 3));
            }

            // Zahlen
            Matcher numMatcher = NUMBER_LITERAL.matcher(currentLine);
            while (numMatcher.find()) {
                tokens.add(new RawToken(currentLineNum, numMatcher.start() + offsetCorrection, numMatcher.group().length(), 4));
            }

            // Wörter (Keywords wie public, private, void, return, Typen wie OrderId)
            Matcher idMatcher = IDENTIFIER_OR_KEYWORD.matcher(currentLine);
            while (idMatcher.find()) {
                String word = idMatcher.group();
                int start = idMatcher.start() + offsetCorrection;

                if (DSL_KEYWORDS.contains(word) || JAVA_KEYWORDS.contains(word)) {
                    tokens.add(new RawToken(currentLineNum, start, word.length(), 0)); // Keyword
                } else if (Character.isUpperCase(word.charAt(0))) {
                    tokens.add(new RawToken(currentLineNum, start, word.length(), 1)); // Type
                }
            }
        }
    }

    private Integer classifyToken(String tokenText) {
        // Indexe: 0=Keyword, 1=Type, 2=Variable, 3=String, 4=Number, 5=Comment, 6=Function
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

        // Typen: PascalCase
        if (Character.isUpperCase(tokenText.charAt(0)) && tokenText.matches("[A-Z][a-zA-Z0-9_]*")) {
            return 1; // Type
        }

        return null;
    }

    // Hilfsmethode, falls der ANTLR-Parser Java-Codeblöcke als einen unzerlegten String liefert
    private void lexJavaBlock(String blockText, int baseLine, int baseChar, List<RawToken> tokens) {
        String[] lines = blockText.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String currentLine = lines[i];
            int currentLineNum = baseLine + i;
            int offsetCorrection = (i == 0) ? baseChar : 0;

            // Strings hervorheben
            Matcher stringMatcher = STRING_LITERAL.matcher(currentLine);
            while (stringMatcher.find()) {
                tokens.add(new RawToken(currentLineNum, stringMatcher.start() + offsetCorrection, stringMatcher.group().length(), 3));
            }

            // Zahlen hervorheben
            Matcher numMatcher = NUMBER_LITERAL.matcher(currentLine);
            while (numMatcher.find()) {
                tokens.add(new RawToken(currentLineNum, numMatcher.start() + offsetCorrection, numMatcher.group().length(), 4));
            }

            // Identifier & Keywords
            Matcher idMatcher = IDENTIFIER_OR_KEYWORD.matcher(currentLine);
            while (idMatcher.find()) {
                String word = idMatcher.group();
                int start = idMatcher.start() + offsetCorrection;
                if (JAVA_KEYWORDS.contains(word)) {
                    tokens.add(new RawToken(currentLineNum, start, word.length(), 0)); // Keyword
                } else if (Character.isUpperCase(word.charAt(0))) {
                    tokens.add(new RawToken(currentLineNum, start, word.length(), 1)); // Type
                }
            }
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        List<CompletionItem> items = new ArrayList<>();

        addKeywordCompletion(items, "package", "package ${1:com.example.domain};");
        addKeywordCompletion(items, "import", "import ${1:package.Type};");
        addKeywordCompletion(items, "aggregate", "aggregate ${1:Name}[${2:IdType}](\n    $0\n);");
        addKeywordCompletion(items, "value", "value ${1:Name}(${2:Type} value);");
        addKeywordCompletion(items, "entity", "entity ${1:Name}[${2:IdType}](\n    $0\n);");
        addKeywordCompletion(items, "event", "event ${1:Name}(${2:Type} value);");
        addKeywordCompletion(items, "repository", "repository ${1:Name} for ${2:Aggregate} {\n    table: \"${3:table_name}\";\n};");
        addKeywordCompletion(items, "validates", "validates {\n    require(${1:condition}, \"${2:Message}\");\n}");
        addKeywordCompletion(items, "mut", "mut ");

        return CompletableFuture.completedFuture(Either.forLeft(items));
    }

    // --- 3. Autovervollständigung ---

    private void addKeywordCompletion(List<CompletionItem> list, String label, String insertSnippet) {
        CompletionItem item = new CompletionItem(label);
        item.setKind(CompletionItemKind.Keyword);
        item.setInsertText(insertSnippet);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        list.add(item);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        MarkupContent content = new MarkupContent(MarkupKind.MARKDOWN, "**Vernac Domain Model**");
        return CompletableFuture.completedFuture(new Hover(content));
    }

    // --- 4. Hover ---

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