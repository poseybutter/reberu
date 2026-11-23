package reberu.core.event.delivery;

import java.util.Optional;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import reberu.core.access.AccessLevel.Scope;
import reberu.core.access.ModuleSwitch;
import reberu.core.event.OrgScoped;
import reberu.core.event.TeamScoped;

public class ModuleDeliveryGuard implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ModuleDeliveryGuard.class);

    private final ObjectProvider<ModuleSwitch> modules;

    public ModuleDeliveryGuard(ObjectProvider<ModuleSwitch> modules) {
        this.modules = modules;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        ModuleSwitch switches = modules.getIfAvailable();
        if (switches == null || invocation.getThis() == null) {
            return invocation.proceed();
        }
        Optional<String> owner = switches.owner(AopUtils.getTargetClass(invocation.getThis()));
        if (owner.isEmpty()) {
            return invocation.proceed();
        }
        Object event = invocation.getArguments().length == 1 ? invocation.getArguments()[0] : null;
        if (!enabled(switches, owner.get(), event)) {
            log.debug("꺼진 범위라 전달하지 않음: 모듈 {}, 이벤트 {}", owner.get(),
                    event == null ? null : event.getClass().getSimpleName());
            return null;
        }
        return invocation.proceed();
    }

    private static boolean enabled(ModuleSwitch switches, String moduleKey, Object event) {
        Optional<Scope> scope = switches.installedScope(moduleKey);
        if (scope.isEmpty()) {
            return false;
        }
        return switch (scope.get()) {
            case TEAM -> event instanceof TeamScoped team && switches.enabledInTeam(team.teamId(), moduleKey);
            case ORG -> event instanceof OrgScoped org && switches.enabledInOrg(org.orgId(), moduleKey);
            case NONE -> false;
        };
    }

}
