// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.vernac.runtime.tenancy.*;
import org.vernac.saas.identity.domain.*;

import java.time.*;
import java.util.*;

class IdentityAccountChainTest {
    static final AccountId ACCOUNT = AccountId.create();
    static final UserId MEMBER = UserId.create();
    static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    static class Accounts implements IdentityAccountActions {
        boolean allowed = true;
        int writes, selections;
        IdentityAccountException.Code failure;
        Set<String> assigned = Set.of();

        void check() {
            assertTrue(TenantContext.currentTenant().isEmpty());
            if (failure != null) throw new IdentityAccountException(failure);
        }

        public List<AccountView> accounts() {
            check();
            return List.of(new AccountView(ACCOUNT, "PERSONAL", true));
        }

        public List<MemberView> members(AccountId account) {
            check();
            return List.of(
                    new MemberView(
                            MEMBER,
                            false,
                            true,
                            Set.of("example.reader"),
                            "<script>private@example.test</script>"));
        }

        public List<InvitationView> invitations(AccountId account) {
            check();
            return List.of();
        }

        public List<String> availableRoles() {
            check();
            return List.of("example.reader", "example.editor");
        }

        public AccountId createOrganization() {
            check();
            writes++;
            return ACCOUNT;
        }

        public TenantContext.Scope select(AccountId account) {
            check();
            selections++;
            if (!allowed || !account.equals(ACCOUNT))
                throw new IdentityAccountException(IdentityAccountException.Code.ACCESS_DENIED);
            return TenantContext.openVerified(TENANT);
        }

        public void assignRoles(AccountId account, UserId subject, Collection<String> roles) {
            check();
            assigned = Set.copyOf(roles);
            writes++;
        }

        public void removeMember(AccountId account, UserId subject) {
            check();
            writes++;
        }

        public void transferOwnership(AccountId account, UserId successor) {
            check();
            writes++;
        }

        public void closeAccount(AccountId account) {
            check();
            writes++;
        }

        public void revokeInvitation(AccountId account, InvitationId invitation) {
            check();
            writes++;
        }

        public void disableIdentity() {
            check();
            writes++;
        }

        public void closeIdentity() {
            check();
            writes++;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class Application {
        @Bean
        Accounts accounts() {
            return new Accounts();
        }

        @Bean
        IdentityInvitationChainTest.Invitations actions() {
            return new IdentityInvitationChainTest.Invitations();
        }

        @Bean
        AuthenticationManager manager() {
            return new IdentityJourneyChainTest.Application().manager();
        }

        @Bean
        SecurityFilterChain chain(
                HttpSecurity http,
                AuthenticationManager manager,
                Accounts accounts,
                IdentityInvitationChainTest.Invitations actions)
                throws Exception {
            return IdentityBrowserSecurity.build(
                    http,
                    manager,
                    new IdentitySessionTrust(
                            id -> OptionalLong.of(0),
                            Clock.fixed(IdentityJourneyChainTest.NOW, ZoneOffset.UTC)),
                    new IdentityLocalReturnTarget("/workspace"),
                    () -> new IdentityRequestAdmission.Decision(true, 0),
                    actions,
                    accounts,
                    new IdentityBrowserSecurity.Extensions(
                            request ->
                                    request.getServletPath().equals("/billing")
                                            || request.getServletPath().equals("/billing/change"),
                            Set.of("/billing/change")));
        }
    }

    AnnotationConfigWebApplicationContext context;
    Filter chain;
    Accounts accounts;

    @BeforeEach
    void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Application.class);
        context.refresh();
        chain = context.getBean("springSecurityFilterChain", Filter.class);
        accounts = context.getBean(Accounts.class);
    }

    @AfterEach
    void stop() {
        context.close();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertTrue(TenantContext.currentTenant().isEmpty());
    }

    MockHttpServletRequest request(String method, String path, MockHttpSession session) {
        var r = new MockHttpServletRequest(method, path);
        r.setServletPath(path);
        r.setSecure(true);
        r.setScheme("https");
        r.setRemoteAddr("192.0.2.44");
        if (session != null) {
            r.setSession(session);
            r.setRequestedSessionId(session.getId());
            r.setRequestedSessionIdValid(true);
        }
        return r;
    }

    boolean productFailure;

