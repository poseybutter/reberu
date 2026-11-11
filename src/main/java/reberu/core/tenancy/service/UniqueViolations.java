package reberu.core.tenancy.service;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;

final class UniqueViolations {

    private UniqueViolations() {
    }

    static boolean of(DataIntegrityViolationException violation, String constraintName) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                return constraint.getKind() == ConstraintKind.UNIQUE && name != null
                        && name.toLowerCase(Locale.ROOT).endsWith(constraintName);
            }
        }
        return false;
    }

}
