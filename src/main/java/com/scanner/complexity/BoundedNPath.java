package com.scanner.complexity;

import java.util.Set;
import java.util.function.BiFunction;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;

import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.ast.NodeStream;
import net.sourceforge.pmd.lang.java.ast.ASTAnonymousClassDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTBlock;
import net.sourceforge.pmd.lang.java.ast.ASTBreakStatement;
import net.sourceforge.pmd.lang.java.ast.ASTConditionalExpression;
import net.sourceforge.pmd.lang.java.ast.ASTContinueStatement;
import net.sourceforge.pmd.lang.java.ast.ASTDoStatement;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTForStatement;
import net.sourceforge.pmd.lang.java.ast.ASTForeachStatement;
import net.sourceforge.pmd.lang.java.ast.ASTIfStatement;
import net.sourceforge.pmd.lang.java.ast.ASTInfixExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLambdaExpression;
import net.sourceforge.pmd.lang.java.ast.ASTLoopStatement;
import net.sourceforge.pmd.lang.java.ast.ASTReturnStatement;
import net.sourceforge.pmd.lang.java.ast.ASTStatement;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchArrowBranch;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchBranch;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchExpression;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchFallthroughBranch;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchLike;
import net.sourceforge.pmd.lang.java.ast.ASTSwitchStatement;
import net.sourceforge.pmd.lang.java.ast.ASTThrowStatement;
import net.sourceforge.pmd.lang.java.ast.ASTUnaryExpression;
import net.sourceforge.pmd.lang.java.ast.ASTWhileStatement;
import net.sourceforge.pmd.lang.java.ast.ASTYieldStatement;
import net.sourceforge.pmd.lang.java.ast.BinaryOp;
import net.sourceforge.pmd.lang.java.ast.JavaNode;
import net.sourceforge.pmd.lang.java.ast.JavaVisitorBase;
import net.sourceforge.pmd.lang.java.ast.ReturnScopeNode;
import net.sourceforge.pmd.lang.java.ast.internal.JavaAstUtils;

/**
 * PMD 7.29 NPath sayacının aynı yürüyüşü. Yol sayısı skor tavanına varınca durur.
 * Tavanın altındaki sonuç PMD ile aynıdır. Tavan ve üstü bu kuralda 0 puandır.
 */
final class BoundedNPath {

    private static final ThreadLocal<Integer> LIMIT = new ThreadLocal<>();
    private static final Saturated SATURATED = new Saturated();

    private BoundedNPath() {
    }

    static int compute(ReturnScopeNode node) {
        int limit = scoreLimit();
        LIMIT.set(limit);
        try {
            ASTBlock body = node.getBody();
            if (body == null) {
                return 1;
            }
            CfVisitState state = new CfVisitState(1);
            state = body.acceptVisitor(CfVisitor.INSTANCE, state);
            long paths = state.getNumPathsToExit();
            if (paths >= limit || paths > Integer.MAX_VALUE) {
                return limit;
            }
            return (int) paths;
        } catch (Saturated saturated) {
            return limit;
        } finally {
            LIMIT.remove();
        }
    }

    static int scoreLimit() {
        HealthScoreCatalog.Rule rule = HealthScoreEngine.get().catalog().find("NPathComplexity");
        if (rule == null || rule.metricMax() < 1) {
            return 200;
        }
        return rule.metricMax();
    }

    private static long guardedAdd(long a, long b) {
        int limit = LIMIT.get();
        long sum;
        try {
            sum = Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            throw SATURATED;
        }
        if (sum >= limit) {
            throw SATURATED;
        }
        return sum;
    }

    private static long pathsForBranch(long start, int alternatives) {
        if (alternatives <= 0) {
            return 0;
        }
        int limit = LIMIT.get();
        if (start >= limit || start > limit / alternatives) {
            throw SATURATED;
        }
        return start * alternatives;
    }

    private static DecisionPoint getControlFlowInCondition(ASTExpression expression, CfPoint point) {
        return expression.acceptVisitor(CfExpressionVisitor.INSTANCE, new CfPoint(point));
    }

