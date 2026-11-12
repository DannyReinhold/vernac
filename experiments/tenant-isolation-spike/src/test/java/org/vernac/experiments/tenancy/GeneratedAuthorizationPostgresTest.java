// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.experiments.tenancy;

import static org.junit.jupiter.api.Assertions.*;
import static org.vernac.experiments.tenancy.PostgresFixture.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.*;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.authorization.*;
import org.vernac.runtime.authorization.spring.SpringSecurityActorResolver;
import org.vernac.runtime.events.EventHandler;

/** Compiles actual .vernac guards, then invokes their real Spring proxies and restricted-role SQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class GeneratedAuthorizationPostgresTest {
    PostgresFixture fixture;
    URLClassLoader classes;
    AnnotationConfigApplicationContext context;
    TenantBoundary boundary;
    InvoiceStore store;
    Policy policy;
    Class<?> idType, flagType, probe;
    Object id;

    private static final String MODEL = """
        namespace sample.auth;
        import sample.security.Rights;
        id InvoiceId;
        value Label(String);
        value Flag(boolean);
        usecase ExportInvoice(InvoiceId) returns Label
            requires all {
                permission Rights.VIEW on invoiceId;
                permission Rights.EXPORT on invoiceId;
            }
            validates { require(fixture.Probe.validate(), "precondition"); }
        behavior {
            execute { return Label.of(fixture.Probe.export(invoiceId.value())); }
        }
        usecase ExternalInvoice(InvoiceId) returns Label
            requires permission Rights.EXPORT on invoiceId
        behavior { execute implemented by fixture.External; }
        usecase Outer(InvoiceId, Flag callInner, Flag catchFailure) returns Label
            requires public uses ExportInvoice exporter
        behavior {
            execute {
                fixture.Probe.writeOuter();
                if (callInner.booleanValue()) {
                    try { return self.exporter().execute(invoiceId); }
                    catch (org.vernac.runtime.authorization.AuthorizationException denied) {
                        if (!catchFailure.booleanValue()) throw denied;
                    }
                }
                return Label.of("outer");
            }
        }
        usecase SignedIn() requires authenticated behavior { execute { fixture.Probe.signedIn(); } }
        usecase PublicEntry() requires public behavior { execute { fixture.Probe.publicCalls++; } }
        """;
    private static final String PROBE = """
        package fixture;
        public final class Probe {
            public static int validations, bodies, publicCalls;
            public static String actorKind;
            public static java.util.function.Function<java.util.UUID,String> exporter;
            public static Runnable outer;
            public static boolean validate() { validations++; return true; }
            public static String export(java.util.UUID id) { bodies++; return exporter.apply(id); }
            public static void writeOuter() { outer.run(); }
            public static void signedIn() {
                bodies++;
                actorKind = org.vernac.runtime.authorization.AuthorizationGuard.currentActor().orElseThrow().kind().name();
            }
        }
        """;
    private static final String EXTERNAL = """
        package fixture;
        public final class External {
            public static sample.auth.domain.Label execute(sample.auth.usecase.access.ExternalInvoiceAccess self,
                    sample.auth.domain.InvoiceId invoiceId) {
                return sample.auth.domain.Label.of(Probe.export(invoiceId.value()));
            }
        }
        """;
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @BeforeAll void compileAndDatabase(@TempDir Path temporary) throws Exception {
        Path sources = temporary.resolve("source");
        write(sources.resolve("sample/auth/model.vernac"), MODEL);
        write(sources.resolve("sample/security/rights.vernac"), "namespace sample.security; permission Rights {VIEW; EXPORT; String; name; values; qualifiedName;}");
        Path java = temporary.resolve("java"), output = Files.createDirectories(temporary.resolve("classes"));
        new VernacCompiler().compileProject(sources).writeTo(java);
        write(java.resolve("fixture/Probe.java"), PROBE); write(java.resolve("fixture/External.java"), EXTERNAL);
        var arguments = new ArrayList<>(List.of("--release", "21", "-parameters", "-g", "-classpath",
                System.getProperty("java.class.path"), "-d", output.toString()));
        try (var files = Files.walk(java)) { files.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add); }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        classes = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader());
        idType = classes.loadClass("sample.auth.domain.InvoiceId"); flagType = classes.loadClass("sample.auth.domain.Flag");
        probe = classes.loadClass("fixture.Probe"); id = idType.getMethod("of", UUID.class).invoke(null, INVOICE_A);
        fixture = new PostgresFixture();
    }
    @AfterAll void close() throws Exception { if (fixture != null) fixture.close(); if (classes != null) classes.close(); }
    @BeforeEach void configure() throws Exception {
        fixture.reset();
        boundary = new TenantBoundary(fixture.pool, (actor, tenant) -> actor.equals("member") && (tenant.equals(A) || tenant.equals(B)));
        store = new InvoiceStore(new JdbcTemplate(fixture.pool), boundary); policy = new Policy(store);
        context = new AnnotationConfigApplicationContext(); context.setClassLoader(classes);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(fixture.pool));
        context.registerBean(Policy.class, () -> policy);
        context.registerBean(AuthorizationGuard.class, () -> new AuthorizationGuard(new SpringSecurityActorResolver(auth ->
                Optional.of(new VerifiedActor("fixture", auth.getName(), auth.getName().equals("worker") ? VerifiedActor.Kind.TECHNICAL : VerifiedActor.Kind.HUMAN))), context.getBean(Policy.class)));
        ObjectProvider<EventHandler> events = context.getBeanFactory().getBeanProvider(EventHandler.class);
        register("exporter", "ExportInvoice", () -> construct("ExportInvoice", events, context.getBean(AuthorizationGuard.class)));
        register("external", "ExternalInvoice", () -> construct("ExternalInvoice", events, context.getBean(AuthorizationGuard.class)));
        register("outer", "Outer", () -> construct("Outer", events, context.getBean("exporter"), context.getBeanFactory().getBeanProvider(AuthorizationGuard.class)));
        register("signed", "SignedIn", () -> construct("SignedIn", events, context.getBean(AuthorizationGuard.class)));
        register("publicEntry", "PublicEntry", () -> construct("PublicEntry", events, context.getBeanFactory().getBeanProvider(AuthorizationGuard.class)));
        context.register(Transactions.class); context.refresh();
        for (String name : List.of("validations", "bodies", "publicCalls")) probe.getField(name).setInt(null, 0);
        probe.getField("exporter").set(null, (Function<UUID,String>) identity -> {
            var invoice = store.byId(identity); invoice.title = "exported"; store.save(invoice); return "done";
        });
        probe.getField("outer").set(null, (Runnable) () -> { var invoice = store.byId(INVOICE_A); invoice.title = "outer"; store.save(invoice); });
        authenticate("member");
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext(); if (context != null) context.close();
        assertTrue(AuthorizationGuard.currentActor().isEmpty());
        assertTrue(org.vernac.runtime.tenancy.TenantContext.currentTenant().isEmpty());
        assertTrue(org.vernac.runtime.tenancy.TenantContext.transactionTenant().isEmpty());
    }
    public static class Policy implements PermissionEvaluator {
        final InvoiceStore store;
        final List<PermissionRequest> calls = new ArrayList<>();
        boolean view = true, export;
        Policy(InvoiceStore store) { this.store = store; }
        @Override @Transactional(propagation = Propagation.MANDATORY)
        public PermissionDecision evaluate(PermissionRequest request) {
            calls.add(request);
            assertEquals("sample.auth.domain.InvoiceId", request.resource().type().getName());
            UUID identity;
            try { identity = (UUID) request.resource().value().getClass().getMethod("value").invoke(request.resource().value()); }
            catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); }
            try { store.byId(identity); }
            catch (TenantBoundary.Failure absent) { if (!absent.getMessage().equals("NOT_FOUND")) throw absent; return PermissionDecision.DENY; }
            boolean allowed = request.permission().qualifiedName().endsWith(".VIEW") ? view : export;
            return allowed ? PermissionDecision.ALLOW : PermissionDecision.DENY;
        }
    }
    @Test void nullChecksAndPermissionDenialsPrecedeGeneratedValidationAndInlineBody() throws Exception {
        SecurityContextHolder.clearContext(); assertThrows(DomainValidationException.class, () -> call("exporter", (Object) null));
        assertTrue(policy.calls.isEmpty()); assertEquals(0, count("validations"));
        authenticate("member");
        denied(AuthorizationException.Code.ACCESS_DENIED, () -> scoped(() -> call("exporter", id)));
        assertEquals(0, count("validations")); assertEquals(0, count("bodies")); assertEquals(1, version());
        policy.export = true;
        assertEquals("done", scoped(() -> text(call("exporter", id))));
        assertEquals(1, count("validations")); assertEquals(1, count("bodies")); assertEquals(2, version());
        assertEquals("sample.auth.ExportInvoice", policy.calls.getLast().operation());
        assertEquals("sample.security.Rights.EXPORT", policy.calls.getLast().permission().qualifiedName());
    }
    @Test void generatedPublicOuterChecksOnlyReachedInnerAndRetainsRequiredRollback() throws Exception {
        assertEquals("outer", scoped(() -> text(call("outer", id, flag(false), flag(false)))));
        assertTrue(policy.calls.isEmpty()); assertEquals(2, version());
        denied(AuthorizationException.Code.ACCESS_DENIED, () -> scoped(() -> call("outer", id, flag(true), flag(false))));
        assertEquals(2, version());
        assertThrows(UnexpectedRollbackException.class, () -> scoped(() -> call("outer", id, flag(true), flag(true))));
        assertEquals(2, version());
        policy.export = true;
        assertEquals("done", scoped(() -> text(call("outer", id, flag(true), flag(false)))));
        assertEquals(4, version());
    }
    @Test void externalImplementationHasTheSameGuardAndDirectConstructionCannotSupplyATransaction() throws Exception {
        denied(AuthorizationException.Code.ACCESS_DENIED, () -> scoped(() -> call("external", id)));
        assertEquals(0, count("bodies")); policy.export = true;
        assertEquals("done", scoped(() -> text(call("external", id)))); assertEquals(1, count("bodies"));
        var manual = construct("ExportInvoice", context.getBeanFactory().getBeanProvider(EventHandler.class), context.getBean(AuthorizationGuard.class));
        denied(AuthorizationException.Code.AUTHORIZATION_CONFIGURATION_ERROR, () -> invoke(manual, id));
    }
    @Test void publicAndAuthenticatedModesPreserveTheirDifferentActorRequirements() throws Exception {
        SecurityContextHolder.clearContext(); call("publicEntry"); assertEquals(1, count("publicCalls"));
        denied(AuthorizationException.Code.AUTHENTICATION_REQUIRED, () -> call("signed"));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("fixture", "anonymous",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        denied(AuthorizationException.Code.AUTHENTICATION_REQUIRED, () -> call("signed"));
        authenticate("member"); call("signed"); assertEquals("HUMAN", probe.getField("actorKind").get(null));
        authenticate("worker"); call("signed"); assertEquals("TECHNICAL", probe.getField("actorKind").get(null));
        assertTrue(policy.calls.isEmpty());
    }
    @Test void crossTenantPolicyReadsCannotAuthorizeTheSameNominalId() throws Exception {
        policy.export = true;
        try (var selected = boundary.select(B)) {
            denied(AuthorizationException.Code.ACCESS_DENIED, () -> call("exporter", id));
        }
        assertEquals(0, count("bodies")); assertEquals(1, version());
    }
    @Test void publicEntryWorksWithoutAuthorizationBeansButProtectedBeanFailsStartup() throws Exception {
        try (var publicContext = new AnnotationConfigApplicationContext()) {
            publicContext.setClassLoader(classes); publicContext.register(Transactions.class);
            publicContext.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(fixture.pool));
            registerAutowire(publicContext, "PublicEntry"); publicContext.refresh();
            invoke(publicContext.getBean("publicEntry")); assertEquals(1, count("publicCalls"));
        }
        try (var protectedContext = new AnnotationConfigApplicationContext()) {
            protectedContext.setClassLoader(classes); registerAutowire(protectedContext, "ExportInvoice");
            assertThrows(org.springframework.beans.BeansException.class, protectedContext::refresh);
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerAutowire(AnnotationConfigApplicationContext target, String name) throws ClassNotFoundException {
        target.registerBean((Class) classes.loadClass("sample.auth.usecase." + name));
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void register(String bean, String name, Supplier<Object> supplier) throws ClassNotFoundException {
        context.registerBean(bean, (Class) classes.loadClass("sample.auth.usecase." + name), supplier);
    }
    private Object construct(String name, Object... arguments) {
        try { return classes.loadClass("sample.auth.usecase." + name).getConstructors()[0].newInstance(arguments); }
        catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); }
    }
    private Object call(String bean, Object... arguments) { return invoke(context.getBean(bean), arguments); }
    private Object invoke(Object receiver, Object... arguments) {
        try {
            var execute = Arrays.stream(receiver.getClass().getMethods()).filter(m -> m.getName().equals("execute")).findFirst().orElseThrow();
            return execute.invoke(receiver, arguments);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new AssertionError(failed.getCause());
        } catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); }
    }
    private Object flag(boolean value) { try { return flagType.getMethod("of", boolean.class).invoke(null, value); } catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); } }
    private String text(Object label) { try { return (String) label.getClass().getMethod("string").invoke(label); } catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); } }
    private <T> T scoped(Supplier<T> body) { try (var selected = boundary.select(A)) { return body.get(); } }
    private long version() { return new JdbcTemplate(fixture.adminSource()).queryForObject("SELECT version FROM tenant_spike.invoice WHERE id=?", Long.class, INVOICE_A); }
    private int count(String name) throws Exception { return probe.getField(name).getInt(null); }
    private static void authenticate(String actor) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of())); }
    private static void denied(AuthorizationException.Code code, org.junit.jupiter.api.function.Executable action) { assertEquals(code, assertThrows(AuthorizationException.class, action).code()); }
    private static void write(Path path, String source) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path, source); }
}
