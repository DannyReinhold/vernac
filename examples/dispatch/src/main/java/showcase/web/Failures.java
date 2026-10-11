package showcase.web;

import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.vernac.runtime.DomainValidationException;

@RestControllerAdvice
public final class Failures {
  @ExceptionHandler(DomainValidationException.class)
  ResponseEntity<Map<String, String>> rejected(DomainValidationException error) {
    return ResponseEntity.unprocessableEntity().body(Map.of("message", error.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<Map<String, String>> invalid(Exception error) {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Check the supplied fields and identifiers."));
  }

  @ExceptionHandler(org.vernac.runtime.AggregateNotFoundException.class)
  ResponseEntity<Map<String, String>> missing(Exception error) {
    return ResponseEntity.status(404)
        .body(Map.of("message", "The tour no longer exists. Refresh the board."));
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<Map<String, String>> conflict(Exception error) {
    return ResponseEntity.status(409)
        .body(Map.of("message", "This tour changed in another request. Refresh and try again."));
  }
}