    private static final class Saturated extends RuntimeException {
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class CfExpressionVisitor extends JavaVisitorBase<CfPoint, DecisionPoint> {

        static final CfExpressionVisitor INSTANCE = new CfExpressionVisitor();

        @Override
        public DecisionPoint visitJavaNode(JavaNode node, CfPoint point) {
            for (JavaNode child : node.children()) {
                point = child.acceptVisitor(this, point).endPaths();
            }
            return point;
        }

        @Override
        public DecisionPoint visit(ASTAnonymousClassDeclaration node, CfPoint point) {
            return point;
        }

        @Override
        public DecisionPoint visit(ASTLambdaExpression node, CfPoint point) {
            return point;
        }

        @Override
        public DecisionPoint visit(ASTConditionalExpression node, CfPoint point) {
            DecisionPoint condition = node.getCondition().acceptVisitor(this, new CfPoint(point));
            DecisionPoint thenState = node.getThenBranch().acceptVisitor(this, new CfPoint(condition.truePoint()));
            DecisionPoint elseState = node.getElseBranch().acceptVisitor(this, new CfPoint(condition.falsePoint()));
            return new BooleanDecisionPoint(
                    thenState.endPaths().connectTo(new CfPoint(elseState.endPaths())),
                    thenState.truePoint().connectTo(new CfPoint(elseState.truePoint())),
                    thenState.falsePoint().connectTo(new CfPoint(elseState.falsePoint()))
            );
        }

        @Override
        public DecisionPoint visit(ASTUnaryExpression node, CfPoint point) {
            if (JavaAstUtils.isBooleanNegation(node)) {
                DecisionPoint condition = node.getOperand().acceptVisitor(this, point);
                return condition.negate();
            }
            return super.visit(node, point);
        }

        @Override
        public DecisionPoint visit(ASTInfixExpression node, CfPoint point) {
            if (node.getOperator() == BinaryOp.CONDITIONAL_AND) {
                DecisionPoint leftState = node.getLeftOperand().acceptVisitor(this, point);
                DecisionPoint rightState = node.getRightOperand().acceptVisitor(this, new CfPoint(leftState.truePoint()));
                return new BooleanDecisionPoint(
                        leftState.endPaths().connectTo(new CfPoint(rightState.endPaths())),
                        rightState.truePoint(),
                        rightState.falsePoint().connectTo(leftState.falsePoint())
                );
            }
            if (node.getOperator() == BinaryOp.CONDITIONAL_OR) {
                DecisionPoint leftState = node.getLeftOperand().acceptVisitor(this, point);
                DecisionPoint rightState = node.getRightOperand().acceptVisitor(this, new CfPoint(leftState.falsePoint()));
                return new BooleanDecisionPoint(
                        leftState.endPaths().connectTo(new CfPoint(rightState.endPaths())),
                        rightState.truePoint().connectTo(leftState.truePoint()),
                        rightState.falsePoint()
                );
            }
            return super.visit(node, point);
        }
    }

    private interface DecisionPoint {
        CfPoint endPaths();

        CfPoint truePoint();

        CfPoint falsePoint();

        DecisionPoint negate();
    }

    private static final class BooleanDecisionPoint implements DecisionPoint {
        final CfPoint pointLeadingToThisDecision;
        final CfPoint truePoint;
        final CfPoint falsePoint;

        BooleanDecisionPoint(CfPoint pred, CfPoint truePoint, CfPoint falsePoint) {
            this.pointLeadingToThisDecision = pred;
            this.truePoint = truePoint;
            this.falsePoint = falsePoint;
        }

        @Override
        public CfPoint truePoint() {
            return truePoint;
        }

        @Override
        public CfPoint falsePoint() {
            return falsePoint;
        }

        @Override
        public CfPoint endPaths() {
            return pointLeadingToThisDecision;
        }

        @Override
        public DecisionPoint negate() {
            return new BooleanDecisionPoint(pointLeadingToThisDecision, falsePoint, truePoint);
        }
    }

    private static final class CfVisitor extends JavaVisitorBase<CfVisitState, CfVisitState> {

        static final CfVisitor INSTANCE = new CfVisitor();

        @Override
        protected CfVisitState visitChildren(Node node, CfVisitState state) {
            for (int i = 0, numChildren = node.getNumChildren(); i < numChildren; i++) {
                state = node.getChild(i).acceptVisitor(this, state);
            }
            return state;
        }

