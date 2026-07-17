package dev.databasemcp.permission;

import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ConservativeMetricSqlInspector {

    private static final Set<String> UNSUPPORTED_KEYWORDS = Set.of(
        "union", "intersect", "except", "minus", "with", "table", "only"
    );
    private static final Set<String> WHERE_BOUNDARIES = Set.of(
        "group", "order", "having", "limit", "offset", "fetch", "for",
        "union", "intersect", "except", "minus"
    );
    private static final Set<String> RELATION_KEYWORDS = Set.of(
        "where", "join", "left", "right", "full", "inner", "outer", "cross",
        "on", "group", "order", "having", "limit", "offset", "fetch", "for",
        "union", "intersect", "except", "minus"
    );

    private final Set<String> protectedTables;
    private final Set<String> metricColumns;
    private final Set<String> sceneColumns;
    private final DatabaseType databaseType;

    @Autowired
    ConservativeMetricSqlInspector(DatabaseMcpProperties properties) {
        this(
            properties.getDatabaseType(),
            properties.getPermission().isEnabled() && properties.getPermission().getMetric().isEnabled()
                ? properties.getPermission().getMetric().getProtectedTables()
                : Set.of(),
            properties.getPermission().getMetric().getMetricColumns(),
            properties.getPermission().getMetric().getSceneColumns()
        );
    }

    ConservativeMetricSqlInspector(
        DatabaseType databaseType,
        Set<String> protectedTables,
        Set<String> metricColumns,
        Set<String> sceneColumns
    ) {
        this.databaseType = Objects.requireNonNull(databaseType, "databaseType");
        this.protectedTables = normalizeSet(protectedTables);
        this.metricColumns = normalizeSet(metricColumns);
        this.sceneColumns = normalizeSet(sceneColumns);
    }

    MetricSqlInspection inspect(String sql) {
        Tokenization tokenization = tokenize(sql == null ? "" : sql);
        List<Token> tokens = tokenization.tokens();
        if (!tokenization.valid() || hasUnsafeExecutableComment(tokens)) {
            return MetricSqlInspection.uninspectable();
        }
        List<TableRef> relations = tableReferences(tokens);
        if (!referencesProtectedTable(tokens, relations)) {
            return MetricSqlInspection.notProtected();
        }
        if (hasDoubleQuotedConstruct(tokens) || !hasSingleQueryBlock(tokens) || hasUnsafeSemicolon(tokens)
            || containsAnyKeyword(tokens, UNSUPPORTED_KEYWORDS)) {
            return MetricSqlInspection.uninspectable();
        }

        List<TableRef> protectedRelations = relations.stream().filter(this::isProtectedTable).toList();
        if (protectedRelations.size() != 1) {
            return MetricSqlInspection.uninspectable();
        }

        List<Token> predicates = wherePredicates(tokens);
        if (predicates.isEmpty() || hasTopLevelDisjunction(predicates)) {
            return MetricSqlInspection.uninspectable();
        }
        TableRef protectedRelation = protectedRelations.getFirst();
        PredicateEvidence evidence = predicateEvidence(predicates, protectedRelation, relations);
        if (!evidence.valid()) {
            return MetricSqlInspection.uninspectable();
        }
        if (!evidence.tupleScopes().isEmpty()) {
            return MetricSqlInspection.inspectable(evidence.tupleScopes());
        }
        if (evidence.metricValues() != null && evidence.sceneValues() != null) {
            return MetricSqlInspection.inspectable(cartesianScopes(
                evidence.metricValues(), evidence.sceneValues()
            ));
        }
        return MetricSqlInspection.uninspectable();
    }

    private boolean referencesProtectedTable(List<Token> tokens, List<TableRef> relations) {
        if (relations.stream().anyMatch(this::isProtectedTable)) {
            return true;
        }
        for (int i = 0; i < tokens.size(); i++) {
            ParsedColumn identifier = parseColumn(tokens, i);
            if (identifier != null && isProtectedName(identifier.reference().qualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isProtectedTable(TableRef tableRef) {
        return isProtectedName(tableRef.name());
    }

    private boolean isProtectedName(String name) {
        String normalized = normalize(name);
        String terminal = terminalName(normalized);
        return protectedTables.stream().anyMatch(configuredTable ->
            configuredTable.equals(normalized) || terminalName(configuredTable).equals(terminal)
        );
    }

    private static boolean hasSingleQueryBlock(List<Token> tokens) {
        return tokens.stream().filter(token -> token.isKeyword("select")).count() == 1;
    }

    private static boolean hasUnsafeSemicolon(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (";".equals(tokens.get(i).value()) && i != tokens.size() - 1) {
                return true;
            }
        }
        return false;
    }

    private static List<TableRef> tableReferences(List<Token> tokens) {
        List<TableRef> relations = new ArrayList<>();
        boolean inFromClause = false;
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if ("(".equals(token.value())) {
                depth++;
                continue;
            }
            if (")".equals(token.value())) {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (token.isKeyword("from")) {
                inFromClause = true;
                i = addTableReference(tokens, i + 1, relations, i);
            } else if (token.isKeyword("join")) {
                i = addTableReference(tokens, i + 1, relations, i);
            } else if (inFromClause && ",".equals(token.value())) {
                i = addTableReference(tokens, i + 1, relations, i);
            } else if (inFromClause && token.isAnyKeyword(WHERE_BOUNDARIES)) {
                inFromClause = false;
            } else if (token.isKeyword("where")) {
                inFromClause = false;
            }
        }
        return relations;
    }

    private static int addTableReference(List<Token> tokens, int start, List<TableRef> relations, int fallbackIndex) {
        ParsedTable parsed = parseTable(tokens, start);
        if (parsed == null) {
            return fallbackIndex;
        }
        relations.add(parsed.table());
        return parsed.nextIndex() - 1;
    }

    private static ParsedTable parseTable(List<Token> tokens, int start) {
        if (start >= tokens.size() || !tokens.get(start).identifier()) {
            return null;
        }
        int index = start;
        StringBuilder name = new StringBuilder(tokens.get(index).normalized());
        index++;
        while (index + 1 < tokens.size() && ".".equals(tokens.get(index).value()) && tokens.get(index + 1).identifier()) {
            name.append('.').append(tokens.get(index + 1).normalized());
            index += 2;
        }

        String alias = null;
        if (index < tokens.size() && tokens.get(index).isKeyword("as")) {
            index++;
            if (index < tokens.size() && tokens.get(index).identifier()) {
                alias = tokens.get(index).normalized();
                index++;
            }
        } else if (index < tokens.size() && tokens.get(index).identifier()
            && !tokens.get(index).isAnyKeyword(RELATION_KEYWORDS)) {
            alias = tokens.get(index).normalized();
            index++;
        }
        return new ParsedTable(new TableRef(name.toString(), alias), index);
    }

    private static List<Token> wherePredicates(List<Token> tokens) {
        int depth = 0;
        int start = -1;
        int end = tokens.size();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if ("(".equals(token.value())) {
                depth++;
            } else if (")".equals(token.value())) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && token.isKeyword("where")) {
                start = i + 1;
            } else if (depth == 0 && start >= 0 && token.isAnyKeyword(WHERE_BOUNDARIES)) {
                end = i;
                break;
            }
        }
        return start < 0 || start >= end ? List.of() : tokens.subList(start, end);
    }

    private PredicateEvidence predicateEvidence(List<Token> predicates, TableRef protectedRelation, List<TableRef> relations) {
        Set<String> metricValues = null;
        Set<String> sceneValues = null;
        Set<MetricScope> tupleScopes = Set.of();
        for (List<Token> rawTerm : conjunctionTerms(predicates)) {
            List<Token> term = stripOuterParentheses(rawTerm);
            ScopeTerm scopeTerm = parseScopeTerm(term, protectedRelation, relations);
            if (scopeTerm == null) {
                Set<MetricScope> parsedTuples = parseTupleTerm(term, protectedRelation, relations);
                if (!parsedTuples.isEmpty()) {
                    if (!tupleScopes.isEmpty() || metricValues != null || sceneValues != null) {
                        return PredicateEvidence.invalid();
                    }
                    tupleScopes = parsedTuples;
                } else if (referencesScopeColumn(term)) {
                    return PredicateEvidence.invalid();
                }
                continue;
            }
            if (!tupleScopes.isEmpty()) {
                return PredicateEvidence.invalid();
            }
            if (scopeTerm.metric()) {
                if (metricValues != null) {
                    return PredicateEvidence.invalid();
                }
                metricValues = scopeTerm.values();
            } else {
                if (sceneValues != null) {
                    return PredicateEvidence.invalid();
                }
                sceneValues = scopeTerm.values();
            }
        }
        return new PredicateEvidence(true, tupleScopes, metricValues, sceneValues);
    }

    private ScopeTerm parseScopeTerm(List<Token> term, TableRef protectedRelation, List<TableRef> relations) {
        ParsedColumn column = parseColumn(term, 0);
        if (column == null || column.nextIndex() >= term.size()
            || !belongsToProtectedRelation(column.reference(), protectedRelation, relations)) {
            return null;
        }
        int operatorIndex = column.nextIndex();
        Set<String> values;
        if ("=".equals(term.get(operatorIndex).value())) {
            if (operatorIndex + 2 != term.size() || !term.get(operatorIndex + 1).string()
                || !isUsableScopeValue(term.get(operatorIndex + 1).value())) {
                return null;
            }
            values = Set.of(term.get(operatorIndex + 1).value());
        } else if (term.get(operatorIndex).isKeyword("in")) {
            values = parseStringList(term, operatorIndex + 1);
            if (values == null) {
                return null;
            }
        } else {
            return null;
        }
        String columnName = column.reference().name();
        if (metricColumns.contains(columnName)) {
            return new ScopeTerm(true, values);
        }
        if (sceneColumns.contains(columnName)) {
            return new ScopeTerm(false, values);
        }
        return null;
    }

    private Set<String> parseStringList(List<Token> term, int startIndex) {
        if (startIndex >= term.size() || !"(".equals(term.get(startIndex).value())) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        int index = startIndex + 1;
        while (index < term.size()) {
            if (!term.get(index).string() || !isUsableScopeValue(term.get(index).value())) {
                return null;
            }
            values.add(term.get(index).value());
            index++;
            if (index >= term.size()) {
                return null;
            }
            if (")".equals(term.get(index).value())) {
                return index == term.size() - 1 ? Set.copyOf(values) : null;
            }
            if (!",".equals(term.get(index).value())) {
                return null;
            }
            index++;
        }
        return null;
    }

    private static boolean isUsableScopeValue(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim());
    }

    private Set<MetricScope> cartesianScopes(Set<String> metricValues, Set<String> sceneValues) {
        Set<MetricScope> scopes = new LinkedHashSet<>();
        for (String metricValue : metricValues) {
            for (String sceneValue : sceneValues) {
                scopes.add(new MetricScope(metricValue, sceneValue));
            }
        }
        return Set.copyOf(scopes);
    }

    private Set<MetricScope> parseTupleTerm(List<Token> term, TableRef protectedRelation, List<TableRef> relations) {
        if (term.isEmpty() || !"(".equals(term.getFirst().value())) {
            return Set.of();
        }
        ParsedColumn metric = parseColumn(term, 1);
        if (metric == null || !metricColumns.contains(metric.reference().name())
            || !belongsToProtectedRelation(metric.reference(), protectedRelation, relations)) {
            return Set.of();
        }
        int index = metric.nextIndex();
        if (index >= term.size() || !",".equals(term.get(index).value())) {
            return Set.of();
        }
        ParsedColumn scene = parseColumn(term, index + 1);
        if (scene == null || !sceneColumns.contains(scene.reference().name())
            || !belongsToProtectedRelation(scene.reference(), protectedRelation, relations)) {
            return Set.of();
        }
        index = scene.nextIndex();
        if (index + 2 >= term.size() || !")".equals(term.get(index).value())
            || !term.get(index + 1).isKeyword("in") || !"(".equals(term.get(index + 2).value())) {
            return Set.of();
        }
        index += 3;
        Set<MetricScope> scopes = new LinkedHashSet<>();
        while (index < term.size()) {
            if (")".equals(term.get(index).value())) {
                return index == term.size() - 1 && !scopes.isEmpty() ? Set.copyOf(scopes) : Set.of();
            }
            if (index + 4 >= term.size() || !"(".equals(term.get(index).value())
                || !term.get(index + 1).string() || !",".equals(term.get(index + 2).value())
                || !term.get(index + 3).string() || !")".equals(term.get(index + 4).value())
                || !isUsableScopeValue(term.get(index + 1).value())
                || !isUsableScopeValue(term.get(index + 3).value())) {
                return Set.of();
            }
            scopes.add(new MetricScope(term.get(index + 1).value(), term.get(index + 3).value()));
            index += 5;
            if (index < term.size() && ",".equals(term.get(index).value())) {
                index++;
            }
        }
        return Set.of();
    }

    private boolean referencesScopeColumn(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            ParsedColumn column = parseColumn(tokens, i);
            if (column != null && (metricColumns.contains(column.reference().name())
                || sceneColumns.contains(column.reference().name()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTopLevelDisjunction(List<Token> predicates) {
        int depth = 0;
        for (Token token : stripOuterParentheses(predicates)) {
            if ("(".equals(token.value())) {
                depth++;
            } else if (")".equals(token.value())) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (token.isKeyword("or") || token.isKeyword("xor")
                || "||".equals(token.value()))) {
                return true;
            }
        }
        return false;
    }

    private static List<List<Token>> conjunctionTerms(List<Token> predicates) {
        List<Token> normalized = stripOuterParentheses(predicates);
        List<List<Token>> terms = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < normalized.size(); i++) {
            Token token = normalized.get(i);
            if ("(".equals(token.value())) {
                depth++;
            } else if (")".equals(token.value())) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && token.isKeyword("and")) {
                terms.add(normalized.subList(start, i));
                start = i + 1;
            }
        }
        terms.add(normalized.subList(start, normalized.size()));
        return terms;
    }

    private static List<Token> stripOuterParentheses(List<Token> tokens) {
        List<Token> current = tokens;
        while (current.size() >= 2 && "(".equals(current.getFirst().value())
            && matchingParenthesis(current, 0) == current.size() - 1) {
            current = current.subList(1, current.size() - 1);
        }
        return current;
    }

    private static int matchingParenthesis(List<Token> tokens, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            if ("(".equals(tokens.get(i).value())) {
                depth++;
            } else if (")".equals(tokens.get(i).value()) && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean belongsToProtectedRelation(ColumnRef column, TableRef protectedRelation,
                                                       List<TableRef> relations) {
        if (column.qualifier() == null) {
            return relations.size() == 1;
        }
        String qualifier = column.qualifier();
        TableRef matchedRelation = null;
        for (TableRef relation : relations) {
            if (!matchesVisibleQualifier(qualifier, relation)) {
                continue;
            }
            if (matchedRelation != null) {
                return false;
            }
            matchedRelation = relation;
        }
        return protectedRelation.equals(matchedRelation);
    }

    private static boolean matchesVisibleQualifier(String qualifier, TableRef relation) {
        if (relation.alias() != null) {
            return qualifier.equals(relation.alias());
        }
        return qualifier.equals(relation.name())
            || (!qualifier.contains(".") && qualifier.equals(relation.terminalName()));
    }

    private static ParsedColumn parseColumn(List<Token> tokens, int start) {
        if (start < 0 || start >= tokens.size() || !tokens.get(start).identifier()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(tokens.get(start).normalized());
        int index = start + 1;
        while (index + 1 < tokens.size() && ".".equals(tokens.get(index).value()) && tokens.get(index + 1).identifier()) {
            parts.add(tokens.get(index + 1).normalized());
            index += 2;
        }
        String name = parts.getLast();
        String qualifier = parts.size() == 1 ? null : String.join(".", parts.subList(0, parts.size() - 1));
        return new ParsedColumn(new ColumnRef(qualifier, name), index);
    }

    private static boolean containsAnyKeyword(List<Token> tokens, Set<String> keywords) {
        return tokens.stream().anyMatch(token -> token.isAnyKeyword(keywords));
    }

    private static boolean hasUnsafeExecutableComment(List<Token> tokens) {
        return tokens.stream().anyMatch(Token::unsafeExecutableComment);
    }

    private static boolean hasDoubleQuotedConstruct(List<Token> tokens) {
        return tokens.stream().anyMatch(token -> token.quoteKind() == QuoteKind.DOUBLE);
    }

    private static Set<String> normalizeSet(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(ConservativeMetricSqlInspector::normalize)
                .forEach(normalized::add);
        }
        return Set.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value.replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String terminalName(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static Tokenization tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        boolean valid = true;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (isDamengQuotedLiteralOpener(sql, i)) {
                valid = false;
                tokens.add(new Token(sql.substring(i, i + 2), TokenType.OTHER));
                i += 2;
            } else if (isUnicodeEscapedQuotedIdentifierOpener(sql, i)) {
                valid = false;
                tokens.add(new Token("U&\"", TokenType.OTHER));
                i += 3;
            } else if (isDollarQuoteOpener(sql, i)) {
                valid = false;
                tokens.add(new Token(String.valueOf(c), TokenType.OTHER));
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int commentStart = i + 2;
                if (commentStart == sql.length()
                    || Character.isWhitespace(sql.charAt(commentStart))
                    || Character.isISOControl(sql.charAt(commentStart))) {
                    i = commentStart;
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                } else {
                    valid = false;
                    tokens.add(new Token("--", TokenType.OPERATOR));
                    i = commentStart;
                }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                if (i + 2 < sql.length() && sql.charAt(i + 2) == '!') {
                    tokens.add(new Token("/*!", TokenType.UNSAFE_EXECUTABLE_COMMENT));
                }
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i + 1 < sql.length()) {
                    i += 2;
                } else {
                    valid = false;
                    i = sql.length();
                }
            } else if (c == '\'') {
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                i++;
                while (i < sql.length()) {
                    char ch = sql.charAt(i);
                    if (ch == '\\') {
                        valid = false;
                        value.append(ch);
                        i++;
                    } else if (ch == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        value.append('\'');
                        i += 2;
                    } else if (ch == '\'') {
                        i++;
                        closed = true;
                        break;
                    } else {
                        value.append(ch);
                        i++;
                    }
                }
                valid &= closed;
                tokens.add(new Token(value.toString(), TokenType.STRING));
            } else if (isIdentifierStart(c) || c == '"' || c == '`') {
                QuoteKind quoteKind = c == '"' ? QuoteKind.DOUBLE
                    : c == '`' ? QuoteKind.BACKTICK : QuoteKind.NONE;
                StringBuilder value = new StringBuilder();
                if (quoteKind != QuoteKind.NONE) {
                    boolean closed = false;
                    i++;
                    while (i < sql.length() && sql.charAt(i) != c) {
                        char quotedChar = sql.charAt(i++);
                        if (quoteKind == QuoteKind.DOUBLE && quotedChar == '\\') {
                            valid = false;
                        }
                        value.append(quotedChar);
                    }
                    if (i < sql.length()) {
                        i++;
                        closed = true;
                    }
                    valid &= closed;
                } else {
                    while (i < sql.length() && isIdentifierPart(sql.charAt(i))) {
                        value.append(sql.charAt(i++));
                    }
                }
                tokens.add(new Token(value.toString(), TokenType.IDENTIFIER, quoteKind));
            } else if (isOperatorChar(c)) {
                StringBuilder operator = new StringBuilder();
                while (i < sql.length() && isOperatorChar(sql.charAt(i))) {
                    operator.append(sql.charAt(i++));
                }
                tokens.add(new Token(operator.toString(), TokenType.OPERATOR));
            } else if ("(),.;".indexOf(c) >= 0) {
                tokens.add(new Token(String.valueOf(c), TokenType.PUNCTUATION));
                i++;
            } else {
                tokens.add(new Token(String.valueOf(c), TokenType.OTHER));
                i++;
            }
        }
        return new Tokenization(List.copyOf(tokens), valid);
    }

    private static boolean isDamengQuotedLiteralOpener(String sql, int start) {
        return start + 1 < sql.length()
            && (sql.charAt(start) == 'Q' || sql.charAt(start) == 'q')
            && sql.charAt(start + 1) == '\''
            && (start == 0 || !isIdentifierContinuation(sql.codePointBefore(start)));
    }

    private static boolean isUnicodeEscapedQuotedIdentifierOpener(String sql, int start) {
        return start + 2 < sql.length()
            && (sql.charAt(start) == 'U' || sql.charAt(start) == 'u')
            && sql.charAt(start + 1) == '&'
            && sql.charAt(start + 2) == '"'
            && (start == 0 || !isIdentifierContinuation(sql.codePointBefore(start)));
    }

    private static boolean isDollarQuoteOpener(String sql, int start) {
        if (sql.charAt(start) != '$'
            || (start > 0 && isIdentifierContinuation(sql.codePointBefore(start)))
            || start + 1 >= sql.length()) {
            return false;
        }
        if (sql.charAt(start + 1) == '$') {
            return true;
        }

        int index = start + 1;
        int codePoint = sql.codePointAt(index);
        if (codePoint != '_' && !Character.isUnicodeIdentifierStart(codePoint)) {
            return false;
        }
        index += Character.charCount(codePoint);
        while (index < sql.length() && sql.charAt(index) != '$') {
            codePoint = sql.codePointAt(index);
            if (codePoint != '_' && !Character.isUnicodeIdentifierPart(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return index < sql.length();
    }

    private static boolean isIdentifierContinuation(int codePoint) {
        return codePoint == '_' || Character.isUnicodeIdentifierPart(codePoint);
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isOperatorChar(char c) {
        return "=<>!~^|&+-*/%".indexOf(c) >= 0;
    }

    private enum TokenType {
        IDENTIFIER,
        STRING,
        OPERATOR,
        PUNCTUATION,
        OTHER,
        UNSAFE_EXECUTABLE_COMMENT
    }

    private enum QuoteKind {
        NONE,
        BACKTICK,
        DOUBLE
    }

    private record Tokenization(List<Token> tokens, boolean valid) {
    }

    private record Token(String value, TokenType type, QuoteKind quoteKind) {

        private Token(String value, TokenType type) {
            this(value, type, QuoteKind.NONE);
        }

        boolean identifier() {
            return type == TokenType.IDENTIFIER;
        }

        boolean string() {
            return type == TokenType.STRING;
        }

        String normalized() {
            return normalize(value);
        }

        boolean isKeyword(String keyword) {
            return identifier() && quoteKind == QuoteKind.NONE && normalized().equals(keyword);
        }

        boolean isAnyKeyword(Set<String> keywords) {
            return identifier() && quoteKind == QuoteKind.NONE && keywords.contains(normalized());
        }

        boolean unsafeExecutableComment() {
            return type == TokenType.UNSAFE_EXECUTABLE_COMMENT;
        }
    }

    private record TableRef(String name, String alias) {
        String terminalName() {
            return ConservativeMetricSqlInspector.terminalName(name);
        }
    }

    private record ParsedTable(TableRef table, int nextIndex) {
    }

    private record ColumnRef(String qualifier, String name) {
        String qualifiedName() {
            return qualifier == null ? name : qualifier + "." + name;
        }
    }

    private record ParsedColumn(ColumnRef reference, int nextIndex) {
    }

    private record ScopeTerm(boolean metric, Set<String> values) {
        private ScopeTerm {
            values = Set.copyOf(values);
        }
    }

    private record PredicateEvidence(
        boolean valid,
        Set<MetricScope> tupleScopes,
        Set<String> metricValues,
        Set<String> sceneValues
    ) {
        static PredicateEvidence invalid() {
            return new PredicateEvidence(false, Set.of(), null, null);
        }
    }
}
