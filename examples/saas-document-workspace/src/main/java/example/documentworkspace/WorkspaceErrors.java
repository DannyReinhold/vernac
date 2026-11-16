// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.vernac.runtime.authorization.AuthorizationException;

/** Safe standard authorization transport outcomes for this handwritten controller. */
@RestControllerAdvice
class WorkspaceErrors {
    @ExceptionHandler({
        org.vernac.saas.restrictions.RestrictionFailure.class,
        org.vernac.runtime.DomainValidationException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<String> invalid(RuntimeException failure) {
        return ResponseEntity.status(409)
                .header("Cache-Control", "no-store")
                .body("Aktion nicht möglich. Bitte Eingaben, Tarif und verfügbares Limit prüfen.");
    }

    @ExceptionHandler(AuthorizationException.class)
    ResponseEntity<String> authorization(AuthorizationException failure) {
        int status =
                switch (failure.code()) {
                    case AUTHENTICATION_REQUIRED -> 401;
                    case ACCESS_DENIED, SCOPE_REQUIRED -> 403;
                    case AUTHORIZATION_UNAVAILABLE -> 503;
                    case AUTHORIZATION_CONFIGURATION_ERROR -> 500;
                };
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(failure.code().name());
    }
}
