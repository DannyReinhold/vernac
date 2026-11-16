// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package acceptance.support;

import java.lang.reflect.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.saas.payments.domain.*;
import org.vernac.saas.payments.local.LocalPayments;
import org.vernac.saas.mail.domain.MailProvider;
import org.vernac.saas.invoicing.domain.InvoiceRenderer;

@Configuration(proxyBeanMethods=false)
@Import(AcceptanceControls.class)
public class AcceptanceConfiguration {
    public static final class Time extends Clock {
        private final AtomicReference<Instant> value;
        private final Path retained;
        Time(Environment environment)throws Exception {
            retained=Path.of(environment.getRequiredProperty("acceptance.clock-file"));
            value=new AtomicReference<>(Files.exists(retained)?Instant.parse(Files.readString(retained)):Instant.parse("2026-01-15T12:00:00Z"));
        }
        public synchronized void advance(long seconds)throws Exception {
            if(seconds<0 || seconds>400L*86400)throw new IllegalArgumentException();
            value.updateAndGet(old->old.plusSeconds(seconds));Files.writeString(retained,value.get().toString());
        }
        public Instant instant(){return value.get();}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
    }
    public static final class Faults {
        volatile List<LocalPayments.Step> scenario=List.of(LocalPayments.Step.PAID);
        volatile boolean render,mail;
        final AtomicBoolean lostCheckout=new AtomicBoolean(),lostRefund=new AtomicBoolean();
    }
    @Bean @Primary Time acceptanceClock(Environment environment)throws Exception{return new Time(environment);}
    @Bean Faults acceptanceFaults(){return new Faults();}
    @Bean static BeanPostProcessor acceptanceBoundaries(ObjectProvider<Faults> faults,
            ObjectProvider<NamedParameterJdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> manager,ObjectProvider<Time> time) {
        return new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean,String name) {
                Class<?> boundary=bean instanceof PaymentProvider?PaymentProvider.class:bean instanceof MailProvider?MailProvider.class:bean instanceof InvoiceRenderer?InvoiceRenderer.class:null;
                if(boundary==null)return bean;
                return Proxy.newProxyInstance(boundary.getClassLoader(),new Class<?>[]{boundary},(proxy,method,args)-> {
                    if(method.getDeclaringClass()==Object.class) return switch(method.getName()){case "toString"->"AcceptanceBoundary[REDACTED]";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;};
                    var f=faults.getObject();
                    if(boundary==InvoiceRenderer.class && method.getName().equals("render") && f.render)throw new IllegalStateException("Acceptance renderer unavailable");
                    if(boundary==MailProvider.class && method.getName().equals("submit") && f.mail)throw new IllegalStateException("Acceptance mail unavailable");
                    if(boundary==PaymentProvider.class && method.getName().equals("inspect"))
                        return new LocalPayments(jdbc.getObject(),manager.getObject(),f.scenario,time.getObject()).inspect((RemotePaymentId)args[0]);
                    Object result;
                    try {result=method.invoke(bean,args);}catch(InvocationTargetException failed){throw failed.getCause();}
                    if(boundary==PaymentProvider.class && (method.getName().equals("checkout")&&f.lostCheckout.getAndSet(false)
                            || method.getName().equals("refund")&&f.lostRefund.getAndSet(false)))
                        throw new org.vernac.saas.payments.PaymentFailure(org.vernac.saas.payments.PaymentFailure.Code.OUTCOME_UNKNOWN);
                    return result;
                });
            }
        };
    }
}
