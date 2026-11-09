package reberu.core.tenancy.controller;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.tenancy.service.HomeService;
import reberu.core.tenancy.service.HomeService.Home;

@RestController
class HomeController {

    private final HomeService home;

    HomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/api/me/home")
    Home home(@AuthenticationPrincipal UUID accountId) {
        return home.home(accountId);
    }

}
