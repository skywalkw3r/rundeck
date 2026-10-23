package com.dtolabs.rundeck.core.execution

import com.dtolabs.rundeck.core.storage.StorageLogger
import org.rundeck.storage.api.PathUtil
import org.slf4j.Logger
import org.slf4j.MDC
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static com.dtolabs.rundeck.core.execution.ExecutionLogContext.*

class ExecutionLogContextSpec extends Specification {
    def cleanup() {
        MDC.clear()
    }

    def "forContext reads the execution identity from the job data context"() {
        given:
            def context = Mock(ExecutionContext) {
                getFrameworkProject() >> project
                getDataContext() >> [job: job]
            }

        when:
            def values = forContext(context)

        then:
            values == expected

        where:
            project | job                                              | expected
            'proj1' | [execid: '12', id: 'job-uuid', project: 'proj1'] | [(LOG_TYPE): LOG_TYPE_EXECUTION, (EXECUTION_ID): '12', (PROJECT): 'proj1', (JOB_ID): 'job-uuid']
            'proj1' | [execid: '12', project: 'proj1']                 | [(LOG_TYPE): LOG_TYPE_EXECUTION, (EXECUTION_ID): '12', (PROJECT): 'proj1']
            null    | [execid: '12', project: 'proj2']                 | [(LOG_TYPE): LOG_TYPE_EXECUTION, (EXECUTION_ID): '12', (PROJECT): 'proj2']
            'proj1' | null                                             | [(LOG_TYPE): LOG_TYPE_EXECUTION, (PROJECT): 'proj1']
    }

    def "apply sets the MDC values and close restores the previous values"() {
        given:
            MDC.put('other', 'value')

        when:
            def outer = apply(forExecution('1', 'proj1', 'job1'))
            def inner = apply(forExecution('2', 'proj2', null))

        then:
            MDC.get(EXECUTION_ID) == '2'
            MDC.get(PROJECT) == 'proj2'
            MDC.get(JOB_ID) == null
            MDC.get(LOG_TYPE) == LOG_TYPE_EXECUTION

        when:
            inner.close()

        then:
            MDC.get(EXECUTION_ID) == '1'
            MDC.get(PROJECT) == 'proj1'
            MDC.get(JOB_ID) == 'job1'

        when:
            outer.close()

        then:
            KEYS.every { MDC.get(it) == null }
            MDC.get('other') == 'value'
    }

    def "wrap runs the callable with the values of the calling thread"() {
        given:
            def executor = Executors.newSingleThreadExecutor()
            Callable<Map> wrapped
            try (def ignored = apply(forExecution('3', 'proj1', 'job1'))) {
                wrapped = wrap({ current() } as Callable<Map>)
            }

        when:
            def seen = executor.submit(wrapped).get(10, TimeUnit.SECONDS)
            def after = executor.submit({ current() } as Callable<Map>).get(10, TimeUnit.SECONDS)

        then:
            seen == forExecution('3', 'proj1', 'job1')
            after.isEmpty()

        cleanup:
            executor.shutdownNow()
    }

    def "threadFactory creates threads with the values of the calling thread"() {
        given:
            def factory
            try (def ignored = apply(forExecution('4', 'proj1', null))) {
                factory = threadFactory()
            }
            def executor = Executors.newFixedThreadPool(2, factory)

        when:
            def seen = executor.submit({ current() } as Callable<Map>).get(10, TimeUnit.SECONDS)

        then:
            seen == forExecution('4', 'proj1', null)
            current().isEmpty()

        cleanup:
            executor.shutdownNow()
    }

    def "storage logging keeps the execution values"() {
        given:
            def storageLogger = new StorageLogger(Mock(Logger))
            def scope = apply(forExecution('5', 'proj1', null))

        when:
            storageLogger.didDeleteResource(PathUtil.asPath('keys/test'), true)

        then:
            MDC.get(EXECUTION_ID) == '5'
            MDC.get('path') == null
            MDC.get('action') == null

        cleanup:
            scope.close()
    }
}
