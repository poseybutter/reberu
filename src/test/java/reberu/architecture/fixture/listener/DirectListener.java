package reberu.architecture.fixture.listener;

import org.springframework.modulith.events.ApplicationModuleListener;

class DirectListener {

    @ApplicationModuleListener
    void on(String event) {
    }

}
