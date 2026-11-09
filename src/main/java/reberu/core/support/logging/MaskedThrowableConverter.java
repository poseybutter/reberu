package reberu.core.support.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;
import reberu.core.support.SensitiveText;

public class MaskedThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy throwableProxy) {
        return SensitiveText.mask(super.throwableProxyToString(throwableProxy));
    }

}
