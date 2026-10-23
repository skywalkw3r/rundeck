package com.dtolabs.rundeck.core.execution;

import org.slf4j.MDC;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Tags application log events emitted while an execution is running with the execution's identity, using the
 * logging MDC. Log configuration can use these values to route execution related log events separately from
 * application level log events, e.g. with a {@code ThreadContextMapFilter} on {@link #LOG_TYPE}.
 */
public final class ExecutionLogContext {
    /**
     * MDC key set to {@link #LOG_TYPE_EXECUTION} while an execution is running
     */
    public static final String LOG_TYPE = "rundeck.logType";
    public static final String LOG_TYPE_EXECUTION = "execution";
    public static final String EXECUTION_ID = "rundeck.executionId";
    public static final String PROJECT = "rundeck.project";
    public static final String JOB_ID = "rundeck.jobId";

    static final List<String> KEYS = Collections.unmodifiableList(
            Arrays.asList(LOG_TYPE, EXECUTION_ID, PROJECT, JOB_ID)
    );

    private ExecutionLogContext() {
    }

    /**
     * @param executionId execution ID
     * @param project     project name
     * @param jobId       job ID, or null for an adhoc execution
     * @return MDC values for the execution
     */
    public static Map<String, String> forExecution(String executionId, String project, String jobId) {
        Map<String, String> values = new HashMap<>();
        values.put(LOG_TYPE, LOG_TYPE_EXECUTION);
        putIfPresent(values, EXECUTION_ID, executionId);
        putIfPresent(values, PROJECT, project);
        putIfPresent(values, JOB_ID, jobId);
        return values;
    }

    /**
     * @param context execution context
     * @return MDC values for the execution, read from the "job" data context
     */
    public static Map<String, String> forContext(ExecutionContext context) {
        Map<String, Map<String, String>> dataContext = context.getDataContext();
        Map<String, String> job = null != dataContext ? dataContext.get("job") : null;
        String project = context.getFrameworkProject();
        if (null == project && null != job) {
            project = job.get("project");
        }
        return forExecution(
                null != job ? job.get("execid") : null,
                project,
                null != job ? job.get("id") : null
        );
    }

    /**
     * @return the execution values currently set in the MDC
     */
    public static Map<String, String> current() {
        Map<String, String> values = new HashMap<>();
        for (String key : KEYS) {
            putIfPresent(values, key, MDC.get(key));
        }
        return values;
    }

    /**
     * Set the execution values in the MDC, replacing any previous execution values
     *
     * @param values values to set
     * @return scope which restores the previous values when closed
     */
    public static Scope apply(Map<String, String> values) {
        Map<String, String> previous = current();
        set(values);
        return new Scope(previous);
    }

    /**
     * Set the execution values for the context in the MDC
     *
     * @param context execution context
     * @return scope which restores the previous values when closed
     */
    public static Scope apply(ExecutionContext context) {
        return apply(forContext(context));
    }

    /**
     * @param callable callable
     * @return callable which runs with the execution values of the calling thread
     */
    public static <T> Callable<T> wrap(Callable<T> callable) {
        Map<String, String> values = current();
        return () -> {
            try (Scope ignored = apply(values)) {
                return callable.call();
            }
        };
    }

    /**
     * @return thread factory for threads which run with the execution values of the calling thread
     */
    public static ThreadFactory threadFactory() {
        Map<String, String> values = current();
        ThreadFactory delegate = Executors.defaultThreadFactory();
        return runnable -> delegate.newThread(() -> {
            try (Scope ignored = apply(values)) {
                runnable.run();
            }
        });
    }

    private static void set(Map<String, String> values) {
        for (String key : KEYS) {
            String value = values.get(key);
            if (null != value) {
                MDC.put(key, value);
            } else {
                MDC.remove(key);
            }
        }
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (null != value && !value.isEmpty()) {
            values.put(key, value);
        }
    }

    /**
     * Restores the previous execution values in the MDC when closed
     */
    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            set(previous);
        }
    }
}