        @Override
        public CfVisitState visit(ASTBlock node, CfVisitState state) {
            return visitChildren(node, state);
        }

        @Override
        public CfVisitState visit(ASTReturnStatement node, CfVisitState state) {
            if (node.getExpr() != null) {
                state = node.getExpr().acceptVisitor(this, state);
            }
            return state.abruptCompletion(state.returnPoint);
        }

        @Override
        public CfVisitState visit(ASTThrowStatement node, CfVisitState state) {
            state = node.getExpr().acceptVisitor(this, state);
            return state.abruptCompletion(state.throwPoint);
        }

        @Override
        public CfVisitState visit(ASTBreakStatement node, CfVisitState state) {
            return state.abruptCompletion(state.getBreakPoint(node.getLabel()));
        }

        @Override
        public CfVisitState visit(ASTYieldStatement node, CfVisitState state) {
            state = node.getExpr().acceptVisitor(this, state);
            return state.abruptCompletion(state.yieldPoint);
        }

        @Override
        public CfVisitState visit(ASTContinueStatement node, CfVisitState state) {
            return state.abruptCompletion(state.getContinuePoint(node.getLabel()));
        }

        @Override
        public CfVisitState visit(ASTIfStatement node, CfVisitState data) {
            return handleLabelsForRegularStmt(node, data, (stmt, state) -> {
                DecisionPoint condition = getControlFlowInCondition(stmt.getCondition(), state.currentProgramPoint);
                CfVisitState thenState = stmt.getThenBranch().acceptVisitor(this, state.fork(condition.truePoint()));
                if (stmt.getElseBranch() != null) {
                    CfVisitState elseState = stmt.getElseBranch().acceptVisitor(this, state.fork(condition.falsePoint()));
                    return thenState.absorb(elseState.currentProgramPoint);
                }
                return thenState.absorb(condition.falsePoint());
            });
        }

        private CfVisitState visitSwitch(ASTSwitchLike switchLike, CfVisitState state) {
            CfVisitState startState = switchLike.getTestedExpression().acceptVisitor(this, state);
            final long numPathsToStart = startState.currentProgramPoint.numPathsUntilThisPoint;
            CfVisitState currentFallthroughState = startState.fork(new CfPoint(0));
            for (ASTSwitchBranch branch : switchLike) {
                CfPoint thisBranch = new CfPoint(pathsForBranch(numPathsToStart, JavaAstUtils.numAlternatives(branch)));
                if (branch instanceof ASTSwitchFallthroughBranch fallthrough) {
                    currentFallthroughState.absorb(thisBranch);
                    NodeStream<? extends ASTStatement> statements = fallthrough.getStatements();
                    currentFallthroughState = statements.reduce(currentFallthroughState,
                            (point, stmt) -> stmt.acceptVisitor(this, point));
                } else if (branch instanceof ASTSwitchArrowBranch arrow) {
                    CfVisitState branchState = startState.fork(thisBranch);
                    branchState = arrow.getRightHandSide().acceptVisitor(this, branchState);
                    CfPoint exitPoint = switchLike instanceof ASTSwitchExpression
                            ? branchState.yieldPoint : branchState.breakPoint;
                    branchState.abruptCompletion(exitPoint);
                }
            }
            if (!JavaAstUtils.isTotalSwitch(switchLike)) {
                currentFallthroughState.absorb(startState.currentProgramPoint);
            }
            return currentFallthroughState;
        }

        @Override
        public CfVisitState visit(ASTSwitchStatement node, CfVisitState data) {
            return handleLabels(node, data, true, false, this::visitSwitch);
        }

        @Override
        public CfVisitState visit(ASTSwitchExpression node, CfVisitState state) {
            final CfPoint prevYield = state.yieldPoint;
            state.yieldPoint = new CfPoint(0);
            CfVisitState endState = visitSwitch(node, state);
            endState.absorb(state.yieldPoint);
            endState.yieldPoint = prevYield;
            return endState;
        }

        @Override
        public CfVisitState visit(ASTForeachStatement node, CfVisitState state) {
            return visitLoopExceptDoWhile(node, state, node.getIterableExpr(), null, node.getIterableExpr());
        }

        @Override
        public CfVisitState visit(ASTWhileStatement node, CfVisitState state) {
            return visitLoopExceptDoWhile(node, state, null, null, node.getCondition());
        }

