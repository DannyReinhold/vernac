// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import example.documentworkspace.domain.DocumentSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.View;

/** Writes the model returned by the guarded export use case as a UTF-8 attachment. */
@Component("document-download")
public final class DocumentDownload implements View {
    @Override
    public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        var document = (DocumentSummary) model.get("document");
        response.setContentType("text/plain");
        response.setHeader("Content-Disposition", "attachment; filename=\"document.txt\"");
        response.getOutputStream().write((document.title().text() + "\n\n" + document.text().text())
                .getBytes(StandardCharsets.UTF_8));
    }
}
