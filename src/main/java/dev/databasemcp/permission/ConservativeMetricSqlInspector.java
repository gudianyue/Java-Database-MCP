package dev.databasemcp.permission;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLListExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import dev.databasemcp.config.DatabaseMcpProperties;
import dev.databasemcp.config.DatabaseType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ConservativeMetricSqlInspector {

    private static final int MAX_SQL_LENGTH = 64 * 1024;
    private static final Pattern UNICODE_QUOTED_IDENTIFIER = Pattern.compile(
        "(?i)(?<![\\p{L}\\p{N}_$])U&\""
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
        if (protectedTables.isEmpty()) {
            return MetricSqlInspection.notProtected();
        }

        try {
            return inspectEnabled(sql);
        } catch (RuntimeException ignored) {
            return MetricSqlInspection.uninspectable();
        }
    }

    private MetricSqlInspection inspectEnabled(String sql) {
        String candidateSql = sql == null ? "" : sql;
        if (candidateSql.isBlank() || candidateSql.length() > MAX_SQL_LENGTH
            || hasUnsupportedLexicalForm(candidateSql)) {
            return MetricSqlInspection.uninspectable();
        }

        SQLSelectStatement statement = parseSupportedStatement(candidateSql);
        if (statement == null) {
            return MetricSqlInspection.uninspectable();
        }
        boolean hasDoubleQuotedIdentifier = containsDoubleQuotedIdentifier(statement);
        if (hasDoubleQuotedIdentifier && UNICODE_QUOTED_IDENTIFIER.matcher(candidateSql).find()) {
            return MetricSqlInspection.uninspectable();
        }
        SQLSelect select = statement.getSelect();
        if (select.getWithSubQuery() != null || !(select.getQuery() instanceof SQLSelectQueryBlock queryBlock)
            || countQueryBlocks(statement) != 1 || queryBlock.getInto() != null
            || queryBlock.isForUpdate() || queryBlock.isForShare()
            || queryBlock instanceof PGSelectQueryBlock pg && pg.getForClause() != null
            || queryBlock instanceof MySqlSelectQueryBlock mysql
                && (mysql.isLockInShareMode() || mysql.getProcedureName() != null)) {
            return MetricSqlInspection.uninspectable();
        }

        List<TableRef> relations = new ArrayList<>();
        if (!collectRelations(queryBlock.getFrom(), relations)) {
            return MetricSqlInspection.uninspectable();
        }
        List<TableRef> protectedRelations = relations.stream().filter(this::isProtectedTable).toList();
        if (protectedRelations.isEmpty()) {
            return MetricSqlInspection.notProtected();
        }
        if (hasDoubleQuotedIdentifier
            || protectedRelations.size() != 1 || queryBlock.getWhere() == null) {
            return MetricSqlInspection.uninspectable();
        }

        PredicateEvidence evidence = predicateEvidence(
            queryBlock.getWhere(),
            protectedRelations.getFirst(),
            relations
        );
        if (!evidence.valid()) {
            return MetricSqlInspection.uninspectable();
        }
        if (!evidence.tupleScopes().isEmpty()) {
            return MetricSqlInspection.inspectable(evidence.tupleScopes());
        }
        if (evidence.metricValues() == null || evidence.sceneValues() == null) {
            return MetricSqlInspection.uninspectable();
        }
        return MetricSqlInspection.inspectable(cartesianScopes(evidence.metricValues(), evidence.sceneValues()));
    }

    private SQLSelectStatement parseSupportedStatement(String sql) {
        List<SQLStatement> statements = SQLUtils.parseStatements(sql == null ? "" : sql, druidDialect());
        if (statements.size() != 1 || !(statements.getFirst() instanceof SQLSelectStatement selectStatement)) {
            return null;
        }
        return selectStatement;
    }

    private boolean hasUnsupportedLexicalForm(String sql) {
        if (sql.indexOf('\\') >= 0) {
            return true;
        }
        if (sql.contains("/*!") && containsExecutableComment(sql)) {
            return true;
        }
        Lexer lexer = SQLParserUtils.createLexer(sql, druidDialect());
        lexer.setKeepComments(true);
        lexer.nextToken();
        while (lexer.token() != Token.EOF) {
            if (lexer.token() == Token.EQEQ || lexer.token() == Token.BARBAR) {
                return true;
            }
            lexer.nextToken();
        }
        List<String> comments = lexer.getComments();
        return comments != null && comments.stream()
            .anyMatch(ConservativeMetricSqlInspector::isUnsafeComment);
    }

    private static boolean containsExecutableComment(String sql) {
        Lexer lexer = new Lexer(sql);
        lexer.setKeepComments(true);
        lexer.nextToken();
        while (lexer.token() != Token.EOF) {
            lexer.nextToken();
        }
        List<String> comments = lexer.getComments();
        return comments != null && comments.stream()
            .map(String::stripLeading)
            .anyMatch(comment -> comment.startsWith("/*!"));
    }

    private static boolean isUnsafeComment(String comment) {
        String normalized = comment.stripLeading();
        return normalized.startsWith("/*!")
            || (normalized.startsWith("--") && normalized.length() > 2
                && !Character.isWhitespace(normalized.charAt(2)));
    }

    private DbType druidDialect() {
        return switch (databaseType) {
            case POSTGRESQL -> DbType.postgresql;
            case MYSQL -> DbType.mysql;
            case DORIS -> DbType.doris;
            case DAMENG -> DbType.dm;
        };
    }

    private static int countQueryBlocks(SQLSelectStatement statement) {
        int[] count = {0};
        statement.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLSelectQueryBlock queryBlock) {
                count[0]++;
                return true;
            }
        });
        return count[0];
    }

    private static boolean containsDoubleQuotedIdentifier(SQLSelectStatement statement) {
        boolean[] found = {false};
        statement.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLIdentifierExpr identifier) {
                found[0] |= isDoubleQuoted(identifier.getName());
                return !found[0];
            }

            @Override
            public boolean visit(SQLPropertyExpr property) {
                found[0] |= isDoubleQuoted(property.getName()) || isDoubleQuoted(property.getOwnerName());
                return !found[0];
            }
        });
        return found[0];
    }

    private static boolean isDoubleQuoted(String value) {
        return value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
    }

    private static boolean collectRelations(SQLTableSource source, List<TableRef> relations) {
        if (source == null) {
            return true;
        }
        if (source instanceof SQLJoinTableSource join) {
            return collectRelations(join.getLeft(), relations) && collectRelations(join.getRight(), relations);
        }
        if (!(source instanceof SQLExprTableSource table) || !isPlainTableName(table.getExpr())) {
            return false;
        }
        relations.add(new TableRef(normalize(table.getExpr().toString()), normalizeNullable(table.getAlias())));
        return true;
    }

    private static boolean isPlainTableName(SQLExpr expression) {
        if (expression instanceof SQLIdentifierExpr) {
            return true;
        }
        if (expression instanceof SQLPropertyExpr property) {
            return isPlainTableName(property.getOwner());
        }
        return false;
    }

    private boolean isProtectedTable(TableRef relation) {
        return protectedTables.stream().anyMatch(tableName ->
            tableName.equals(relation.name())
                || terminalName(tableName).equals(relation.terminalName())
        );
    }

    private PredicateEvidence predicateEvidence(
        SQLExpr where,
        TableRef protectedRelation,
        List<TableRef> relations
    ) {
        Set<String> metricValues = null;
        Set<String> sceneValues = null;
        Set<MetricScope> tupleScopes = Set.of();
        for (SQLExpr term : conjunctionTerms(where)) {
            ScopeTerm scopeTerm = parseScopeTerm(term, protectedRelation, relations);
            if (scopeTerm != null) {
                if (!tupleScopes.isEmpty()) {
                    return PredicateEvidence.invalid();
                }
                if (scopeTerm.dimension() == ScopeDimension.METRIC) {
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
                continue;
            }

            Set<MetricScope> parsedTuples = parseTupleTerm(term, protectedRelation, relations);
            if (!parsedTuples.isEmpty()) {
                if (!tupleScopes.isEmpty() || metricValues != null || sceneValues != null) {
                    return PredicateEvidence.invalid();
                }
                tupleScopes = parsedTuples;
            } else if (referencesScopeColumn(term)) {
                return PredicateEvidence.invalid();
            }
        }
        return new PredicateEvidence(true, tupleScopes, metricValues, sceneValues);
    }

    private static List<SQLExpr> conjunctionTerms(SQLExpr expression) {
        List<SQLExpr> terms = new ArrayList<>();
        collectConjunctionTerms(expression, terms);
        return terms;
    }

    private static void collectConjunctionTerms(SQLExpr expression, List<SQLExpr> terms) {
        if (expression instanceof SQLBinaryOpExpr binary && binary.getOperator() == SQLBinaryOperator.BooleanAnd) {
            collectConjunctionTerms(binary.getLeft(), terms);
            collectConjunctionTerms(binary.getRight(), terms);
        } else {
            terms.add(expression);
        }
    }

    private ScopeTerm parseScopeTerm(
        SQLExpr expression,
        TableRef protectedRelation,
        List<TableRef> relations
    ) {
        SQLExpr columnExpression;
        List<SQLExpr> valueExpressions;
        if (expression instanceof SQLBinaryOpExpr binary && binary.getOperator() == SQLBinaryOperator.Equality) {
            columnExpression = binary.getLeft();
            valueExpressions = List.of(binary.getRight());
        } else if (expression instanceof SQLInListExpr in && !in.isNot()) {
            columnExpression = in.getExpr();
            valueExpressions = in.getTargetList();
        } else {
            return null;
        }

        ColumnRef column = columnRef(columnExpression);
        if (column == null || !belongsToProtectedRelation(column, protectedRelation, relations)) {
            return null;
        }
        Set<String> values = stringValues(valueExpressions);
        if (values == null) {
            return null;
        }
        if (metricColumns.contains(column.name())) {
            return new ScopeTerm(ScopeDimension.METRIC, values);
        }
        if (sceneColumns.contains(column.name())) {
            return new ScopeTerm(ScopeDimension.SCENE, values);
        }
        return null;
    }

    private Set<MetricScope> parseTupleTerm(
        SQLExpr expression,
        TableRef protectedRelation,
        List<TableRef> relations
    ) {
        if (!(expression instanceof SQLInListExpr in) || in.isNot()
            || !(in.getExpr() instanceof SQLListExpr columns) || columns.getItems().size() != 2) {
            return Set.of();
        }
        ColumnRef metric = columnRef(columns.getItems().get(0));
        ColumnRef scene = columnRef(columns.getItems().get(1));
        if (metric == null || scene == null
            || !metricColumns.contains(metric.name()) || !sceneColumns.contains(scene.name())
            || !belongsToProtectedRelation(metric, protectedRelation, relations)
            || !belongsToProtectedRelation(scene, protectedRelation, relations)) {
            return Set.of();
        }

        Set<MetricScope> scopes = new LinkedHashSet<>();
        for (SQLExpr target : in.getTargetList()) {
            if (!(target instanceof SQLListExpr tuple) || tuple.getItems().size() != 2) {
                return Set.of();
            }
            Set<String> values = stringValues(tuple.getItems());
            if (values == null) {
                return Set.of();
            }
            SQLCharExpr metricValue = (SQLCharExpr) tuple.getItems().get(0);
            SQLCharExpr sceneValue = (SQLCharExpr) tuple.getItems().get(1);
            scopes.add(new MetricScope(metricValue.getText(), sceneValue.getText()));
        }
        return Set.copyOf(scopes);
    }

    private static Set<String> stringValues(List<SQLExpr> expressions) {
        Set<String> values = new LinkedHashSet<>();
        for (SQLExpr expression : expressions) {
            if (!(expression instanceof SQLCharExpr literal) || !isUsableScopeValue(literal.getText())) {
                return null;
            }
            values.add(literal.getText());
        }
        return values.isEmpty() ? null : Set.copyOf(values);
    }

    private boolean referencesScopeColumn(SQLExpr expression) {
        boolean[] found = {false};
        expression.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLIdentifierExpr identifier) {
                found[0] |= isScopeColumn(identifier.getName());
                return !found[0];
            }

            @Override
            public boolean visit(SQLPropertyExpr property) {
                found[0] |= isScopeColumn(property.getName());
                return !found[0];
            }
        });
        return found[0];
    }

    private boolean isScopeColumn(String name) {
        String normalized = normalize(name);
        return metricColumns.contains(normalized) || sceneColumns.contains(normalized);
    }

    private static ColumnRef columnRef(SQLExpr expression) {
        if (expression instanceof SQLIdentifierExpr identifier) {
            return new ColumnRef(null, normalize(identifier.getName()));
        }
        if (expression instanceof SQLPropertyExpr property) {
            return new ColumnRef(normalize(property.getOwnerName()), normalize(property.getName()));
        }
        return null;
    }

    private static boolean belongsToProtectedRelation(
        ColumnRef column,
        TableRef protectedRelation,
        List<TableRef> relations
    ) {
        if (column.qualifier() == null) {
            return relations.size() == 1;
        }
        TableRef matchedRelation = null;
        for (TableRef relation : relations) {
            if (!matchesVisibleQualifier(column.qualifier(), relation)) {
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

    private static boolean isUsableScopeValue(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim());
    }

    private static Set<MetricScope> cartesianScopes(Set<String> metricValues, Set<String> sceneValues) {
        Set<MetricScope> scopes = new LinkedHashSet<>();
        for (String metricValue : metricValues) {
            for (String sceneValue : sceneValues) {
                scopes.add(new MetricScope(metricValue, sceneValue));
            }
        }
        return Set.copyOf(scopes);
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

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : normalize(value);
    }

    private static String terminalName(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private record TableRef(String name, String alias) {
        String terminalName() {
            return ConservativeMetricSqlInspector.terminalName(name);
        }
    }

    private record ColumnRef(String qualifier, String name) {
    }

    private record ScopeTerm(ScopeDimension dimension, Set<String> values) {
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

    private enum ScopeDimension {
        METRIC,
        SCENE
    }
}
