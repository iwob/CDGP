package tests

import cdgp.{CDGPState, GetValueParser, LoadSygusBenchmark, SMTLIBFormatter}
import fuel.util.{CollectorStdout, Options, Rng}
import org.junit.Test
import org.junit.Assert._
import swim.tree.Op


object TestCDGPState {
  val scriptMax =
"""(set-logic LIA)
(synth-fun max2 ((x Int) (y Int)) Int
  ((Start Int (x y 0 1
(+ Start Start)
(- Start Start)
(ite StartBool Start Start)))
(StartBool Bool ((and StartBool StartBool)
  (or StartBool StartBool)
  (not StartBool)
  (<= Start Start)
  (= Start Start)
  (>= Start Start)))))
(declare-var x Int)
(declare-var y Int)
(constraint (>= (max2 x y) x))
(constraint (>= (max2 x y) y))
(constraint (or (= x (max2 x y)) (= y (max2 x y))))
(check-synth)"""

  val scriptMaxRenamedVars =
"""(set-logic LIA)
(synth-fun max2 ((a Int) (b Int)) Int
  ((Start Int (a b 0 1
(+ Start Start)
(- Start Start)
(ite StartBool Start Start)))
(StartBool Bool ((and StartBool StartBool)
  (or StartBool StartBool)
  (not StartBool)
  (<= Start Start)
  (= Start Start)
  (>= Start Start)))))
(declare-var x Int)
(declare-var y Int)
(constraint (>= (max2 x y) x))
(constraint (>= (max2 x y) y))
(constraint (or (= x (max2 x y)) (= y (max2 x y))))
(check-synth)"""

  val scriptPsuedoMaxRenamedVars =
"""(set-logic LIA)
(synth-fun max2 ((a Int) (b Int)) Int
  ((Start Int (a b 0 1
(+ Start Start)
(- Start Start)
(ite StartBool Start Start)))
(StartBool Bool ((and StartBool StartBool)
  (or StartBool StartBool)
  (not StartBool)
  (<= Start Start)
  (= Start Start)
  (>= Start Start)))))
(declare-var x Int)
(declare-var y Int)
(constraint (>= (max2 x y) x))
(constraint (>= (max2 x y) y))
;(constraint (or (= x (max2 x y)) (= y (max2 x y))))
(check-synth)"""

  val scriptNotSingleInvocation =
"""; three.sl
; Synthesize x * 3 mod 10
(set-logic LIA)
(synth-fun f ((x Int)) Int
   ((Start Int (x 3 7 10 (* Start Start) (mod Start Start)))))
(declare-var x Int)
(constraint (= (f x) (f (+ x 10))))
(constraint (= (f 1) 3))
(constraint (= (f 2) 6))
(constraint (= (f 3) 9))
(constraint (= (f 4) 2))
(constraint (= (f 5) 5))
(constraint (= (f 6) 8))
(constraint (= (f 7) 1))
(constraint (= (f 8) 4))
(constraint (= (f 9) 7))
(constraint (= (f 0) 0))
(check-synth)
"""
}

final class TestCDGPState {
  implicit val emptyOpt = Options("--searchAlgorithm Lexicase --solverPath " +
    f"${Global.solverPath}")
  implicit val coll = CollectorStdout(emptyOpt)
  implicit val rng = Rng(emptyOpt)

  @Test
  def test_evalOnTestsMax(): Unit = {
    val code = TestCDGPState.scriptMax
    val problem = LoadSygusBenchmark.parseText(code)
    val state = new CDGPState(problem)
    val op = Op.fromStr("ite(>=(x y) x 0)", useSymbols=false)
    val t1 = (GetValueParser("((x 4)(y 3))").toMap, Some(4))
    val t2 = (GetValueParser("((x 5)(y 1))").toMap, Some(5))
    val t3 = (GetValueParser("((x 1)(y 3))").toMap, Some(3))
    val tests = Seq(t1, t2, t3)
    val res = state.evalOnTests(op, tests)
    assertEquals(Seq(0, 0, 1), res)
  }

  @Test
  def test_evalOnTestsMaxDifferentVarOrderInModel(): Unit = {
    val code = TestCDGPState.scriptMax
    val problem = LoadSygusBenchmark.parseText(code)
    val state = new CDGPState(problem)
    val op = Op.fromStr("ite(>=(x y) x 0)", useSymbols=false)
    val t1 = (GetValueParser("((y 3)(x 4))").toMap, Some(4))
    val t2 = (GetValueParser("((y 1)(x 5))").toMap, Some(5))
    val t3 = (GetValueParser("((y 3)(x 1))").toMap, Some(3))
    val tests = Seq(t1, t2, t3)
    val res = state.evalOnTests(op, tests)
    assertEquals(Seq(0, 0, 1), res)
  }

  @Test
  def test_evalOnTestsMaxRenamedVars(): Unit = {
    val code = TestCDGPState.scriptMaxRenamedVars
    val problem = LoadSygusBenchmark.parseText(code)
    val state = new CDGPState(problem)
    val op = Op.fromStr("ite(>=(x y) x 0)", useSymbols=false)
    val t1 = (GetValueParser("((x 4)(y 3))").toMap, Some(4))
    val t2 = (GetValueParser("((x 5)(y 1))").toMap, Some(5))
    val t3 = (GetValueParser("((x 1)(y 3))").toMap, Some(3))
    val tests = Seq(t1, t2, t3)
    val res = state.evalOnTests(op, tests)
    assertEquals(Seq(0, 0, 1), res)
  }

  @Test
  def test_checkIfOnlySingleCorrectAnswer_unsat(): Unit = {
    val code = TestCDGPState.scriptMaxRenamedVars
    val problem = LoadSygusBenchmark.parseText(code)
//    val op = Op.fromStr("ite(>=(x y) x 0)", useSymbols=false)
//    val queryVer = SMTLIBFormatter.verify(problem, op)
//    println("queryVer:\n" + queryVer)
    val query = SMTLIBFormatter.checkIfOnlySingleCorrectAnswer(problem)
    println("query:\n" + query)
    val state = new CDGPState(problem)
    val (decision, output) = state.solver.runSolver(query)
    assertEquals("unsat", decision)
    assertEquals(None, output)
  }

  @Test
  def test_checkIfOnlySingleCorrectAnswer_sat(): Unit = {
    val code = TestCDGPState.scriptPsuedoMaxRenamedVars
    val problem = LoadSygusBenchmark.parseText(code)
    val query = SMTLIBFormatter.checkIfOnlySingleCorrectAnswer(problem)
    println("query:\n" + query)
    val state = new CDGPState(problem)
    val (decision, output) = state.solver.runSolver(query)
    assertEquals("sat", decision)
  }
}