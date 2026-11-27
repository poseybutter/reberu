package reberu.core.event.delivery;

import io.micrometer.core.instrument.Metrics;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;

class DeliveryFailureMeter implements MethodInterceptor {

    static final String FAILURES = "reberu.events.listener.failures";
    static final String LISTENER = "listener";

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        try {
            return invocation.proceed();
        }
        catch (Throwable failure) {
            Metrics.counter(FAILURES, LISTENER, listenerOf(invocation)).increment();
            throw failure;
        }
    }

    static String listenerOf(MethodInvocation invocation) {
        Class<?> type = invocation.getThis() == null
                ? invocation.getMethod().getDeclaringClass()
                : AopUtils.getTargetClass(invocation.getThis());
        return type.getSimpleName() + "." + invocation.getMethod().getName();
    }

}
