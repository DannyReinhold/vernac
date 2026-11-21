---
title: Formatting Vernac files
---
# Formatting Vernac files

In IntelliJ, open a `.vernac`, `.vapplication` or `.vmodule` file and choose
**Code → Reformat Code** (the IDE's normal shortcut). The formatter uses four-space
indentation, separate declarations and one declaration/member per line. It is a
file-level formatter; selecting a fragment does not rewrite the rest of the file.

The compiler lexer/parser identify syntax, comments and embedded Java. Formatting
changes whitespace outside Java bodies. Comments, string contents/escapes and the
complete embedded Java bodies are retained. In particular, Java text is not split
at semicolons or reformatted by a second text parser. Existing Java-injection and
debugger source mapping continue to use the resulting source positions.

Syntactically incomplete files are left untouched. A final lexical comparison
rejects any result that would change a token or comment. Reformatting a formatted
file produces identical text. CRLF files retain CRLF layout; formatting does not
rewrite the line endings inside preserved Java/comment text.

For example:

```vernac
permission WorkspacePermissions {
    VIEW;
    EDIT;
    EXPORT;
}

role example.workspace_reader {
    permission WorkspacePermissions.VIEW;
}
```

The document workspace and identity examples are formatted with the same
implementation: `org.vernac.compiler.format.VernacFormatter`. Tests cover nested
permissions/roles/usecases, comments, escaped strings, supplementary Unicode,
Java islands, idempotence, identical lexical tokens, unchanged generated Java,
application/module files and the IntelliJ service/packaged plugin registration.
