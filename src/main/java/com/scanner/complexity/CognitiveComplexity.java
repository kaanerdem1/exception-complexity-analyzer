package com.scanner.complexity;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Sonar Cognitive Complexity modeline yakın okunabilirlik skoru (metod gövdesi).
 * @see <a href="https://www.sonarsource.com/docs/CognitiveComplexity.pdf">Sonar white paper</a>
 */
final class CognitiveComplexity {

    private CognitiveComplexity() {
    }

    static int of(Node root) {
        if (root == null) {
            return 0;
        }
        Context ctx = new Context();
        root.accept(new IncrementVisitor(), ctx);
        return ctx.score;
    }

    private static final class Context {
        int score;
        int nesting;
    }

    private static final class IncrementVisitor extends VoidVisitorAdapter<Context> {

        private void add(Context ctx, boolean includeNesting) {
            ctx.score += 1 + (includeNesting ? ctx.nesting : 0);
        }

        private void withNesting(Context ctx, Runnable descend) {
            ctx.nesting++;
            try {
                descend.run();
            } finally {
                ctx.nesting--;
            }
        }

        @Override
        public void visit(IfStmt n, Context ctx) {
            add(ctx, true);
            n.getCondition().accept(this, ctx);
            if (isElseIf(n)) {
                n.getThenStmt().accept(this, ctx);
                n.getElseStmt().ifPresent(e -> e.accept(this, ctx));
            } else {
                withNesting(ctx, () -> {
                    n.getThenStmt().accept(this, ctx);
                    n.getElseStmt().ifPresent(e -> e.accept(this, ctx));
                });
            }
        }

        @Override
        public void visit(ForStmt n, Context ctx) {
            add(ctx, true);
            withNesting(ctx, () -> super.visit(n, ctx));
        }

        @Override
        public void visit(ForEachStmt n, Context ctx) {
            add(ctx, true);
            withNesting(ctx, () -> super.visit(n, ctx));
        }

        @Override
        public void visit(WhileStmt n, Context ctx) {
            add(ctx, true);
            withNesting(ctx, () -> super.visit(n, ctx));
        }

        @Override
        public void visit(DoStmt n, Context ctx) {
            add(ctx, true);
            withNesting(ctx, () -> super.visit(n, ctx));
        }

        @Override
        public void visit(SwitchStmt n, Context ctx) {
            add(ctx, true);
            withNesting(ctx, () -> super.visit(n, ctx));
        }

        @Override
        public void visit(CatchClause n, Context ctx) {
            add(ctx, true);
            withNesting(ctx, () -> super.visit(n, ctx));
        }

        @Override
        public void visit(ConditionalExpr n, Context ctx) {
            add(ctx, true);
            super.visit(n, ctx);
        }

        @Override
        public void visit(BinaryExpr n, Context ctx) {
            if (n.getOperator() == BinaryExpr.Operator.AND || n.getOperator() == BinaryExpr.Operator.OR) {
                add(ctx, false);
            }
            super.visit(n, ctx);
        }

        @Override
        public void visit(LambdaExpr n, Context ctx) {
            withNesting(ctx, () -> super.visit(n, ctx));
        }
    }

    private static boolean isElseIf(IfStmt node) {
        return node.getParentNode()
                .filter(parent -> parent instanceof IfStmt)
                .map(parent -> (IfStmt) parent)
                .flatMap(IfStmt::getElseStmt)
                .filter(elseStmt -> elseStmt == node)
                .isPresent();
    }
}
