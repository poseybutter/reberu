package reberu.core.support.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;

final class FieldViolations {

    private static final Set<String> REQUIRED = Set.of("NotNull", "NotBlank", "NotEmpty");
    private static final Set<String> LENGTH = Set.of("Size", "Length");
    private static final Set<String> FORMAT = Set.of("Pattern", "Email", "UUID");

    private FieldViolations() {
    }

    static FieldViolation of(String field, ConstraintViolation<?> violation) {
        return new FieldViolation(field, violation == null ? FieldReason.NOT_ALLOWED_VALUE : reason(violation));
    }

    static String lastNode(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name;
    }

    private static FieldReason reason(ConstraintViolation<?> violation) {
        ConstraintDescriptor<?> descriptor = violation.getConstraintDescriptor();
        String type = descriptor.getAnnotation().annotationType().getSimpleName();
        if (REQUIRED.contains(type)) {
            return FieldReason.REQUIRED;
        }
        if (LENGTH.contains(type)) {
            return lengthReason(descriptor.getAttributes(), violation.getInvalidValue());
        }
        if (FORMAT.contains(type)) {
            return FieldReason.INVALID_FORMAT;
        }
        return FieldReason.NOT_ALLOWED_VALUE;
    }

    private static FieldReason lengthReason(Map<String, Object> attributes, Object value) {
        int length;
        if (value instanceof CharSequence text) {
            length = text.length();
        } else if (value instanceof Collection<?> collection) {
            length = collection.size();
        } else if (value instanceof Map<?, ?> map) {
            length = map.size();
        } else {
            return FieldReason.NOT_ALLOWED_VALUE;
        }
        Object min = attributes.get("min");
        return min instanceof Integer minimum && length < minimum ? FieldReason.TOO_SHORT : FieldReason.TOO_LONG;
    }

}