    MockHttpServletResponse dispatch(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        chain.doFilter(
                request,
                response,
                (a, b) -> {
                    if (request.getServletPath().startsWith("/billing")) {
                        assertTrue(TenantContext.currentTenant().isEmpty());
                        b.getWriter().write("global metadata");
                        return;
                    }
                    assertEquals(TENANT, TenantContext.requireTenant());
                    assertEquals(
                            ACCOUNT,
                            IdentityAccountSelectionFilter.requireAccount(
                                    (jakarta.servlet.http.HttpServletRequest) a));
                    if (productFailure)
                        throw new IllegalStateException("synthetic product failure");
                    b.getWriter().write("scoped product");
                });
        return response;
    }

    void body(MockHttpServletRequest request, String text) {
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    MockHttpSession login() throws Exception {
        var get = request("GET", "/identity/login", null);
        var page = dispatch(get);
        var post = request("POST", "/identity/login", (MockHttpSession) get.getSession(false));
        body(
                post,
                IdentityJourneyChainTest.form(
                        Map.of(
                                "address",
                                "member@example.test",
                                "password",
                                "secret",
                                "_csrf",
                                IdentityJourneyChainTest.csrf(page))));
        assertEquals(303, dispatch(post).getStatus());
        return (MockHttpSession) post.getSession(false);
    }

    String token(MockHttpSession session) throws Exception {
        return IdentityJourneyChainTest.csrf(dispatch(request("GET", "/auth/accounts", session)));
    }

    MockHttpServletResponse post(String path, MockHttpSession session, Map<String, String> values)
            throws Exception {
        var fields = new HashMap<>(values);
        fields.put("_csrf", token(session));
        var request = request("POST", path, session);
        body(request, IdentityJourneyChainTest.form(fields));
        return dispatch(request);
    }

    @Test
    void accountPagesRequireAuthenticationAndMutationsRequireCsrfBeforeInvokingThePort()
            throws Exception {
        assertEquals(401, dispatch(request("GET", "/auth/accounts", null)).getStatus());
        var session = login();
        var noCsrf = request("POST", "/auth/account/create", session);
        body(noCsrf, "");
        assertEquals(403, dispatch(noCsrf).getStatus());
        assertEquals(0, accounts.writes);
        var page = dispatch(request("GET", "/auth/accounts", session));
        assertEquals(200, page.getStatus());
        assertTrue(page.getContentAsString().contains("Open workspace"));
        assertTrue(page.getContentAsString().contains("Change password"));
    }

    @Test
    void selectionStoresOnlyVerifiedAccountAndBindsItsMappedTenantForEachProductRequest()
            throws Exception {
        var session = login();
        var unselected = dispatch(request("GET", "/workspace", session));
        assertEquals(303, unselected.getStatus());
        assertEquals("/auth/accounts", unselected.getHeader("Location"));
        assertEquals(0, accounts.selections);
        var response =
                post(
                        "/auth/account/select",
                        session,
                        Map.of(
                                "account",
                                ACCOUNT.asString(),
                                "tenant",
                                UUID.randomUUID().toString(),
                                "returnTo",
                                "//evil.test"));
        assertEquals(303, response.getStatus());
        assertEquals("/workspace", response.getHeader("Location"));
        assertEquals(
                ACCOUNT.asString(),
                session.getAttribute(IdentityAccountSelectionFilter.SELECTED_ACCOUNT));
        assertEquals(
                "scoped product",
                dispatch(request("GET", "/workspace", session)).getContentAsString());
        assertEquals(2, accounts.selections);
        assertTrue(TenantContext.currentTenant().isEmpty());
        accounts.allowed = false;
        assertEquals(403, dispatch(request("GET", "/workspace", session)).getStatus());
        assertEquals(
                ACCOUNT.asString(),
                session.getAttribute(IdentityAccountSelectionFilter.SELECTED_ACCOUNT));
        assertFalse(session.isInvalid());
    }

    @Test
    void productFailuresCloseScopeAndGlobalAccountActionsNeverInheritSelectedTenant()
            throws Exception {
        var session = login();
        post("/auth/account/select", session, Map.of("account", ACCOUNT.asString()));
        productFailure = true;
        assertThrows(
                IllegalStateException.class, () -> dispatch(request("GET", "/workspace", session)));
        assertTrue(TenantContext.currentTenant().isEmpty());
        productFailure = false;
        assertEquals(303, post("/auth/account/create", session, Map.of()).getStatus());
        assertEquals(1, accounts.writes);
        assertTrue(TenantContext.currentTenant().isEmpty());
    }

    @Test
    void ownerScreensEscapeScopedMemberDataAndRoleCsvSupportsTheFiniteCatalogAssignmentBound()
            throws Exception {
        var session = login();
        var get = request("GET", "/auth/account", session);
        get.setParameter("account", ACCOUNT.asString());
        var page = dispatch(get);
        assertEquals(200, page.getStatus());
        assertFalse(page.getContentAsString().contains("<script>private"));
        assertTrue(page.getContentAsString().contains("&lt;script&gt;private"));
        assertTrue(page.getContentAsString().contains("Transfer my ownership"));
        var longRoles = new ArrayList<String>();
        for (int i = 0; i < 32; i++) {
            String prefix = "example.role" + i;
            longRoles.add(prefix + "a".repeat(256 - prefix.length()));
        }
        var response =
                post(
                        "/auth/account/roles",
                        session,
                        Map.of(
                                "account",
                                ACCOUNT.asString(),
                                "subject",
                                MEMBER.asString(),
                                "roles",
                                String.join(",", longRoles)));
        assertEquals(303, response.getStatus());
        assertEquals(32, accounts.assigned.size());
        assertEquals(
                400,
                post(
                                "/auth/account/roles",
                                session,
                                Map.of(
                                        "account",
                                        ACCOUNT.asString(),
                                        "subject",
                                        MEMBER.asString(),
                                        "roles",
                                        "example.reader,example.reader"))
                        .getStatus());
    }

    @Test
    void lastOwnerAndTechnicalFailuresHaveSafeStatusAndDoNotLogOutOrSilentlySwitchAccount()
            throws Exception {
        var session = login();
        String csrf = token(session);
        accounts.failure = IdentityAccountException.Code.LAST_OWNER;
        var post = request("POST", "/auth/account/remove", session);
        body(
                post,
                IdentityJourneyChainTest.form(
                        Map.of(
                                "account",
                                ACCOUNT.asString(),
                                "subject",
                                MEMBER.asString(),
                                "_csrf",
                                csrf)));
        var response = dispatch(post);
        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("Transfer ownership"));
        assertFalse(session.isInvalid());
        accounts.failure = IdentityAccountException.Code.UNAVAILABLE;
        var get = request("GET", "/auth/accounts", session);
        response = dispatch(get);
        assertEquals(503, response.getStatus());
        assertEquals("Account operation unavailable", response.getContentAsString());
        assertFalse(session.isInvalid());
    }

