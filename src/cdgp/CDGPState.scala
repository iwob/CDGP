package cdgp

import fuel.util.{Collector, Options, TRandom}
import swim.Grammar
import swim.tree.Op
import sygus.VarDeclCmd
import sygus16.SyGuS16


/**
  * Manages everything needed for the CDGP to run. Among other things, handles interaction
  * with the solver, and contains test manager. As argument given is the definition of the
  * SYGUS problem to be solved, and from it extracted are all important information such
  * as grammar and logic to be used.
  */
class CDGPState(sygusProblem: SyGuS16)
               (implicit opt: Options, coll: Collector, rng: TRandom) {
  // The types for input and output
  type I = Map[String, Any]
  type O = Any
  val testsManager = new TestsManagerCDGP[I, O]()

  val method = opt('method, "CDGP")
  val searchAlg = opt('searchAlgorithm)
  assert(method == "CDGP" || method == "GPR", "Invalid method! Possible values: 'CDGP', 'GPR'.")
  assert(searchAlg == "GP" || searchAlg == "GPSteadyState" ||
         searchAlg == "Lexicase" || searchAlg == "LexicaseSteadyState",
         "Invalid searchAlgorithm! Possible values: 'GP', 'GPSteadyState', 'Lexicase', 'LexicaseSteadyState'.")

  // Other parameters
  val GPRminInt: Int = opt('GPRminInt, -100)
  val GPRmaxInt: Int = opt('GPRmaxInt, 100)
  val CDGPtestsRatio: Double = opt('CDGPtestsRatio, 1.0, (x: Double) => x >= 0.0 && x <= 1.0)
  val GPRtestsRatio: Double = opt('GPRtestsRatio, 1.0, (x: Double) => x >= 0.0 && x <= 1.0)
  val CDGPoneTestPerIter: Boolean = opt('CDGPoneTestPerIter, false)
  val GPRoneTestPerIter: Boolean = opt('GPRoneTestPerIter, false)
  val timeout: Int = opt('solverTimeout, 0)


  /**
    * Checks using SMT solver if the given problem has only one correct answer for
    * any input.
    */
  def hasSingleAnswerForEveryInput(problem: SyGuS16): Option[Boolean] = {
    val query = SMTLIBFormatter.checkIfSingleAnswerForEveryInput(problem)
    // println("\nQuery checkIfSingleAnswerForEveryInput:\n" + query)
    val (dec, _) = solver.runSolver(query)
    if (dec == "sat") Some(false)
    else if (dec == "unsat") Some(true)
    else None
  }

  def getTestCasesMode(problem: SyGuS16): String = {
    val singleInvoc = SygusUtils.hasSingleInvocationProperty(sygusProblem)
    println(f"(singleInvocationProperty $singleInvoc)")
    coll.set("cdgp.singleInvocationProperty", singleInvoc)
    if (singleInvoc) {
      // Checking for the single answer property has sense only if the problem
      // has single invocation property.
      val singleAnswer = hasSingleAnswerForEveryInput(sygusProblem)
      val supportForAllTerms = !SygusUtils.containsUnsupportedComplexTerms(sygusProblem)
      println(f"(singleAnswerForEveryInput ${singleAnswer.getOrElse("unknown")})")
      println(f"(supportForAllTerms $supportForAllTerms)")
      coll.set("cdgp.singleAnswerForEveryInput", singleAnswer.getOrElse("unknown"))
      coll.set("cdgp.supportForAllTerms", supportForAllTerms)
      if (singleAnswer.getOrElse(false) && supportForAllTerms)
        "gp"  // it may be considered to treat unknown singleAnswer as true, with the potential risk of losing "soundness" of the fitness
      else
        "solver"
    }
    else
      "solver"
  }

  /*
   * Depending on the properties of the problem, CDGPState will switch between using
   * GP domain and executing the solver for computing fitness.
   */
  val testCasesMode: String = getTestCasesMode(sygusProblem)
  assert(testCasesMode == "solver" || testCasesMode == "gp")
  val useDomainToComputeFitness: Boolean = testCasesMode == "gp"
  println(f"(testCasesMode $testCasesMode)")
  coll.set("cdgp.testCasesMode", testCasesMode)
  if (!useDomainToComputeFitness)
    println("INFO: solver will be used to compute fitness. Expect major efficiency decrease" +
      " in comparison with GP test cases mode.")


  val synthTasks = ExtractSynthesisTasks(sygusProblem)
  if (synthTasks.size > 1)
    throw new Exception("SKIPPING: Multiple synth-fun commands detected. Cannot handle such problems.")
  val synthTask: SygusSynthesisTask = synthTasks.head
  val invocations: Seq[Seq[String]] = SygusUtils.getSynthFunsInvocationsInfo(sygusProblem, synthTask.fname)
  val grammar: Grammar = ExtractSygusGrammar(synthTask)
  val varDecls: List[VarDeclCmd] = sygusProblem.cmds.collect { case v: VarDeclCmd => v }


  // Creating solver manager
  private def solverPath = opt('solverPath)
  private def solverArgs = opt.getOption("solverArgs")
  lazy val solver = new SolverManager(solverPath, solverArgs, verbose=false)



  /**
    * Tests a program on the available tests and returns the vector of 0s (passed test)
    * and 1s (failed test). Depending on the problem will either optimize by executing
    * program directly on the tests, or will have to resort to a solver.
    */
  def evalOnTests(s: Op, tests: Seq[(I, Option[O])]): Seq[Int] = {
    def handleException(test: (I, Option[O]), message: String) {
      val msg = f"Error during evalutation of $s and test $test: $message"
      coll.set("error_evalOnTests", msg)
      println(msg)
    }
    for (test <- tests) yield {
      try {
        if (useDomainToComputeFitness) evalOnTestsDomain(s, test)
        else evalOnTestsSolver(s, test)
      }
      catch { case e: Throwable => handleException(test, e.getMessage); 1 }
    }
  }

  /**
    * Checks correctness of the program only for the given test.
    * Tests here always have None as the answer, because in general there is no
    * single answer for the problem being solved in 'solver' mode.
    */
  def evalOnTestsSolver(s: Op, test: (I, Option[O])): Int = {
    val testInputsMap: Map[String, Any] = test._1
    val (dec, _) = checkOnInputOnly(s, testInputsMap)
    if (dec == "sat") 0 else 1
  }

  /**
    * Checks correctness of the program only for the given test.
    * If test has a defined expected answer, then it is compared with the answer
    * obtained by executing the program in the domain simulating semantics of SMT
    * theory.
    * If test don't have defined expected answer, then the program's output is verified
    * by the solver for consistency with the specification. The test will be updated if
    * this output is deemed consistent by the solver.
    */
  def evalOnTestsDomain(s: Op, test: (I, Option[O])): Int = {
    val testInputsMap: Map[String, Any] = test._1
    val testOutput: Option[Any] = test._2
    val testInputsRenamed = SygusUtils.renameVars(testInputsMap, synthTask.argNames, invocations.head)
    val output = LIA(s)(testInputsRenamed) // TODO: implement domains other than LIA
    if (testOutput.isDefined) {
      if (output == testOutput.get) 0 else 1
    }
    else {
      val (dec, _) = checkOnInputAndKnownOutput(s, testInputsMap, output)
      if (dec == "sat")
        testsManager.updateTest((testInputsMap, Some(output)))
      if (dec == "sat") 0 else 1
    }
  }



  ///////  Interactions with the solver  ///////


  def checkOnInputAndKnownOutput(s: Op,
                                 testInputsMap: Map[String, Any],
                                 output: Any): (String, Option[String]) = {
    val query = SMTLIBFormatter.checkOnInputAndKnownOutput(sygusProblem,
      testInputsMap, output, timeout)
    // println("\nQuery checkOnInputAndKnownOutput:\n" + query)
    solver.runSolver(query)
  }

  def checkOnInputOnly(s: Op,
                       testInputsMap: Map[String, Any]): (String, Option[String]) = {
    val query = SMTLIBFormatter.checkOnInput(sygusProblem, testInputsMap, s, timeout)
    // println("\nQuery checkOnInputOnly:\n" + query)
    solver.runSolver(query)
  }

  def verify(s: Op): (String, Option[String]) = {
    val query = SMTLIBFormatter.verify(sygusProblem, s, timeout)
    // println("\nQuery verify:\n" + query)
    val getValueCommand = f"(get-value (${varDecls.map(_.sym).mkString(" ")}))"
    solver.runSolver(query, getValueCommand)
  }

  def findOutputForTestCase(test: (I, Option[O])): (I, Option[O]) = {
    val query = SMTLIBFormatter.findOutputForTestCase(sygusProblem, test._1, solverTimeout=timeout)
    // println("\nQuery findOutputForTestCase:\n" + query)
    try {
      val getValueCommand = f"(get-value (CorrectOutput))"
      val (dec, res) = solver.runSolver(query, getValueCommand)
      if (dec == "sat") {
        val values = GetValueParser(res.get)
        (test._1, Some(values.head._2))
      }
      else
        test
    }
    catch {
      case _: Throwable =>
        println(s"Exception during executing query or parsing result, returning test with no output! ")
        test // in case solver returns unknown
    }
  }

  ///////////////////////////////////////////////////////




  def createTestFromFailedVerification(verOutput: String): (Map[String, Any], Option[Any]) = {
    val counterExample = GetValueParser(verOutput) // returns the counterexample
    val testNoOutput = (counterExample.toMap, None) // for this test currently the correct answer is not known
    if (useDomainToComputeFitness)
      findOutputForTestCase(testNoOutput)
    else
      testNoOutput
  }

  def createRandomTest(verOutput: String): (Map[String, Any], Option[Any]) = {
    // The only reason to call the parser here is to get the right argument names:
    val argNames = GetValueParser(verOutput).unzip._1
    val example = argNames.map(argName => (argName, GPRminInt + rng.nextInt(GPRmaxInt+1-GPRminInt)))
    val testNoOutput = (example.toMap, None) // for this test currently the correct answer is not known
    findOutputForTestCase(testNoOutput)
  }

  /**
    * Creates CDGPEvaluation based on options.
    */
  def getCDGPEvaluation[S, E](eval: S => E): CDGPEvaluation[S, E] =
    new CDGPEvaluation(this, eval)
  /**
    * Creates CDGPEvaluationSteadyState based on options.
    */
  def getCDGPEvaluationSteadyState[S, E](eval: S => E,
                                         updateEval: ((S, E)) => (S, E)): CDGPEvaluation[S, E] =
    new CDGPEvaluationSteadyState(this, eval, updateEval)


  val fitness: (Op) => (Boolean, Seq[Int]) =
    method match {
      case "CDGP"     => fitnessCDGPGeneral
      case "GPR"      => fitnessGPR
    }

  def fitnessCDGPGeneral: Op => (Boolean, Seq[Int]) =
    new Function1[Op, (Boolean, Seq[Int])] {
      def doVerify(evalTests: Seq[Int]): Boolean = {
        val numPassed = evalTests.count(_ == 0).asInstanceOf[Double]
        (numPassed / evalTests.size) >= CDGPtestsRatio || evalTests.isEmpty
      }
      def apply(s: Op) = {
        val evalTests = evalOnTests(s, testsManager.getTests())
        // If the program passes the specified ratio of test cases, it will be verified
        // and a counterexample will be produced (or program will be deemed correct).
        // NOTE: if the program does not pass all test cases, then the probability is high
        // that produced counterexample will already be in the set of test cases.
        if (!doVerify(evalTests))
          (false, evalTests)
        else {
          val (decision, r) = verify(s)
          if (decision == "unsat") (true, evalTests) // perfect program found; end of run
          else {
            if (!CDGPoneTestPerIter || testsManager.newTests.isEmpty) {
              val newTest = createTestFromFailedVerification(r.get)
              testsManager.addNewTest(newTest)
            }
            (false, evalTests)
          }
        }
      }
    }

  def fitnessGPR: Op => (Boolean, Seq[Int]) = {
    new Function1[Op, (Boolean, Seq[Int])] {
      def doVerify(evalTests: Seq[Int]): Boolean = {
        val numPassed = evalTests.count(_ == 0).asInstanceOf[Double]
        (numPassed / evalTests.size) >= GPRtestsRatio || evalTests.isEmpty
      }
      def apply(s: Op) = {
        val evalTests = evalOnTests(s, testsManager.getTests())
        if (!doVerify(evalTests))
          (false, evalTests)
        else {
          val (decision, r) = verify(s)
          if (decision == "unsat") (true, evalTests) // perfect program found; end of run
          else {
            if (!GPRoneTestPerIter || testsManager.newTests.isEmpty) {
              val newTest = createRandomTest(r.get)
              testsManager.addNewTest(newTest)
            }
            (false, evalTests)
          }
        }
      }
    }
  }

  def updateEvalInt(s: (Op, FInt)): (Op, FInt) = {
    val newFit = FInt(s._2.correct, s._2.value + evalOnTests(s._1, testsManager.newTests.toList).sum)
    (s._1, newFit)
  }
  def updateEvalSeqInt(s: (Op, FSeqInt)): (Op, FSeqInt) =
    (s._1, FSeqInt(s._2.correct, s._2.value ++ evalOnTests(s._1, testsManager.newTests.toList)))
}


object CDGPState {
  def apply(benchmark: String)
           (implicit opt: Options, coll: Collector, rng: TRandom): CDGPState =
    new CDGPState(LoadSygusBenchmark(benchmark))

  def apply(sygusProblem: SyGuS16)
           (implicit opt: Options, coll: Collector, rng: TRandom): CDGPState =
    new CDGPState(sygusProblem)
}
