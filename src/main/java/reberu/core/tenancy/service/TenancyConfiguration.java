package reberu.core.tenancy.service;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.account.Accounts;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.CursorCodec;
import reberu.core.tenancy.Deployment;
import reberu.core.tenancy.DeploymentMode;
import reberu.core.tenancy.OrgBootstrap;
import reberu.core.tenancy.repository.JobTypeRepository;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenancyProperties.class)
class TenancyConfiguration {

    @Bean
    Deployment deployment(TenancyProperties properties) {
        DeploymentMode mode = properties.mode();
        return () -> mode;
    }

    @Bean
    NameRules nameRules(TenancyProperties properties) {
        return new NameRules(properties.bannedWords());
    }

    @Bean
    DefaultJobTypes defaultJobTypes(JobTypeRepository jobTypes, JsonMapper jsonMapper) {
        return new DefaultJobTypes(jobTypes, jsonMapper);
    }

    @Bean
    OrgCreationService orgCreationService(Accounts accounts, OrganizationRepository organizations,
            OrgMembershipRepository orgMemberships, TeamRepository teams, TeamMembershipRepository teamMemberships,
            DefaultJobTypes defaultJobTypes, NameRules nameRules, ActivityLogs activityLogs) {
        return new OrgCreationService(accounts, organizations, orgMemberships, teams, teamMemberships,
                defaultJobTypes, nameRules, activityLogs, Clock.systemUTC());
    }

    @Bean
    OrgAccess orgAccess(OrganizationRepository organizations, OrgMembershipRepository orgMemberships) {
        return new OrgAccess(organizations, orgMemberships);
    }

    @Bean
    TeamService teamService(OrgAccess access, TeamRepository teams, TeamMembershipRepository teamMemberships,
            OrgMembershipRepository orgMemberships, JobTypeRepository jobTypes, NameRules nameRules,
            ActivityLogs activityLogs, CursorCodec cursors, JdbcClient jdbc, JsonMapper jsonMapper) {
        return new TeamService(access, teams, teamMemberships, orgMemberships, jobTypes, nameRules, activityLogs,
                cursors, jdbc, jsonMapper, Clock.systemUTC());
    }

    @Bean
    HomeService homeService(JdbcClient jdbc) {
        return new HomeService(jdbc);
    }

    @Bean
    SingleOrgBootstrap singleOrgBootstrap(TenancyProperties properties, OrganizationRepository organizations,
            OrgMembershipRepository orgMemberships, DefaultJobTypes defaultJobTypes, NameRules nameRules,
            ActivityLogs activityLogs, TransactionTemplate transactions) {
        return new SingleOrgBootstrap(properties, organizations, orgMemberships, defaultJobTypes, nameRules,
                activityLogs, transactions, Clock.systemUTC());
    }

}