        @Override
        public CfVisitState visit(ASTForStatement node, CfVisitState state) {
            return visitLoopExceptDoWhile(node, state, node.getInit(), node.getUpdate(), node.getCondition());
        }

        private CfVisitState visitLoopExceptDoWhile(ASTLoopStatement node, CfVisitState state,
                                                     @Nullable JavaNode init, @Nullable JavaNode update,
                                                     @Nullable ASTExpression conditionNode) {
            state = acceptOpt(init, state);
            DecisionPoint decision = getLoopCondition(conditionNode, state.currentProgramPoint);
            CfVisitState endState = handleLabels(node, state.fork(decision.truePoint()), true, true,
                    (loop, state2) -> {
                        state2 = loop.getBody().acceptVisitor(this, state2);
                        return acceptOpt(update, state2);
                    },
                    (afterBody, breakPoint, contPoint) -> afterBody.absorb(breakPoint).absorb(contPoint)
            );
            if (JavaAstUtils.isUnconditionalLoop(node)) {
                return endState;
            }
            return endState.absorb(decision.falsePoint());
        }

        @Override
        public CfVisitState visit(ASTDoStatement node, CfVisitState state) {
            return handleLabels(node, state, true, true,
                    (loop, state2) -> loop.getBody().acceptVisitor(this, state2),
                    (afterBody, breakPoint, contPoint) -> {
                        CfPoint beforeCond = afterBody.currentProgramPoint.connectTo(contPoint);
                        DecisionPoint condition = getLoopCondition(node.getCondition(), beforeCond);
                        CfVisitState endState = afterBody.withPoint(condition.falsePoint()).absorb(breakPoint);
                        condition.truePoint().connectTo(endState.loopBackPaths);
                        return endState;
                    });
        }

        private DecisionPoint getLoopCondition(@Nullable ASTExpression condition, CfPoint point) {
            if (condition != null) {
                return getControlFlowInCondition(condition, point);
            }
            return point;
        }

        @Override
        public CfVisitState visitExpression(ASTExpression node, CfVisitState state) {
            CfPoint endPoint = getControlFlowInCondition(node, state.currentProgramPoint).endPaths();
            return state.withPoint(endPoint);
        }

        private CfVisitState acceptOpt(@Nullable JavaNode node, CfVisitState state) {
            if (node == null) {
                return state;
            }
            return node.acceptVisitor(this, state);
        }

        private static <N extends ASTStatement> CfVisitState handleLabelsForRegularStmt(
                N stmt, CfVisitState state, BiFunction<N, CfVisitState, CfVisitState> action) {
            return handleLabels(stmt, state, false, false, action);
        }

        private static <N extends ASTStatement> CfVisitState handleLabels(
                N stmt, CfVisitState state, boolean canBreakWithoutLabel, boolean canContinue,
                BiFunction<N, CfVisitState, CfVisitState> action) {
            return handleLabels(stmt, state, canBreakWithoutLabel, canContinue, action,
                    (endState, breakPoint, contPoint) -> endState.absorb(breakPoint));
        }

        private interface BreakAndContinueHandler {
            CfVisitState handleBreakAndContinue(CfVisitState state, CfPoint breakPoint, @Nullable CfPoint continuePoint);
        }

        private static <N extends ASTStatement> CfVisitState handleLabels(
                N stmt, CfVisitState state, boolean canBreakWithoutLabel, boolean canContinue,
                BiFunction<N, CfVisitState, CfVisitState> action, BreakAndContinueHandler callback) {
            Set<String> labels = JavaAstUtils.getStatementLabels(stmt);
            if (labels.isEmpty() && !canBreakWithoutLabel && !canContinue) {
                return action.apply(stmt, state);
            }
            final PMap<String, CfPoint> prevLabeledBreaks = state.labeledBreakPoints;
            final PMap<String, CfPoint> prevLabeledContinues = state.labeledContinuePoints;
            final CfPoint prevBreak = state.breakPoint;
            final CfPoint prevContinue = state.continuePoint;

            CfPoint breakPoint = new CfPoint(0);
            for (String label : labels) {
                state.labeledBreakPoints = state.labeledBreakPoints.plus(label, breakPoint);
            }
            if (canBreakWithoutLabel) {
                state.breakPoint = breakPoint;
            }

            CfPoint continuePoint = null;
            if (canContinue) {
                continuePoint = new CfPoint(0);
                state.continuePoint = continuePoint;
                for (String label : labels) {
                    state.labeledContinuePoints = state.labeledContinuePoints.plus(label, continuePoint);
                }
            }

            CfVisitState endState = action.apply(stmt, state);
            endState.continuePoint = prevContinue;
            endState.breakPoint = prevBreak;
            endState.labeledBreakPoints = prevLabeledBreaks;
            endState.labeledContinuePoints = prevLabeledContinues;
            return callback.handleBreakAndContinue(endState, breakPoint, continuePoint);
        }
    }

