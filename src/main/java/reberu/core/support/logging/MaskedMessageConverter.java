package reberu.core.support.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import reberu.core.support.SensitiveText;

public class MaskedMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveText.escapeLineBreaks(SensitiveText.mask(super.convert(event)));
    }

}
