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
    private final ExportDocument export;
    WorkspaceController(ExportDocument export) {this.export=export;}

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

}
