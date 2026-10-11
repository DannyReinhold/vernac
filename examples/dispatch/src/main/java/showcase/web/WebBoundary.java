package showcase.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** The example is local-only. JSON mutations reject cross-origin browser requests. */
@Component
public final class WebBoundary extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src"
            + " 'none'; base-uri 'self'; frame-ancestors 'none'");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    if (request.getRequestURI().startsWith("/api/") && !request.getMethod().equals("GET")) {
      String origin = request.getHeader("Origin");
      String expected =
          request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
      if (!"application/json".equalsIgnoreCase(request.getContentType())
          || (origin != null && !origin.equals(expected))) {
        response.sendError(403);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
