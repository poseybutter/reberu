package reberu.core.support;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }

}
