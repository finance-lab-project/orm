package org.financelab.orm;

import java.util.Locale;
import java.util.Objects;

/** One trusted, reviewed DML statement. Never construct from request input. */
public record Sql(String text) {
    public Sql {
        Objects.requireNonNull(text);
        if (text.indexOf('\\') >= 0 || text.indexOf('$') >= 0)
            throw new IllegalArgumentException("Use bound literals instead of escape or dollar syntax");
        // Strip quoted literals/identifiers and comments before checking statement boundaries.
        StringBuilder tokens = new StringBuilder();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c; i++;
                boolean closed = false;
                while (i < text.length()) {
                    if (text.charAt(i++) == quote) {
                        if (i < text.length() && text.charAt(i) == quote) i++;
                        else { closed = true; break; }
                    }
                }
                if (!closed) throw new IllegalArgumentException("Unclosed SQL quote");
                tokens.append(' ');
            } else if (text.startsWith("--", i)) {
                int end = text.indexOf('\n', i + 2); i = end < 0 ? text.length() : end;
                tokens.append(' ');
            } else if (text.startsWith("/*", i) || c == '$' || c == '\\') {
                throw new IllegalArgumentException("Use simple reviewed SQL with bound literals");
            } else { tokens.append(c); i++; }
        }
        String sql = tokens.toString().strip().toUpperCase(Locale.ROOT);
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).stripTrailing();
        if (sql.indexOf(';') >= 0 || !sql.matches("(?s)(SELECT|INSERT|UPDATE|DELETE|WITH)\\b.*"))
            throw new IllegalArgumentException("Require one reviewed SELECT/INSERT/UPDATE/DELETE/WITH statement");
    }
}
