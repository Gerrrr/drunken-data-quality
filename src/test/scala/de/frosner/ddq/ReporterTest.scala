package de.frosner.ddq

import java.io.{PrintStream, ByteArrayOutputStream}

import de.frosner.ddq.reporters.{MarkdownReporter, ConsoleReporter}
import org.scalatest.Matchers

class ReporterTest extends TestDataFrameContext with Matchers {
  "ConsoleReporter" should "produce correct output" in {
    val baos = new ByteArrayOutputStream()
    val consoleReporter = new ConsoleReporter(new PrintStream(baos))

    Check(makeIntegerDf(List(1,2,3))).hasNumRowsEqualTo(3).hasNumRowsEqualTo(2).satisfies("column > 0").
      run(List(consoleReporter))

    val expectedOutput = s"""${Console.BLUE}Checking [column: int]${Console.RESET}
${Console.BLUE}It has a total number of 1 columns and 3 rows.${Console.RESET}
${Console.GREEN}- The number of rows is equal to 3${Console.RESET}
${Console.RED}- The actual number of rows 3 is not equal to the expected 2${Console.RESET}
${Console.GREEN}- Constraint column > 0 is satisfied${Console.RESET}
"""

    baos.toString shouldBe expectedOutput
  }

  "MarkdownReporter" should "produce correct output" in {
    val baos = new ByteArrayOutputStream()
    val markdownReporter = new MarkdownReporter(new PrintStream(baos))

    Check(makeIntegerDf(List(1,2,3))).hasNumRowsEqualTo(3).hasNumRowsEqualTo(2).satisfies("column > 0").
      run(List(markdownReporter))

    val expectedOutput = """# Checking [column: int]

It has a total number of 1 columns and 3 rows.

* [success]: The number of rows is equal to 3
* [failure]: The actual number of rows 3 is not equal to the expected 2
* [success]: Constraint column > 0 is satisfied
"""

    baos.toString shouldBe expectedOutput
  }
}