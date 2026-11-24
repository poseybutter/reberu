package reberu.core.tenancy.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.tenancy.service.MemberService;
import reberu.core.tenancy.service.TeamMemberService;

@RestController
@RequestMapping("/api/me")
class MyMembershipController {

    private final MemberService members;
    private final TeamMemberService teamMembers;

    MyMembershipController(MemberService members, TeamMemberService teamMembers) {
        this.members = members;
        this.teamMembers = teamMembers;
    }

    @PostMapping("/orgs/{leaveOrgId}/leave")
    @Access(AccessLevel.ACCOUNT)
    ResponseEntity<Void> leaveOrg(@AuthenticationPrincipal UUID accountId, @PathVariable UUID leaveOrgId) {
        members.leave(leaveOrgId, accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/teams/{leaveTeamId}/leave")
    @Access(AccessLevel.ACCOUNT)
    ResponseEntity<Void> leaveTeam(@AuthenticationPrincipal UUID accountId, @PathVariable UUID leaveTeamId) {
        teamMembers.leave(leaveTeamId, accountId);
        return ResponseEntity.noContent().build();
    }

}
