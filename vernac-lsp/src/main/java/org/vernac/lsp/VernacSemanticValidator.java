package org.vernac.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.vernac.compiler.analyzer.CompilerDiagnostic;
import org.vernac.compiler.analyzer.SemanticAnalyzer;
import org.vernac.compiler.ast.CompilationUnitNode;
import org.vernac.compiler.ast.SourceLocation;

import java.util.List;

public class VernacSemanticValidator {

    private final SemanticAnalyzer analyzer = new SemanticAnalyzer();

    public List<Diagnostic> validate(CompilationUnitNode cu) {
        List<CompilerDiagnostic> compilerDiagnostics = analyzer.analyze(cu);

        return compilerDiagnostics.stream()
                .map(this::toLspDiagnostic)
                .toList();
    }

    private Diagnostic toLspDiagnostic(CompilerDiagnostic cd) {
        SourceLocation loc = cd.location();
        int line = Math.max(0, loc.line() - 1);
        int startCol = Math.max(0, loc.column() - 1);
        int endCol = startCol + 1;

        Range range = new Range(new Position(line, startCol), new Position(line, endCol));
        Diagnostic d = new Diagnostic(range, cd.message());

        DiagnosticSeverity severity = switch (cd.severity()) {
            case WARNING -> DiagnosticSeverity.Warning;
            case ERROR -> DiagnosticSeverity.Error;
        };

        d.setSeverity(severity);
        d.setSource("vernac-semantic");
        return d;
    }
}