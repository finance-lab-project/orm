package org.financelab.orm;

import java.util.List;

public interface WriteSession extends ReadSession {
    int execute(Sql sql, List<Param<?>> params);
}
