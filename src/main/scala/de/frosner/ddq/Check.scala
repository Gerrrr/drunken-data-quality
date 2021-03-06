package de.frosner.ddq

import java.text.SimpleDateFormat
import java.util.regex.Pattern

import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.SQLContext
import de.frosner.ddq.Check._
import de.frosner.ddq.Constraint.ConstraintFunction
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.storage.StorageLevel

import scala.util.Try

/**
 * A class representing a list of constraints that can be applied to a given [[org.apache.spark.sql.DataFrame]].
 * In order to run the checks, use the `run` method.
 *
 * @param dataFrame The table to check
 * @param displayName The name to show in the logs. If it is not set, `toString` will be used.
 * @param cacheMethod The [[org.apache.spark.storage.StorageLevel]] to persist with before executing the checks.
 *                    If it is not set, no persisting will be attempted
 * @param constraints The constraints to apply when this check is run. New ones can be added and will return a new object
 */
case class Check(dataFrame: DataFrame,
                 displayName: Option[String] = Option.empty,
                 cacheMethod: Option[StorageLevel] = DEFAULT_CACHE_METHOD,
                 constraints: Iterable[Constraint] = Iterable.empty) {
  
  private def addConstraint(cf: ConstraintFunction): Check =
    Check(dataFrame, displayName, cacheMethod, constraints ++ List(Constraint(cf)))

  /**
   * Check whether the given columns are a unique key for this table.
   *
   * @param columnName name of the first column that is supposed to be part of the unique key
   * @param columnNames names of the other columns that are supposed to be part of the unique key
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def hasUniqueKey(columnName: String, columnNames: String*): Check = addConstraint {
    df => {
      val columnsString = (columnName :: columnNames.toList).mkString(",")
      val nonUniqueRows = df.groupBy(columnName, columnNames:_*).count.filter(new Column("count") > 1).count
      if (nonUniqueRows == 0)
        success(s"""Columns $columnsString are a key""")
      else
        failure(s"""Columns $columnsString are not a key""")
    }
  }

  private def satisfies(succeedingRowsFunction: DataFrame => DataFrame, constraintString: String): Check = addConstraint {
    df => {
      val succeedingRows = succeedingRowsFunction(df).count
      val count = df.count
      if (succeedingRows == count)
        success(s"Constraint $constraintString is satisfied")
      else
        failure(s"${count - succeedingRows} rows did not satisfy constraint $constraintString")
    }
  }

  /**
   * Check whether the given constraint is satisfied. The constraint has to comply with Spark SQL syntax. So you
   * can just write it the same way that you would put it inside a `WHERE` clause.
   *
   * @param constraint The constraint that needs to be satisfied for all columns
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def satisfies(constraint: String): Check = satisfies(
    (df: DataFrame) => df.filter(constraint),
    constraint
  )

  /**
    * Check whether the given constraint is satisfied. The constraint is built using the
    * [[org.apache.spark.sql.Column]] class.
    *
    * @param constraint The constraint that needs to be satisfied for all columns
    * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def satisfies(constraint: Column): Check = satisfies(
    (df: DataFrame) => df.filter(constraint),
    constraint.toString()
  )

  /**
   * <p>Check whether the given conditional constraint is satisfied. The constraint is built using the
   * [[org.apache.spark.sql.Column]] class.</p><br/>
   * Usage:
   * {{{
   * Check(df).satisfies((new Column("c1") === 1) -> (new Column("c2").isNotNull))
   * }}}
   *
   * @param conditional The constraint that needs to be satisfied for all columns
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def satisfies(conditional: (Column, Column)): Check = {
    val (statement, implication) = conditional
    satisfies(
      (df: DataFrame) => df.filter(!statement || implication),
      s"$statement -> $implication"
    )
  }

  /**
   * Check whether the column with the given name contains no null values.
   *
   * @param columnName Name of the column to check
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isNeverNull(columnName: String) = addConstraint {
    df => {
      val nullCount = df.filter(new Column(columnName).isNull).count
      if (nullCount == 0)
        success(s"Column $columnName is not null")
      else
        failure(s"Column $columnName has $nullCount null rows although it should not be null")
    }
  }

  /**
   * Check whether the column with the given name contains only null values.
   *
   * @param columnName Name of the column to check
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isAlwaysNull(columnName: String) = addConstraint {
    df => {
      val notNullCount = df.filter(new Column(columnName).isNotNull).count
      if (notNullCount == 0)
        success(s"Column $columnName is null")
      else
        failure(s"Column $columnName has $notNullCount non-null rows although it should be null")
    }
  }

  /**
   * Check whether the table has exactly the given number of rows.
   *
   * @param expected Expected number of rows.
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def hasNumRowsEqualTo(expected: Long): Check = addConstraint {
    df => {
      val count = df.count
      if (count == expected)
        success(s"The number of rows is equal to $count")
      else
        failure(s"The actual number of rows $count is not equal to the expected $expected")
    }
  }

  private val cannotBeInt = udf((column: String) => column != null && Try(column.toInt).isFailure)
  /**
   * Check whether the column with the given name can be converted to an integer.
   *
   * @param columnName Name of the column to check
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isConvertibleToInt(columnName: String) = addConstraint {
    df => {
      val cannotBeIntCount = df.filter(cannotBeInt(new Column(columnName))).count
      if (cannotBeIntCount == 0)
        success(s"Column $columnName can be converted to Int")
      else
        failure(s"Column $columnName contains $cannotBeIntCount rows that cannot be converted to Int")
    }
  }

  private val cannotBeDouble = udf((column: String) => column != null && Try(column.toDouble).isFailure)
  /**
   * Check whether the column with the given name can be converted to a double.
   *
   * @param columnName Name of the column to check
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isConvertibleToDouble(columnName: String) = addConstraint {
    df => {
      val cannotBeDoubleCount = df.filter(cannotBeDouble(new Column(columnName))).count
      if (cannotBeDoubleCount == 0)
        success(s"Column $columnName can be converted to Double")
      else
        failure(s"Column $columnName contains $cannotBeDoubleCount rows that cannot be converted to Double")
    }
  }

  private val cannotBeLong = udf((column: String) => column != null && Try(column.toLong).isFailure)
  /**
   * Check whether the column with the given name can be converted to a long.
   *
   * @param columnName Name of the column to check
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isConvertibleToLong(columnName: String) = addConstraint {
    df => {
      val cannotBeLongCount = df.filter(cannotBeLong(new Column(columnName))).count
      if (cannotBeLongCount == 0)
        success(s"Column $columnName can be converted to Long")
      else
        failure(s"Column $columnName contains $cannotBeLongCount rows that cannot be converted to Long")
    }
  }

  /**
   * Check whether the column with the given name can be converted to a date using the specified date format.
   *
   * @param columnName Name of the column to check
   * @param dateFormat Date format to use for conversion
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isConvertibleToDate(columnName: String, dateFormat: SimpleDateFormat) = addConstraint {
    df => {
      val cannotBeDate = udf((column: String) => column != null && Try(dateFormat.parse(column)).isFailure)
      val cannotBeDateCount = df.filter(cannotBeDate(new Column(columnName))).count
      if (cannotBeDateCount == 0)
        success(s"Column $columnName can be converted to Date")
      else
        failure(s"Column $columnName contains $cannotBeDateCount rows that cannot be converted to Date")
    }
  }

  /**
   * Check whether the column with the given name is always any of the specified values.
   *
   * @param columnName Name of the column to check
   * @param allowed Set of allowed values
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isAnyOf(columnName: String, allowed: Set[Any]) = addConstraint {
    df => {
      df.select(new Column(columnName)) // check if reference is not ambiguous
      val columnIndex = df.columns.indexOf(columnName)
      val notAllowedCount = df.rdd.filter(row => !row.isNullAt(columnIndex) && !allowed.contains(row.get(columnIndex))).count
      if (notAllowedCount == 0)
        success(s"Column $columnName contains only values in $allowed")
      else
        failure(s"Column $columnName contains $notAllowedCount rows that are not in $allowed")
    }
  }

  /**
   * Check whether the column with the given name is always matching the specified regular expression.
   *
   * @param columnName Name of the column to check
   * @param regex Regular expression that needs to match
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isMatchingRegex(columnName: String, regex: String) = addConstraint {
    df => {
      val pattern = Pattern.compile(regex)
      val doesNotMatch = udf((column: String) => column != null && !pattern.matcher(column).find())
      val doesNotMatchCount = df.filter(doesNotMatch(new Column(columnName))).count
      if (doesNotMatchCount == 0)
        success(s"Column $columnName matches $regex")
      else
        failure(s"Column $columnName contains $doesNotMatchCount rows that do not match $regex")
    }
  }

  /**
   * Check whether the column with the given name can be converted to a boolean. You can specify the textual values
   * to use for true and false.
   *
   * @param columnName Name of the column to check
   * @param trueValue String value to treat as true
   * @param falseValue String value to treat as false
   * @param isCaseSensitive Whether parsing should be case sensitive
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isConvertibleToBoolean(columnName: String, trueValue: String = "true", falseValue: String = "false",
                             isCaseSensitive: Boolean = false) = addConstraint {
    df => {
      val cannotBeBoolean =
        if (isCaseSensitive)
          udf((column: String) => column != null
            && column != trueValue
            && column != falseValue)
        else
          udf((column: String) => column != null
            && column.toUpperCase != trueValue.toUpperCase
            && column.toUpperCase != falseValue.toUpperCase)
      val cannotBeBooleanCount = df.filter(cannotBeBoolean(new Column(columnName))).count
      if (cannotBeBooleanCount == 0)
        success(s"Column $columnName can be converted to Boolean")
      else
        failure(s"Column $columnName contains $cannotBeBooleanCount rows that cannot be converted to Boolean")
    }
  }

  /**
   * Check whether the columns with the given names define a foreign key to the specified reference table.
   *
   * @param referenceTable Table to which the foreign key is pointing
   * @param keyMap Column mapping from this table to the reference one (`"column1" -> "base_column1"`)
   * @param keyMaps Column mappings from this table to the reference one (`"column1" -> "base_column1"`)
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def hasForeignKey(referenceTable: DataFrame, keyMap: (String, String), keyMaps: (String, String)*) = addConstraint {
    df => {
      val columns = keyMap :: keyMaps.toList
      val renamedColumns = columns.map{ case (baseColumn, refColumn) => ("b_" + baseColumn, "r_" + refColumn)}
      val (baseColumns, refColumns) = columns.unzip
      val (renamedBaseColumns, renamedRefColumns) = renamedColumns.unzip

      // check if foreign key is a key in reference table
      val nonUniqueRows = referenceTable.groupBy(refColumns.map(new Column(_)):_*).count.filter(new Column("count") > 1).count
      if (nonUniqueRows > 0) {
        failure( s"""Columns ${refColumns.mkString(", ")} are not a key in reference table""")
      } else {
        // rename all columns to avoid ambiguous column references
        val renamedDf = df.select(baseColumns.zip(renamedBaseColumns).map {
          case (original, renamed) => new Column(original).as(renamed)
        }:_*)
        val renamedRef = referenceTable.select(refColumns.zip(renamedRefColumns).map {
          case (original, renamed) => new Column(original).as(renamed)
        }:_*)

        // check if left outer join yields some null values
        val leftOuterJoin = renamedDf.distinct.join(renamedRef, renamedColumns.map{
          case (baseColumn, refColumn) => new Column(baseColumn) === new Column(refColumn)
        }.reduce(_ && _), "outer")
        val notMatchingRefs = leftOuterJoin.filter(renamedRefColumns.map(new Column(_).isNull).reduce(_ && _)).count
        val columnsString = columns.map{ case (baseCol, refCol) => baseCol + "->" + refCol }.mkString(", ")
        if (notMatchingRefs == 0)
          success(s"""Columns $columnsString define a foreign key""")
        else
          failure(s"Columns $columnsString do not define a foreign key ($notMatchingRefs records do not match)")
      }
    }
  }

  /**
   * Check whether a join between this table and the given reference table returns any results. This can be seen
   * as a weaker version of the foreign key check, as it requires only partial matches.
   *
   * @param referenceTable Table to join with
   * @param keyMap Column mapping from this table to the reference one (`"column1" -> "base_column1"`)
   * @param keyMaps Column mappings from this table to the reference one (`"column1" -> "base_column1"`)
   * @return [[de.frosner.ddq.Check]] object including this constraint
   */
  def isJoinableWith(referenceTable: DataFrame, keyMap: (String, String), keyMaps: (String, String)*) = addConstraint {
    df => {
      val columns = keyMap :: keyMaps.toList
      val columnsMap = columns.toMap
      val renamedColumns = columns.map{ case (baseColumn, refColumn) => ("b_" + baseColumn, "r_" + refColumn)}
      val (baseColumns, refColumns) = columns.unzip
      val (renamedBaseColumns, renamedRefColumns) = renamedColumns.unzip

      val nonUniqueRows = referenceTable.groupBy(refColumns.map(new Column(_)):_*).count.filter(new Column("count") > 1).count

    // rename all columns to avoid ambiguous column references
    val renamedDf = df.select(baseColumns.zip(renamedBaseColumns).map {
      case (original, renamed) => new Column(original).as(renamed)
    }:_*)
    val renamedRef = referenceTable.select(refColumns.zip(renamedRefColumns).map {
      case (original, renamed) => new Column(original).as(renamed)
    }:_*)

    // check if join yields some values
    val join = renamedDf.distinct.join(renamedRef, renamedColumns.map{
      case (baseColumn, refColumn) => new Column(baseColumn) === new Column(refColumn)
    }.reduce(_ && _))
    val matchingRows = join.count
    val columnsString = columns.map{ case (baseCol, refCol) => baseCol + "->" + refCol }.mkString(", ")
    if (matchingRows > 0)
      success(s"""Columns $columnsString can be used for joining ($matchingRows distinct rows match)""")
    else
      failure(s"Columns $columnsString cannot be used for joining (no rows match)")
    }
  }


  /**
   * Run all the previously specified constraints.
   *
   * @return whether all constraints are satisfied
   */
  def run: Boolean = {
    hint(s"Checking ${displayName.getOrElse(dataFrame.toString)}")
    val potentiallyPersistedDf = cacheMethod.map(dataFrame.persist(_)).getOrElse(dataFrame)
    hint(s"It has a total number of ${potentiallyPersistedDf.columns.size} columns and ${potentiallyPersistedDf.count} rows.")
    val result = if (!constraints.isEmpty)
      constraints.map(c => c.fun(potentiallyPersistedDf)).reduce(_ && _)
    else
      hint("- Nothing to check!")
    if (cacheMethod.isDefined) potentiallyPersistedDf.unpersist()
    result
  }
      
}

