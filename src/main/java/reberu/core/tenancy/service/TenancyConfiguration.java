package reberu.core.tenancy.service;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.access.MembershipDirectory;
import reberu.core.account.Accounts;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.CursorCodec;
import reberu.core.support.RateLimits;
import reberu.core.tenancy.Deployment;
import reberu.core.tenancy.DeploymentMode;
import reberu.core.tenancy.OrgBootstrap;
import reberu.core.tenancy.repository.InvitationRepository;
import reberu.core.tenancy.repository.JobTypeRepository;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamCustomFieldRepository;
import reberu.core.tenancy.repository.TeamRepository;
import reberu.core.tenancy.repository.TeamTaskLabelRepository;
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
    StateLocks stateLocks(OrganizationRepository organizations, TeamRepository teams) {
        return new StateLocks(organizations, teams);
    }

    @Bean
    DefaultJobTypes defaultJobTypes(JobTypeRepository jobTypes, JsonMapper jsonMapper) {
        return new DefaultJobTypes(jobTypes, jsonMapper);
    }

    @Bean
    OrgCreationService orgCreationService(Accounts accounts, OrganizationRepository organizations,
            OrgMembershipRepository orgMemberships, TeamRepository teams, TeamMembershipRepository teamMemberships,
            DefaultJobTypes defaultJobTypes, TeamFieldService teamFields, NameRules nameRules,
            ActivityLogs activityLogs) {
        return new OrgCreationService(accounts, organizations, orgMemberships, teams, teamMemberships,
                defaultJobTypes, teamFields, nameRules, activityLogs, Clock.systemUTC());
    }

    @Bean
    TeamFieldService teamFieldService(StateLocks locks, TeamTaskLabelRepository labels,
            TeamCustomFieldRepository fields, NameRules nameRules, ActivityLogs activityLogs) {
        return new TeamFieldService(locks, labels, fields, nameRules, activityLogs, Clock.systemUTC());
    }

    @Bean
    TeamSettingsService teamSettingsService(TeamRepository teams, StateLocks locks, NameRules nameRules,
            ActivityLogs activityLogs, JdbcClient jdbc, JsonMapper jsonMapper) {
        return new TeamSettingsService(teams, locks, nameRules, activityLogs, jdbc, jsonMapper, Clock.systemUTC());
    }

    @Bean
    MembershipDirectory membershipDirectory(JdbcClient jdbc) {
        return new JdbcMembershipDirectory(jdbc);
    }

    @Bean
    TeamService teamService(StateLocks locks, TeamRepository teams,
            TeamMembershipRepository teamMemberships, TeamFieldService teamFields,
            OrgMembershipRepository orgMemberships, JobTypeRepository jobTypes, NameRules nameRules,
            ActivityLogs activityLogs, CursorCodec cursors, JdbcClient jdbc, JsonMapper jsonMapper) {
        return new TeamService(locks, teams, teamMemberships, teamFields, orgMemberships, jobTypes,
                nameRules, activityLogs, cursors, jdbc, jsonMapper, Clock.systemUTC());
    }

    @Bean
    OrgSettingsService orgSettingsService(OrganizationRepository organizations,
            OrgMembershipRepository orgMemberships, TeamMembershipRepository teamMemberships, NameRules nameRules,
            ActivityLogs activityLogs, JdbcClient jdbc) {
        return new OrgSettingsService(organizations, orgMemberships, teamMemberships, nameRules, activityLogs, jdbc,
                Clock.systemUTC());
    }

    @Bean
    JobTypeService jobTypeService(JobTypeRepository jobTypes, StateLocks locks, NameRules nameRules,
            ActivityLogs activityLogs, JdbcClient jdbc, JsonMapper jsonMapper) {
        return new JobTypeService(jobTypes, locks, nameRules, activityLogs, jdbc, jsonMapper,
                Clock.systemUTC());
    }

    @Bean
    MemberService memberService(StateLocks locks, OrgMembershipRepository orgMemberships,
            TeamRepository teams, TeamMembershipRepository teamMemberships, Accounts accounts,
            ActivityLogs activityLogs, ApplicationEventPublisher events, CursorCodec cursors, JdbcClient jdbc) {
        return new MemberService(locks, orgMemberships, teams, teamMemberships, accounts, activityLogs,
                events, cursors, jdbc, Clock.systemUTC());
    }

    @Bean
    TeamMemberService teamMemberService(StateLocks locks, OrgMembershipRepository orgMemberships, TeamRepository teams, TeamMembershipRepository teamMemberships,
            Accounts accounts, ActivityLogs activityLogs, CursorCodec cursors, JdbcClient jdbc) {
        return new TeamMemberService(locks, orgMemberships, teams, teamMemberships, accounts, activityLogs,
                cursors, jdbc, Clock.systemUTC());
    }

    @Bean
    InvitationService invitationService(StateLocks locks, InvitationRepository invitations,
            OrgMembershipRepository orgMemberships, TeamRepository teams, TeamMembershipRepository teamMemberships,
            Accounts accounts, ActivityLogs activityLogs, RateLimits rateLimits, CursorCodec cursors,
            JdbcClient jdbc) {
        return new InvitationService(locks, invitations, orgMemberships, teams, teamMemberships, accounts,
                activityLogs, rateLimits, cursors, jdbc, Clock.systemUTC());
    }

    @Bean
    OrgActivityService orgActivityService(ActivityLogs activityLogs, Accounts accounts, CursorCodec cursors,
            JdbcClient jdbc) {
        return new OrgActivityService(activityLogs, accounts, cursors, jdbc);
    }

    @Bean
    TeamViewService teamViewService(TeamRepository teams, ActivityLogs activityLogs) {
        return new TeamViewService(teams, activityLogs, Clock.systemUTC());
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
