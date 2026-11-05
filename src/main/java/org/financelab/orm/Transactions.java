package org.financelab.orm;

public interface Transactions {
    <R> Completed<R> write(Work<WriteSession, R> work);
    <R> Completed<R> snapshot(Work<ReadSession, R> work);
}
