package reberu.core.access.web;

import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;

class AccessHiddenException extends ReberuException {

    AccessHiddenException() {
        super(ErrorCode.NOT_FOUND);
    }

}
