// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.*;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.payments.persistence.*;

/** Server-set request capability; customer owner roles and tenant claims never grant merchant authority. */
final class MerchantAccess {
    private static final String KEY=MerchantAccess.class.getName()+".verified";
    private static final Object VERIFIED=new Object();
    static void open(HttpServletRequest request) {request.setAttribute(KEY,VERIFIED);}
    static void close(HttpServletRequest request) {request.removeAttribute(KEY);}
    static void requireAuthorized(AccountId ignored) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes bound)
                || bound.getRequest().getAttribute(KEY)!=VERIFIED)
            throw new PaymentProcessingFailure(PaymentProcessingFailure.Code.ACCESS_DENIED);
    }
}
