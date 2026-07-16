package dev.databasemcp.permission;

import dev.databasemcp.config.DatabaseMcpProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConservativeMetricSqlInspector {

    private static final Set<String> UNSUPPORTED_KEYWORDS = Set.of(
        "union", "intersect", "except", "minus", "not", "with"
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

    @Autowired
    public ConservativeMetricSqlInspector(DatabaseMcpProperties properties) {
        this(
            properties.getPermission().isEnabled() && properties.getPermission().getMetric().isEnabled()
                ? properties.getPermission().getMetric().getProtectedTables()
                : Set.of(),
            properties.getPermission().getMetric().getMetricColumns(),
            properties.getPermission().getMetric().getSceneColumns()
        );
    }

    public ConservativeMetricSqlInspector(Set<String> protectedTables, Set<String> metricColumns, Set<String> sceneColumns) {
        this.protectedTables = normalizeSet(protectedTables);
        this.metricColumns = normalizeSet(metricColumns);
        this.sceneColumns = normalizeSet(sceneColumns);
    }

    public MetricSqlInspection inspect(String sql) {
        List<Token> tokens = tokenize(sql == null ? "" : sql);
        List<TableRef> relations = tableReferences(tokens);
        if (!referencesProtectedTable(tokens, relations)) {
            return MetricSqlInspection.notProtected();
        }
        if (!hasSingleQueryBlock(tokens) || hasUnsafeSemicolon(tokens)
            || containsAnyKeyword(tokens, UNSUPPORTED_KEYWORDS) || containsKeyword(tokens, "or")) {
            return MetricSqlInspection.uninspectable();
        }

        List<TableRef> protectedRelations = relations.stream().filter(this::isProtectedTable).toList();
        if (protectedRelations.size() != 1) {
            return MetricSqlInspection.uninspectable();
        }

        List<Token> predicates = wherePredicates(tokens);
        if (predicates.isEmpty()) {
            return MetricSqlInspection.uninspectable();
        }
        TableRef protectedRelation = protectedRelations.getFirst();
        PredicateEvidence evidence = predicateEvidence(predicates, protectedRelation, relations.size());
        if (!evidence.valid()) {
            return MetricSqlInspection.uninspectable();
        }
        if (!evidence.tupleScopes().isEmpty()) {
            return MetricSqlInspection.inspectable(evidence.tupleScopes());
        }
        if (evidence.metricValue() != null && evidence.sceneValue() != null) {
            return MetricSqlInspection.inspectable(Set.of(
                new MetricScope(evidence.metricValue(), evidence.sceneValue())
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
            } else if (inFromClause && token.identifier() && WHERE_BOUNDARIES.contains(token.normalized())) {
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
            && !RELATION_KEYWORDS.contains(tokens.get(index).normalized())) {
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
            } else if (depth == 0 && start >= 0 && token.identifier() && WHERE_BOUNDARIES.contains(token.normalized())) {
                end = i;
                break;
            }
        }
        return start < 0 || start >= end ? List.of() : tokens.subList(start, end);
    }

    private PredicateEvidence predicateEvidence(List<Token> predicates, TableRef protectedRelation, int relationCount) {
        String metricValue = null;
        String sceneValue = null;
        Set<MetricScope> tupleScopes = Set.of();
        for (List<Token> rawTerm : conjunctionTerms(predicates)) {
            List<Token> term = stripOuterParentheses(rawTerm);
            ScopeTerm scopeTerm = parseEqualityTerm(term, protectedRelation, relationCount);
            if (scopeTerm == null) {
                Set<MetricScope> parsedTuples = parseTupleTerm(term, protectedRelation, relationCount);
                if (!parsedTuples.isEmpty()) {
                    if (!tupleScopes.isEmpty() || metricValue != null || sceneValue != null) {
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
                if (metricValue != null && !metricValue.equals(scopeTerm.value())) {
                    return PredicateEvidence.invalid();
                }
                metricValue = scopeTerm.value();
            } else {
                if (sceneValue != null && !sceneValue.equals(scopeTerm.value())) {
                    return PredicateEvidence.invalid();
                }
                sceneValue = scopeTerm.value();
            }
        }
        return new PredicateEvidence(true, tupleScopes, metricValue, sceneValue);
    }

    private ScopeTerm parseEqualityTerm(List<Token> term, TableRef protectedRelation, int relationCount) {
        ParsedColumn column = parseColumn(term, 0);
        if (column == null || column.nextIndex() + 1 >= term.size()
            || !"=".equals(term.get(column.nextIndex()).value())
            || !term.get(column.nextIndex() + 1).string()
            || column.nextIndex() + 2 != term.size()
            || !belongsToProtectedRelation(column.reference(), protectedRelation, relationCount)) {
            return null;
        }
        String columnName = column.reference().name();
        String value = term.get(column.nextIndex() + 1).value();
        if (metricColumns.contains(columnName)) {
            return new ScopeTerm(true, value);
        }
        if (sceneColumns.contains(columnName)) {
            return new ScopeTerm(false, value);
        }
        return null;
    }

    private Set<MetricScope> parseTupleTerm(List<Token> term, TableRef protectedRelation, int relationCount) {
        if (term.isEmpty() || !"(".equals(term.getFirst().value())) {
            return Set.of();
        }
        ParsedColumn metric = parseColumn(term, 1);
        if (metric == null || !metricColumns.contains(metric.reference().name())
            || !belongsToProtectedRelation(metric.reference(), protectedRelation, relationCount)) {
            return Set.of();
        }
        int index = metric.nextIndex();
        if (index >= term.size() || !",".equals(term.get(index).value())) {
            return Set.of();
        }
        ParsedColumn scene = parseColumn(term, index + 1);
        if (scene == null || !sceneColumns.contains(scene.reference().name())
            || !belongsToProtectedRelation(scene.reference(), protectedRelation, relationCount)) {
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
                || !term.get(index + 3).string() || !")".equals(term.get(index + 4).value())) {
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

    private static boolean belongsToProtectedRelation(ColumnRef column, TableRef table, int relationCount) {
        if (column.qualifier() == null) {
            return relationCount == 1;
        }
        String qualifier = column.qualifier();
        return qualifier.equals(table.name())
            || terminalName(qualifier).equals(table.terminalName())
            || (table.alias() != null && qualifier.equals(table.alias()));
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
        return tokens.stream().anyMatch(token -> token.identifier() && keywords.contains(token.normalized()));
    }

    private static boolean containsKeyword(List<Token> tokens, String keyword) {
        return tokens.stream().anyMatch(token -> token.isKeyword(keyword));
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

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(sql.length(), i + 2);
            } else if (c == '\'') {
                StringBuilder value = new StringBuilder();
                i++;
                while (i < sql.length()) {
                    char ch = sql.charAt(i);
                    if (ch == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        value.append('\'');
                        i += 2;
                    } else if (ch == '\'') {
                        i++;
                        break;
                    } else {
                        value.append(ch);
                        i++;
                    }
                }
                tokens.add(new Token(value.toString(), TokenType.STRING));
            } else if (isIdentifierStart(c) || c == '"' || c == '`') {
                char quote = (c == '"' || c == '`') ? c : 0;
                StringBuilder value = new StringBuilder();
                if (quote != 0) {
                    i++;
                    while (i < sql.length() && sql.charAt(i) != quote) {
                        value.append(sql.charAt(i++));
                    }
                    i = Math.min(sql.length(), i + 1);
                } else {
                    while (i < sql.length() && isIdentifierPart(sql.charAt(i))) {
                        value.append(sql.charAt(i++));
                    }
                }
                tokens.add(new Token(value.toString(), TokenType.IDENTIFIER));
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
                i++;
            }
        }
        return tokens;
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
        PUNCTUATION
    }

    private record Token(String value, TokenType type) {
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
            return identifier() && normalized().equals(keyword);
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

    private record ScopeTerm(boolean metric, String value) {
    }

    private record PredicateEvidence(
        boolean valid,
        Set<MetricScope> tupleScopes,
        String metricValue,
        String sceneValue
    ) {
        static PredicateEvidence invalid() {
            return new PredicateEvidence(false, Set.of(), null, null);
        }
    }
}
