package org.financelab.orm;

import java.util.Optional;

public interface Row {
    <V> Optional<V> get(int index, Codec<V> codec);
}
