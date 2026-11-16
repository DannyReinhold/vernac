// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import example.documentworkspace.domain.*;
import example.documentworkspace.usecase.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
class WorkspaceController {
    private final ListDocuments list;
    private final CreateDocument create;
    private final DeleteDocument delete;
    private final ExportDocument export;

    WorkspaceController(
            ListDocuments list,
            CreateDocument create,
            DeleteDocument delete,
            ExportDocument export) {
        this.list = list;
        this.create = create;
        this.delete = delete;
        this.export = export;
    }

    @GetMapping(value = "/workspace", produces = "text/html;charset=UTF-8")
    ResponseEntity<String> workspace(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String token = hidden(csrf.getParameterName(), csrf.getToken());
        var body =
                new StringBuilder(
                        "<!doctype html><html lang=\"de\"><meta charset=\"utf-8\"><meta"
                            + " name=\"viewport\""
                            + " content=\"width=device-width,initial-scale=1\"><title>Document"
                            + " Workspace</title><main><h1>Deine Dokumente</h1><p><a"
                            + " href=\"/billing\">Tarif und Abrechnung</a> · <a"
                            + " href=\"/auth/accounts\">Konten und Mitglieder</a></p><ul>");
        for (var doc : list.execute())
            body.append("<li><strong>")
                    .append(escape(doc.title().text()))
                    .append("</strong><p>")
                    .append(escape(doc.text().text()))
                    .append("</p><form method=\"post\" action=\"/documents/export\">")
                    .append(token)
                    .append(hidden("id", doc.id().asString()))
                    .append(
                            "<button>Als Text exportieren</button></form><form method=\"post\""
                                + " action=\"/documents/delete\">")
                    .append(token)
                    .append(hidden("id", doc.id().asString()))
                    .append("<button>Dokument löschen</button></form></li>");
        body.append(
                        "</ul><h2>Neues Dokument</h2><form method=\"post\""
                            + " action=\"/documents/create\">")
                .append(token)
                .append(
                        "<p><label>Titel <input name=\"title\" maxlength=\"120\""
                            + " required></label></p><p><label>Text <textarea name=\"text\""
                            + " maxlength=\"10000\""
                            + " required></textarea></label></p><button>Dokument"
                            + " anlegen</button></form></main></html>");
        return ResponseEntity.ok().headers(headers()).body(body.toString());
    }

    @PostMapping("/documents/create")
    ResponseEntity<Void> create(HttpServletRequest request) {
        fields(request, "title", "text");
        create.execute(
                DocumentTitle.of(value(request, "title")), DocumentText.of(value(request, "text")));
        return ResponseEntity.status(303)
                .headers(headers())
                .header("Location", "/workspace")
                .build();
    }

    @PostMapping("/documents/delete")
    ResponseEntity<Void> delete(HttpServletRequest request) {
        fields(request, "id");
        delete.execute(id(request));
        return ResponseEntity.status(303)
                .headers(headers())
                .header("Location", "/workspace")
                .build();
    }

    @PostMapping("/documents/export")
    ResponseEntity<byte[]> export(HttpServletRequest request) {
        fields(request, "id");
        var doc = export.execute(id(request));
        return ResponseEntity.ok()
                .headers(headers())
                .contentType(MediaType.TEXT_PLAIN)
                .header("Content-Disposition", "attachment; filename=\"document.txt\"")
                .body(
                        (doc.title().text() + "\n\n" + doc.text().text())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static HttpHeaders headers() {
        var h = new HttpHeaders();
        h.setCacheControl("no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Content-Type-Options", "nosniff");
        h.set(
                "Content-Security-Policy",
                "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        return h;
    }

    static void fields(HttpServletRequest request, String... names) {
        var allowed = new HashSet<>(List.of(names));
        allowed.add(
                ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getParameterName());
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw new IllegalArgumentException();
    }

    static String value(HttpServletRequest request, String name) {
        var values = request.getParameterValues(name);
        if (values == null || values.length != 1) throw new IllegalArgumentException();
        return values[0];
    }

    static DocumentId id(HttpServletRequest request) {
        String text = value(request, "id");
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException();
        return DocumentId.of(id);
    }

    static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\""
                + escape(name)
                + "\" value=\""
                + escape(value)
                + "\">";
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