    private static final class CfPoint implements DecisionPoint {
        private long numPathsUntilThisPoint;

        CfPoint(long start) {
            this.numPathsUntilThisPoint = start;
        }

        CfPoint(CfPoint clone) {
            this.numPathsUntilThisPoint = clone.numPathsUntilThisPoint;
        }

        CfPoint connectTo(CfPoint point) {
            point.numPathsUntilThisPoint = guardedAdd(point.numPathsUntilThisPoint, this.numPathsUntilThisPoint);
            return point;
        }

        @Override
        public CfPoint truePoint() {
            return this;
        }

        @Override
        public CfPoint falsePoint() {
            return this;
        }

        @Override
        public CfPoint endPaths() {
            return this;
        }

        @Override
        public DecisionPoint negate() {
            return this;
        }
    }

    private static final class CfVisitState {
        private PMap<String, CfPoint> labeledBreakPoints;
        private PMap<String, CfPoint> labeledContinuePoints;
        private @Nullable CfPoint breakPoint;
        private @Nullable CfPoint continuePoint;
        private @Nullable CfPoint yieldPoint;
        private final CfPoint returnPoint;
        private final CfPoint throwPoint;
        private final CfPoint loopBackPaths;
        private CfPoint currentProgramPoint;

        CfVisitState(long numPathsUntilThisPoint) {
            this.labeledBreakPoints = HashTreePMap.empty();
            this.labeledContinuePoints = HashTreePMap.empty();
            this.returnPoint = new CfPoint(0);
            this.throwPoint = new CfPoint(0);
            this.loopBackPaths = new CfPoint(0);
            this.currentProgramPoint = new CfPoint(numPathsUntilThisPoint);
        }

        CfVisitState(CfVisitState state, CfPoint currentProgramPoint) {
            this.labeledBreakPoints = state.labeledBreakPoints;
            this.labeledContinuePoints = state.labeledContinuePoints;
            this.breakPoint = state.breakPoint;
            this.continuePoint = state.continuePoint;
            this.yieldPoint = state.yieldPoint;
            this.returnPoint = state.returnPoint;
            this.throwPoint = state.throwPoint;
            this.loopBackPaths = state.loopBackPaths;
            this.currentProgramPoint = currentProgramPoint;
        }

        CfVisitState fork(CfPoint currentPoint) {
            return new CfVisitState(this, new CfPoint(currentPoint.numPathsUntilThisPoint));
        }

        CfVisitState absorb(CfPoint point) {
            point.connectTo(currentProgramPoint);
            return this;
        }

        CfVisitState withPoint(CfPoint point) {
            this.currentProgramPoint = point;
            return this;
        }

        @Nullable
        CfPoint getBreakPoint(@Nullable String label) {
            if (label == null) {
                return breakPoint;
            }
            return labeledBreakPoints.get(label);
        }

        @Nullable
        CfPoint getContinuePoint(@Nullable String label) {
            if (label == null) {
                return continuePoint;
            }
            return labeledContinuePoints.get(label);
        }

        CfVisitState abruptCompletion(CfPoint exitPoint) {
            if (exitPoint != null) {
                this.currentProgramPoint.connectTo(exitPoint);
            }
            this.currentProgramPoint.numPathsUntilThisPoint = 0;
            return this;
        }

        long getNumPathsToExit() {
            return currentProgramPoint
                    .connectTo(returnPoint)
                    .connectTo(throwPoint)
                    .connectTo(loopBackPaths).numPathsUntilThisPoint;
        }
    }
}
