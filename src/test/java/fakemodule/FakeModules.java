package fakemodule;

import fakemodule.ext.FakeExtensionModule;
import fakemodule.org.FakeOrgModule;
import fakemodule.team.FakeTeamModule;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class FakeModules {

    @Bean
    FakeTeamModule fakeTeamModule() {
        return new FakeTeamModule();
    }

    @Bean
    FakeTeamModule.Api fakeTeamModuleApi() {
        return new FakeTeamModule.Api();
    }

    @Bean
    FakeOrgModule fakeOrgModule() {
        return new FakeOrgModule();
    }

    @Bean
    FakeOrgModule.Api fakeOrgModuleApi() {
        return new FakeOrgModule.Api();
    }

    @Bean
    FakeExtensionModule fakeExtensionModule() {
        return new FakeExtensionModule();
    }

    @Bean
    FakeExtensionModule.Api fakeExtensionModuleApi() {
        return new FakeExtensionModule.Api();
    }

}
