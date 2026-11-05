package org.financelab.orm;

@FunctionalInterface
public interface Work<S, R> { R run(S session) throws Exception; }
