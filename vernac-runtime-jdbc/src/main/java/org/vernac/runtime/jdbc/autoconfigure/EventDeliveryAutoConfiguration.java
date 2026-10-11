// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc.autoconfigure;

import java.time.Duration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.vernac.runtime.events.*;
import org.vernac.runtime.jdbc.*;

/** Polls only when a deferred handler is registered. Recorded-only applications need no delivery table. */
@NullMarked
@AutoConfiguration(after={JdbcTemplateAutoConfiguration.class,TransactionAutoConfiguration.class})
@ConditionalOnBean({NamedParameterJdbcTemplate.class,PlatformTransactionManager.class,EventStorage.class})
@ConditionalOnProperty(prefix="vernac.events",name="enabled",havingValue="true",matchIfMissing=true)
public class EventDeliveryAutoConfiguration {
    @Bean @ConditionalOnMissingBean @DependsOnDatabaseInitialization
    public JdbcEventWorker vernacEventWorker(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,
            ObjectProvider<EventHandler> handlers,Environment environment) {
        var defaults=EventDeliveryOptions.defaults();
        var options=new EventDeliveryOptions(environment.getProperty("vernac.events.max-attempts",Integer.class,defaults.maxAttempts()),
                duration(environment,"lease",defaults.lease()),duration(environment,"initial-backoff",defaults.initialBackoff()),
                duration(environment,"max-backoff",defaults.maxBackoff()));
        return new JdbcEventWorker(jdbc,manager,()->handlers.stream().toList(),options);
    }
    @Bean
    public SmartLifecycle vernacEventPolling(JdbcEventWorker worker,ObjectProvider<EventHandler> handlers,Environment environment) {
        long delay=duration(environment,"poll-interval",Duration.ofSeconds(1)).toMillis();
        if(delay<1) throw new IllegalArgumentException("vernac.events.poll-interval must be at least one millisecond");
        return new Polling(worker,handlers,delay);
    }
    private static Duration duration(Environment environment,String name,Duration fallback) {
        // ISO-8601 avoids depending on application-specific conversion services.
        return Duration.parse(environment.getProperty("vernac.events."+name,fallback.toString()));
    }
    private static final class Polling implements SmartLifecycle {
        private final JdbcEventWorker worker;
        private final ObjectProvider<EventHandler> handlers;
        private final long delay;
        private volatile boolean running;
        private @Nullable ScheduledExecutorService executor;
        Polling(JdbcEventWorker worker,ObjectProvider<EventHandler> handlers,long delay) {
            this.worker=worker;this.handlers=handlers;this.delay=delay;
        }
        public synchronized void start() {
            if(running || handlers.stream().noneMatch(h->!(h instanceof EventStorage) && h.deferred())) return;
            executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"vernac-events");t.setDaemon(true);return t;});
            running=true;
            executor.scheduleWithFixedDelay(()->{
                if(!running)return;
                try { worker.runOnce(); }
                catch(RuntimeException error) {
                    System.getLogger(Polling.class.getName()).log(System.Logger.Level.ERROR,
                            "Event worker infrastructure failure: "+error.getClass().getName());
                }
            },0,delay,TimeUnit.MILLISECONDS);
        }
        public synchronized void stop() {
            running=false;
            if(executor!=null)executor.shutdown();
        }
        public void stop(Runnable callback) {
            stop();
            // Let the current transaction finish before dependent database beans shut down.
            var current=executor;
            if(current==null || current.isTerminated()) { callback.run();return; }
            Thread waiter=new Thread(()->{
                try { while(!current.awaitTermination(1,TimeUnit.SECONDS)) { } }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { callback.run(); }
            },"vernac-events-shutdown");
            waiter.setDaemon(true);waiter.start();
        }
        public boolean isRunning(){return running;}
        public int getPhase(){return Integer.MAX_VALUE;}
    }
}
