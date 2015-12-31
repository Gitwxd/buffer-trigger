/**
 * 
 */
package com.github.phantomthief.collection.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import com.github.phantomthief.collection.BufferTrigger;
import com.github.phantomthief.util.ThrowableConsumer;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

@SuppressWarnings("unchecked")
public class SimpleBufferTriggerBuilder<E, C> {

    private ScheduledExecutorService scheduledExecutorService;
    private Supplier<C> bufferFactory;
    private BiPredicate<C, E> queueAdder;
    private ThrowableConsumer<C, Throwable> consumer;
    private BiConsumer<Throwable, C> exceptionHandler;
    private long maxBufferCount = -1;
    private Consumer<E> rejectHandler;
    private long warningBufferThreshold;
    private LongConsumer warningBufferHandler;
    private final Map<Long, Long> triggerMap = new HashMap<>();

    /**
     * <b>warning:</b> the container must be thread-safed.
     * 
     * @param factory
     * @param queueAdder
     * @return
     */
    public <E1, C1> SimpleBufferTriggerBuilder<E1, C1> setContainer(Supplier<? extends C1> factory,
            BiPredicate<? super C1, ? super E1> queueAdder) {
        checkNotNull(factory);
        checkNotNull(queueAdder);

        SimpleBufferTriggerBuilder<E1, C1> thisBuilder = (SimpleBufferTriggerBuilder<E1, C1>) this;
        thisBuilder.bufferFactory = (Supplier<C1>) factory;
        thisBuilder.queueAdder = (BiPredicate<C1, E1>) queueAdder;
        return thisBuilder;
    }

    public SimpleBufferTriggerBuilder<E, C>
            setScheduleExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
        return this;
    }

    public <E1, C1> SimpleBufferTriggerBuilder<E1, C1>
            setExceptionHandler(BiConsumer<? super Throwable, ? super C1> exceptionHandler) {
        SimpleBufferTriggerBuilder<E1, C1> thisBuilder = (SimpleBufferTriggerBuilder<E1, C1>) this;
        thisBuilder.exceptionHandler = (BiConsumer<Throwable, C1>) exceptionHandler;
        return thisBuilder;
    }

    public SimpleBufferTriggerBuilder<E, C> on(long interval, TimeUnit unit, long count) {
        triggerMap.put(unit.toMillis(interval), count);
        return this;
    }

    public <E1, C1> SimpleBufferTriggerBuilder<E1, C1> consumer(ThrowableConsumer<? super C1, Throwable> consumer) {
        checkNotNull(consumer);
        SimpleBufferTriggerBuilder<E1, C1> thisBuilder = (SimpleBufferTriggerBuilder<E1, C1>) this;
        thisBuilder.consumer = (ThrowableConsumer<C1, Throwable>) consumer;
        return thisBuilder;
    }

    /**
     * it's better dealing this in container
     */
    public SimpleBufferTriggerBuilder<E, C> maxBufferCount(long count) {
        checkArgument(count > 0);

        this.maxBufferCount = count;
        return this;
    }

    /**
     * it's better dealing this in container
     */
    public <E1, C1> SimpleBufferTriggerBuilder<E1, C1> maxBufferCount(long count, Consumer<? super E1> rejectHandler) {
        return (SimpleBufferTriggerBuilder<E1, C1>) maxBufferCount(count).rejectHandler(rejectHandler);
    }

    /**
     * it's better dealing this in container
     */
    public <E1, C1> SimpleBufferTriggerBuilder<E1, C1> rejectHandler(Consumer<? super E1> rejectHandler) {
        checkNotNull(rejectHandler);
        SimpleBufferTriggerBuilder<E1, C1> thisBuilder = (SimpleBufferTriggerBuilder<E1, C1>) this;
        thisBuilder.rejectHandler = (Consumer<E1>) rejectHandler;
        return thisBuilder;
    }

    public SimpleBufferTriggerBuilder<E, C> warningThreshold(long threshold, LongConsumer handler) {
        checkNotNull(handler);
        checkArgument(threshold > 0);

        this.warningBufferHandler = handler;
        this.warningBufferThreshold = threshold;
        return this;
    }

    public <E1> BufferTrigger<E1> build() {
        return new LazyBufferTrigger<>(() -> {
            ensure();
            return new SimpleBufferTrigger<E1>((Supplier<Object>) bufferFactory,
                    (BiPredicate<Object, E1>) queueAdder, scheduledExecutorService,
                    (ThrowableConsumer<Object, Throwable>) consumer, triggerMap,
                    (BiConsumer<Throwable, Object>) exceptionHandler, maxBufferCount,
                    (Consumer<E1>) rejectHandler, warningBufferThreshold, warningBufferHandler);
        });
    }

    private void ensure() {
        checkNotNull(consumer);

        if (bufferFactory == null) {
            bufferFactory = () -> (C) Collections.synchronizedSet(new HashSet<>());
        }
        if (queueAdder == null) {
            queueAdder = (c, e) -> ((Set<E>) c).add(e);
        }
        if (!triggerMap.isEmpty() && scheduledExecutorService == null) {
            scheduledExecutorService = makeScheduleExecutor();
        }
        if (maxBufferCount > 0 && warningBufferThreshold > 0) {
            if (warningBufferThreshold >= maxBufferCount) {
                SimpleBufferTrigger.logger.warn(
                        "invalid warning threshold:{}, it shouldn't be larger than maxBufferSize. ignore warning threshold.",
                        warningBufferThreshold);
                warningBufferThreshold = 0;
                warningBufferHandler = null;
            }
        }
    }

    private ScheduledExecutorService makeScheduleExecutor() {
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(
                Math.max(1, triggerMap.size()),
                new ThreadFactoryBuilder().setNameFormat("pool-simple-buffer-trigger-thread-%d") //
                        .setDaemon(true) //
                        .build());

        return scheduledExecutorService;
    }
}