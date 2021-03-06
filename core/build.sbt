name := "Core"

mainClass in Compile := Some("slamdata.engine.repl.Repl")

fork in run := true

connectInput in run := true

outputStrategy := Some(StdoutOutput)

import ScoverageSbtPlugin._

ScoverageKeys.coverageExcludedPackages := "slamdata.engine.repl;.*RenderTree"

ScoverageKeys.coverageMinimum := 78

ScoverageKeys.coverageFailOnMinimum := true

ScoverageKeys.coverageHighlighting := true

wartremoverErrors in (Compile, compile) ++= Warts.allBut(
  // NB: violation counts are from running `compile`
  Wart.Any,               // 113
  Wart.AsInstanceOf,      //  75
  Wart.IsInstanceOf,      //  79
  Wart.NoNeedForMonad,    //  62
  Wart.Nothing,           // 366
  Wart.Product,           // 180  _ these two are highly correlated
  Wart.Serializable,      // 182  /
  Wart.Throw)             // 412
