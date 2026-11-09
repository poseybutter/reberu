package reberu.core.support;

import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

public interface IdempotentRequests {

    ResponseEntity<String> execute(IdempotentRequest request, Supplier<? extends ResponseEntity<?>> action);

    ResponseEntity<String> executeWithoutReplay(IdempotentRequest request,
            Supplier<? extends ResponseEntity<?>> action);

}