object Check {

  private val DEFAULT_CACHE_METHOD = Option(StorageLevel.MEMORY_ONLY)

  /**
   * Construct a check object using the given [[org.apache.spark.sql.SQLContext]] and table name.
   *
   * @param sql SQL context to read the table from
   * @param table Name of the table to check
   * @param cacheMethod The [[org.apache.spark.storage.StorageLevel]] to persist with before executing the checks.
   *                    If it is not set, no persisting will be attempted
   * @return Check object that can be applied on the given table
   */
  def sqlTable(sql: SQLContext,
               table: String,
               cacheMethod: Option[StorageLevel] = DEFAULT_CACHE_METHOD): Check = {
    val tryTable = Try(sql.table(table))
    require(tryTable.isSuccess, s"""Failed to reference table $table: ${tryTable.failed.getOrElse("No exception provided")}""")
    Check(
      dataFrame = tryTable.get,
      displayName = Option(table),
      cacheMethod = cacheMethod
    )
  }

  /**
   * Construct a check object using the given [[org.apache.spark.sql.SQLContext]] and table name.
   *
   * @param hive Hive context to read the table from
   * @param database Database to switch to before attempting to read the table
   * @param table Name of the table to check
   * @param cacheMethod The [[org.apache.spark.storage.StorageLevel]] to persist with before executing the checks.
   *                    If it is not set, no persisting will be attempted
   * @return Check object that can be applied on the given table
   */
  def hiveTable(hive: HiveContext,
                database: String,
                table: String,
                cacheMethod: Option[StorageLevel] = DEFAULT_CACHE_METHOD): Check = {
    hive.sql(s"USE $database")
    sqlTable(hive, table, cacheMethod)
  }

  private def success(message: String): Boolean = {
    println(Console.GREEN + "- " + message + Console.RESET)
    true
  }

  private def failure(message: String): Boolean = {
    println(Console.RED + "- " + message + Console.RESET)
    false
  }

  private def hint(message: String): Boolean = {
    println(Console.BLUE + message + Console.RESET)
    true
  }

}