    @Test
    void explicitGlobalMetadataRetainsAuthenticationCsrfAndBoundedFormsWithoutProductScope()
            throws Exception {
        assertEquals(401, dispatch(request("GET", "/billing", null)).getStatus());
        var session = login();
        assertEquals(
                "global metadata",
                dispatch(request("GET", "/billing", session)).getContentAsString());
        assertEquals(0, accounts.selections);
        var missing = request("POST", "/billing/change", session);
        body(missing, "account=" + ACCOUNT.asString());
        assertEquals(403, dispatch(missing).getStatus());
        var valid = request("POST", "/billing/change", session);
        body(
                valid,
                IdentityJourneyChainTest.form(
                        Map.of("_csrf", token(session), "account", ACCOUNT.asString())));
        assertEquals("global metadata", dispatch(valid).getContentAsString());
        var duplicate = request("POST", "/billing/change", session);
        body(duplicate, "account=one&account=two&_csrf=" + token(session));
        assertEquals(400, dispatch(duplicate).getStatus());
        post("/auth/account/select", session, Map.of("account", ACCOUNT.asString()));
        var product = request("GET", "/workspace", session);
        assertEquals("scoped product", dispatch(product).getContentAsString());
        assertThrows(
                IdentityAccountException.class,
                () -> IdentityAccountSelectionFilter.requireAccount(product));
        assertEquals(
                "global metadata",
                dispatch(request("GET", "/billing", session)).getContentAsString());
        assertTrue(TenantContext.currentTenant().isEmpty());
    }
}
