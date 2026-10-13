// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.inbound;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.logging.*;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.vernac.runtime.*;
import org.vernac.runtime.json.*;
import org.vernac.runtime.ports.PortRejectedException;

/** Safe HTTP defaults, scoped to generated controllers through their generated advice. */
@NullMarked
public final class InboundHttpErrors {
    private InboundHttpErrors() { }
    public record Body(String code, String message) { }
    public record Reply(int status, String code, String message) {
        public Reply {
            if(status<400 || status>599)throw new IllegalArgumentException("Error status must be 4xx or 5xx");
            Objects.requireNonNull(code);Objects.requireNonNull(message);
            if(code.isBlank() || message.isBlank())throw new IllegalArgumentException("Error code and message are required");
        }
    }
    public static final class InvocationFailure extends RuntimeException {
        private final String endpoint;
        private final Exception exception;
        private InvocationFailure(String endpoint, Exception exception) {
            super("Inbound endpoint failed: " + endpoint,exception);this.endpoint=endpoint;this.exception=exception;
        }
        public String endpoint() {return endpoint;}
        public Exception exception() {return exception;}
    }
    public static final class ResponseMappingFailure extends VernacTechnicalException {
        public ResponseMappingFailure(Exception cause) {super("Failed to produce the HTTP response",cause);}
    }
    public static InvocationFailure invocation(String endpoint,Exception error) {
        return error instanceof InvocationFailure existing ? existing : new InvocationFailure(endpoint,error);
    }
    public static InvocationFailure response(String endpoint,Exception error) {
        return new InvocationFailure(endpoint,new ResponseMappingFailure(error));
    }
    public static Reply defaultReply(Exception error) {
        if(error instanceof InvocationFailure invocation)return defaultReply(invocation.exception());
        if(error instanceof HttpMessageNotReadableException || error instanceof MissingRequestValueException
                || error instanceof MethodArgumentTypeMismatchException || error instanceof WireValueException || error instanceof DtoContractException)
            return new Reply(400,"INVALID_REQUEST","Check the supplied fields and identifiers.");
        if(error instanceof AggregateNotFoundException)return new Reply(404,"NOT_FOUND","The requested resource was not found.");
        if(error instanceof OptimisticLockingFailureException)return new Reply(409,"OPTIMISTIC_LOCK_CONFLICT","The resource changed. Reload it before retrying.");
        if(error instanceof MappingContractException && error.getCause() instanceof Exception cause
                && (cause instanceof MappingContractException || cause instanceof DomainValidationException || cause instanceof DtoContractException))
            return defaultReply(cause);
        if(error instanceof org.vernac.runtime.pagination.InvalidCursorException)
            return new Reply(400,"INVALID_CURSOR","Start a new traversal with the same search criteria.");
        if(error instanceof DomainValidationException)return new Reply(422,"DOMAIN_VALIDATION","The supplied values do not meet the domain requirements.");
        if(error instanceof PortRejectedException rejected)return new Reply(422,rejected.code().name(),"The operation was rejected.");
        if(error instanceof VernacDomainException)return new Reply(422,"DOMAIN_REJECTED","The operation was rejected.");
        return new Reply(500,"INTERNAL_ERROR","The operation could not be completed.");
    }
    public static ResponseEntity<Body> respond(Exception error) {return respond(error,InboundHttpErrors::defaults);}
    private static Reply defaults(String endpoint,Exception error) {return defaultReply(error);}
    public static ResponseEntity<Body> respond(Exception error,BiFunction<String,Exception,Reply> handler) {
        String endpoint=error instanceof InvocationFailure f?f.endpoint():"request";
        Exception cause=error instanceof InvocationFailure f?f.exception():error;
        Reply reply;
        try {reply=Objects.requireNonNull(handler.apply(endpoint,cause),"Error handler returned null");}
        catch(Exception handlerError) {cause=handlerError;reply=new Reply(500,"INTERNAL_ERROR","The operation could not be completed.");}
        if(reply.status()>=500)Logger.getLogger(InboundHttpErrors.class.getName()).log(Level.SEVERE,"HTTP inbound failure at " + endpoint,cause);
        return ResponseEntity.status(reply.status()).body(new Body(reply.code(),reply.message()));
    }
}
