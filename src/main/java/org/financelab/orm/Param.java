package org.financelab.orm;

import java.util.Objects;
import java.util.Optional;

public record Param<V>(Codec<V> codec, Optional<V> value) {
    public Param { Objects.requireNonNull(codec); Objects.requireNonNull(value); }
    public static <V> Param<V> of(Codec<V> codec, V value) { return new Param<>(codec, Optional.of(value)); }
}
