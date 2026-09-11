package me.dontshare.yieldcore.math;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates a small admin-authored math expression at a given "level" - the
 * config-driven-curve idiom several other formula-shaped configs in this
 * codebase use fixed fields for (cost-base/cost-growth, etc.), but a
 * pickaxe enchant's cost/boost/chance curves are open-ended enough (see
 * yield-mining's pickaxe-enchants.yml) that a real expression is worth it:
 * {@code +  -  *  /  ^}, parentheses, unary minus, decimal numbers, and the
 * single variable {@code level}.
 * <p>
 * Each formula string is parsed into a small expression tree ONCE and
 * cached forever after (see {@link #CACHE}) - the same reason a formula
 * cache exists in any interpreter that re-evaluates the same expression
 * repeatedly (this runs per mined block, per equipped enchant): parsing is
 * the expensive part, substituting {@code level} into an already-built
 * tree and evaluating it is cheap. A dedicated expression library (e.g.
 * exp4j) would get the same win, but pulls in a dependency for a grammar
 * (functions, multiple variables) this never needs - one variable and five
 * operators is little enough to own outright.
 * <p>
 * Grammar (standard precedence, {@code ^} right-associative, tighter than
 * unary minus so {@code -2^2} is -4):
 * <pre>
 * expression := term (('+' | '-') term)*
 * term       := power (('*' | '/') power)*
 * power      := unary ('^' power)?
 * unary      := '-' unary | primary
 * primary    := NUMBER | 'level' | '(' expression ')'
 * </pre>
 */
public final class FormulaEvaluator {

    private static final Map<String, Node> CACHE = new ConcurrentHashMap<>();

    private FormulaEvaluator() {
    }

    public static double evaluate(String formula, double level) {
        Node node = CACHE.computeIfAbsent(formula, f -> new Parser(f).parseExpressionAndExpectEnd());
        return node.evaluate(level);
    }

    private interface Node {
        double evaluate(double level);
    }

    private record Constant(double value) implements Node {
        @Override
        public double evaluate(double level) {
            return value;
        }
    }

    private record Level() implements Node {
        @Override
        public double evaluate(double level) {
            return level;
        }
    }

    private record Negate(Node operand) implements Node {
        @Override
        public double evaluate(double level) {
            return -operand.evaluate(level);
        }
    }

    private record BinaryOp(char operator, Node left, Node right) implements Node {
        @Override
        public double evaluate(double level) {
            double a = left.evaluate(level);
            double b = right.evaluate(level);
            return switch (operator) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> a / b;
                case '^' -> Math.pow(a, b);
                default -> throw new IllegalStateException("Unknown operator '" + operator + "'");
            };
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Node parseExpressionAndExpectEnd() {
            Node node = parseExpression();
            skipWhitespace();
            if (pos != text.length()) {
                throw new IllegalArgumentException("Unexpected character at position " + pos + " in formula: " + text);
            }
            return node;
        }

        private Node parseExpression() {
            Node node = parseTerm();
            while (true) {
                skipWhitespace();
                if (peek('+')) {
                    pos++;
                    node = new BinaryOp('+', node, parseTerm());
                } else if (peek('-')) {
                    pos++;
                    node = new BinaryOp('-', node, parseTerm());
                } else {
                    return node;
                }
            }
        }

        private Node parseTerm() {
            Node node = parsePower();
            while (true) {
                skipWhitespace();
                if (peek('*')) {
                    pos++;
                    node = new BinaryOp('*', node, parsePower());
                } else if (peek('/')) {
                    pos++;
                    node = new BinaryOp('/', node, parsePower());
                } else {
                    return node;
                }
            }
        }

        private Node parsePower() {
            Node base = parseUnary();
            skipWhitespace();
            if (peek('^')) {
                pos++;
                // Right-associative: 2^3^2 = 2^(3^2), same as parseExpression's own recursive-into-parsePower call chain.
                return new BinaryOp('^', base, parsePower());
            }
            return base;
        }

        private Node parseUnary() {
            skipWhitespace();
            if (peek('-')) {
                pos++;
                return new Negate(parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skipWhitespace();
            if (peek('(')) {
                pos++;
                Node node = parseExpression();
                skipWhitespace();
                if (!peek(')')) {
                    throw new IllegalArgumentException("Missing closing ')' in formula: " + text);
                }
                pos++;
                return node;
            }
            if (matchWord("level")) {
                return new Level();
            }
            return parseNumber();
        }

        private Node parseNumber() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("Expected a number, 'level', or '(' at position " + pos + " in formula: " + text);
            }
            return new Constant(Double.parseDouble(text.substring(start, pos)));
        }

        private boolean matchWord(String word) {
            if (text.regionMatches(true, pos, word, 0, word.length())) {
                pos += word.length();
                return true;
            }
            return false;
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
