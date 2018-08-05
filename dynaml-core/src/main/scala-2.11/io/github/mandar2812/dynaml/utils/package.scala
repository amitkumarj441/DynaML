/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
* */
package io.github.mandar2812.dynaml

import java.io.{BufferedWriter, File, FileWriter}

import breeze.linalg.{DenseMatrix, DenseVector, Matrix, MatrixNotSquareException, MatrixNotSymmetricException, kron}
import com.github.tototoshi.csv.{CSVReader, DefaultCSVFormat, QUOTE_NONNUMERIC}
import org.renjin.script.{RenjinScriptEngine, RenjinScriptEngineFactory}
import org.renjin.sexp.SEXP
import spire.algebra.InnerProductSpace

import scala.io.Source
import scala.reflect.runtime.{universe => ru}
import scala.annotation.tailrec
import scala.util.matching.Regex
import sys.process._
import java.net.URL

import breeze.stats.distributions.ContinuousDistr
import io.github.mandar2812.dynaml.algebra.PartitionedMatrix

import scalaxy.streams.optimize
import spire.algebra.{Eq, Field}

import scala.util.Random

/**
  * A set of pre-processing utilities
  * and library functions.
  * */
package object utils {

  val log1pExp: (Double) => Double = (x) => {x + math.log1p(math.exp(-x))}

  /**
    * Get a [[CSVReader]] object from a file name and delimiter
    * character.
    *
    * @param file The file pathname as a String
    * @param delim The delimiter character used
    *              to split the csv file.
    * @return A [[CSVReader]] object which can be
    *         iterated for each line.
    * */
  def getCSVReader(file: String, delim: Char): CSVReader = {
    implicit object MyFormat extends DefaultCSVFormat {
      override val delimiter = delim
      override val quoting = QUOTE_NONNUMERIC
    }
    CSVReader.open(new File(file))
  }

  /**
    * Extract the diagonal elements of a breeze [[DenseMatrix]]
    * */
  def diagonal(m: DenseMatrix[Double]): DenseMatrix[Double] = {
    require(m.rows == m.cols, "Matrix must be square to extract diagonal")
    m.mapPairs((index, value) => if(index._1 == index._2) value else 0.0)
  }

  /**
    * Extract the diagonal elements of a [[PartitionedMatrix]]
    * */
  def diagonal[M <: PartitionedMatrix](pm: M): PartitionedMatrix = {
    require(pm.rows == pm.cols, "Blocked matrix must be square to extract diagonal")
    pm.map(pairs => {
      if(pairs._1._1 == pairs._1._2) (pairs._1, diagonal(pairs._2))
      else (pairs._1, DenseMatrix.zeros[Double](pairs._2.rows, pairs._2.cols))
    })
  }

  /**
    * Get the mean and variance of a data set
    * which is a [[List]] of [[DenseVector]].
    *
    * @param data The data set.
    * @return A [[Tuple2]] containing the mean
    *         and variance * n-1.
    *
    * */
  def getStats(data: List[DenseVector[Double]]):
  (DenseVector[Double], DenseVector[Double]) = {
    @tailrec
    def getStatsRec(d: List[DenseVector[Double]],
                    m: DenseVector[Double],
                    s: DenseVector[Double],
                    i: Int):
    (DenseVector[Double], DenseVector[Double]) = d match {
      case Nil => (m, s)
      case x :: rest =>
        val mnew = m + (x - m)/(i+1).toDouble
        getStatsRec(rest, mnew,
          s + (m*:*m) - (mnew*:*mnew) + ((x*:*x) - s - (m*:*m))/(i+1).toDouble,
          i + 1)

    }


    val n = data.length

    require(n > 1, "To calculate stats size of data must be > 1")

    val adjustment = n.toDouble/(n-1)
    val (mean, biasedSigmaSq) = getStatsRec(
      data.tail, data.head,
      DenseVector.zeros[Double](data.head.length),
      1)

    (mean, biasedSigmaSq*adjustment)
  }

  /**
    * Get the mean and variance of a data set
    * which is a [[List]] of [[DenseVector]].
    *
    * @param data The data set.
    * @return A [[Tuple2]] containing the mean
    *         and variance.
    *
    * */
  def getStatsMult(data: List[DenseVector[Double]]):
  (DenseVector[Double], DenseMatrix[Double]) = {
    def getStatsRec(d: List[DenseVector[Double]],
                    m: DenseVector[Double],
                    s: DenseMatrix[Double],
                    i: Int):
    (DenseVector[Double], DenseMatrix[Double]) = d match {
      case Nil =>
        (m,s)
      case x :: rest =>
        val mnew = m + (x - m)/(i+1).toDouble
        getStatsRec(rest, mnew,
          s + (m*m.t) - (mnew*mnew.t) + ((x*x.t) - s - (m*m.t))/(i+1).toDouble,
          i + 1)

    }


    val n = data.length

    require(n > 1, "To calculate stats size of data must be > 1")

    val adjustment = n.toDouble/(n-1)
    val (mean, biasedSigmaSq) = getStatsRec(
      data.tail, data.head,
      data.head * data.head.t,
      1)

    (mean, biasedSigmaSq*adjustment)
  }

  def getMinMax(data: List[DenseVector[Double]]):
  (DenseVector[Double], DenseVector[Double]) = {
    @tailrec
    def getMinMaxRec(d: List[DenseVector[Double]],
                     m: DenseVector[Double],
                     s: DenseVector[Double],
                     i: Int):
    (DenseVector[Double], DenseVector[Double]) = d match {
      case Nil => (m, s)
      case x :: rest =>
        getMinMaxRec(rest,
          DenseVector((x.toArray zip m.toArray).map(c => math.min(c._1, c._2))),
          DenseVector((x.toArray zip s.toArray).map(c => math.max(c._1, c._2))),
          i - 1)

    }

    getMinMaxRec(
      data.tail,
      data.head,
      data.head,
      data.length)
  }


  /**
    * Implementation of the quick-select algorithm.
    * */
  def quickselect(list: Stream[Double], k: Int): Double = {

    require(k <= list.length && k > 0, "In quick-select, the search index must be between 1 and length of list")
    val random: (Int) => Int = Random.nextInt

    def quickSelectRec(list_sample: Seq[Double], k: Int, pivot: Double): Double = {
      val split_list = list_sample.partition(_ < pivot)
      val s = split_list._1.length

      if(s == k) {
        pivot
      } else if (s == 0 && list_sample.sum == pivot * list_sample.length) {
        pivot
      } else if(s < k) {
        quickSelectRec(split_list._2, k - s,
          split_list._2(random(split_list._2.length)))
      } else {
        quickSelectRec(split_list._1, k,
          split_list._1(random(split_list._1.length)))
      }
    }

    val arrayStream = list.toArray
    quickSelectRec(arrayStream, k-1, arrayStream(Random.nextInt(arrayStream.length)))
  }

  def median(list: Stream[Double]): Double = {
    val random: (Int) => Int = Random.nextInt

    def medianK(list_sample: Seq[Double], k: Int, pivot: Double): Double = {
      val split_list = list_sample.partition(_ < pivot)
      val s = split_list._1.length

      if(s == k) {
        pivot
      } else if (s == 0 && list_sample.sum == pivot * list_sample.length) {
        pivot
      } else if(s < k) {
        medianK(split_list._2, k - s,
          split_list._2(random(split_list._2.length)))
      } else {
        medianK(split_list._1, k,
          split_list._1(random(split_list._1.length)))
      }
    }

    if(list.length % 2 == 0) {
      val medA = medianK(list, list.length/2, list(random(list.length)))
      val medB = medianK(list, list.length/2 - 1, list(random(list.length)))
      (medA + medB)/2.0
    } else {
      medianK(list, list.length/2, list(random(list.length)))
    }
  }

  /**
    * Convert a hyper-prior specification to a continuous distribution
    * over [[Map]]
    * */
  def getPriorMapDistr(d: Map[String, ContinuousDistr[Double]]) = {


    new ContinuousDistr[Map[String, Double]] {

      override def unnormalizedLogPdf(x: Map[String, Double]) = {

        x.map(c => d(c._1).unnormalizedLogPdf(c._2)).sum
      }

      override def logNormalizer = d.values.map(_.logNormalizer).sum

      override def draw() = d.mapValues(_.draw())
    }

  }


  /**
    * Calculates the Chebyshev polynomials of the first and second kind,
    * in a tail recursive manner, using their recurrence relations.
    * */
  def chebyshev(n: Int, x: Double, kind: Int = 1): Double = {
    require(
      kind >= 1 && kind <= 2,
      "Chebyshev function can only be of the first or second kind")

    def chebyshev_T(k: Int, arg: Double, a: Double, b: Double): Double =
      k match {
        case 0 => a
        case 1 => b
        case _ => chebyshev_T(k-1, arg, b, 2*arg*b - a)
      }

    val c1 = if(kind == 1) x else 2*x

    chebyshev_T(n, x, 1, c1)
  }

  /**
    * Calculate the value of the hermite polynomials
    * tail recursively. This is needed to calculate
    * the Gaussian derivatives at a point x.
    * */
  def hermite(n: Int, x: Double): Double = {
    @tailrec
    def hermiteHelper(k: Int, x: Double, a: Double, b: Double): Double =
      k match {
        case 0 => a
        case 1 => b
        case _ => hermiteHelper(k-1, x, b, x*b - (k-1)*a)
      }
    hermiteHelper(n, x, 1, x)
  }

  /**
    * Calculate the value of the Legendre polynomials
    * tail recursively.
    * */
  def legendre(n: Int, x: Double): Double = {
    @tailrec
    def legendreHelper(k: Int, x: Double, a: Double, b: Double): Double =
      k match {
        case 0 => a
        case 1 => b
        case _ => legendreHelper(k-1, x, b, ((2*k - 1)*x*b - (k-1)*a)/k)
      }

    legendreHelper(n, x, 1, x)
  }


  /**
    * Calculates the Harmonic number function
    * for positive real arguments.
    * */
  def H(x: Double): Double = {
    assert(x >= 0, "Harmonic number function in DynaML takes only non-negative arguments")
    def hRec(arg: Double, acc: Double): Double = math.floor(arg) match {
      case 0 => acc
      case n => hRec(arg-1, acc + (1d/n))
    }
    hRec(x, 0d)
  }

  @tailrec
  def factorial(n: Int, accumulator: Long = 1): Long = {
    if(n == 0) accumulator else factorial(n - 1, accumulator*n)
  }

  def getTypeTag[T: ru.TypeTag](obj: T) = ru.typeTag[T]

  def combine[A](xs: Traversable[Traversable[A]]): Seq[Seq[A]] =
    xs.foldLeft(Seq(Seq.empty[A])) {
      (x, y) => optimize {
        for (a <- x.view; b <- y) yield a :+ b
      }
    }

  def range[I](min: I, max: I, steps: Int)(implicit field: InnerProductSpace[I, Double]): Stream[I] = {
    val step_size = field.divr(field.minus(max, min), steps)
    (0 until steps).toStream.map(i => field.plus(min, field.timesr(step_size, i)))
  }

  def downloadURL(url: String, saveAs: String): Unit =
    new URL(url) #> new File(saveAs) !!

  def replace(find: String)(replace: String)(input: String): String = {
    val pattern = new Regex(find)
    pattern.replaceAllIn(input, replace)
  }

  def textFileToStream(fileName: String): Stream[String] =
    Source.fromFile(new File(fileName)).getLines().toStream

  def strReplace(fileName: String)(
    findStringRegex: String,
    replaceString: String): Stream[String] = optimize {

    textFileToStream(fileName).map(
      replace(findStringRegex)(replaceString))
  }

  def writeToFile(destination: String)(lines: Stream[String]): Unit = {
    val writer = new BufferedWriter(new FileWriter(new File(destination)))
    lines.foreach(line => {
      writer.write(line+"\n")
    })
    writer.close()
  }

  def transformData(transform: (String) => String)(lines: Stream[String]): Stream[String] =
    optimize { lines.map(transform) }

  def extractColumns(
    lines: Stream[String], sep: String,
    columns: List[Int], naStrings:Map[Int, String]): Stream[String] = {

    val tFunc = (line: String) => {
      val fields = line.split(sep)

      optimize {
        val newFields:List[String] = columns.map(col => {
          if (!naStrings.contains(col) || fields(col) != naStrings(col)) fields(col)
          else "<NA>"
        })

        val newLine = newFields.foldLeft("")(
          (str1, str2) => str1+sep+str2
        )

        newLine.tail
      }
    }

    transformData(tFunc)(lines)
  }

  /**
    * Construct a Haar transform matrix of size n
    *
    * NOTE: n must be a power of 2.
    *
    * */
  def haarMatrix(n: Int) = {

    val pos = DenseMatrix(Array(1.0, 1.0))
    val neg = DenseMatrix(Array(-1.0, 1.0))
    val hMat = DenseMatrix(Array(1.0, 1.0), Array(-1.0, 1.0))

    def haarMatrixAcc(i: Int, hMatAcc: DenseMatrix[Double]): DenseMatrix[Double] = i match {
      case `n` => hMatAcc
      case index =>
        haarMatrixAcc(i*2,
          DenseMatrix.vertcat[Double](
            kron(hMatAcc, pos),
            kron(DenseMatrix.eye[Double](i), neg)))
    }

    haarMatrixAcc(2, hMat)
  }

  def productField[Domain, Domain1](ev: Field[Domain], ev1: Field[Domain1])(
    implicit eqq: Eq[Domain], eqq1: Eq[Domain1]): Field[(Domain, Domain1)] =
    new Field[(Domain, Domain1)] {
      /*override def gcd(a: (Domain, Domain1), b: (Domain, Domain1)): (Domain, Domain1) =
        (ev.gcd(a._1, b._1), ev1.gcd(a._2, b._2))*/

      override def gcd(a: (Domain, Domain1), b: (Domain, Domain1))(implicit eqq3: Eq[(Domain, Domain1)]) =
        (ev.gcd(a._1, b._1), ev1.gcd(a._2, b._2))

      override def lcm(a: (Domain, Domain1), b: (Domain, Domain1))(implicit eqq3: Eq[(Domain, Domain1)]) =
        (ev.lcm(a._1, b._1), ev1.lcm(a._2, b._2))

      override def quot(a: (Domain, Domain1), b: (Domain, Domain1)): (Domain, Domain1) =
        (ev.quot(a._1, b._1), ev1.quot(a._2, b._2))

      override def mod(a: (Domain, Domain1), b: (Domain, Domain1)): (Domain, Domain1) =
        (ev.mod(a._1, b._1), ev1.mod(a._2, b._2))

      override def negate(x: (Domain, Domain1)): (Domain, Domain1) =
        (ev.negate(x._1), ev1.negate(x._2))

      override def zero: (Domain, Domain1) = (ev.zero, ev1.zero)

      override def one: (Domain, Domain1) = (ev.one, ev1.one)

      override def plus(x: (Domain, Domain1), y: (Domain, Domain1)): (Domain, Domain1) =
        (ev.plus(x._1, y._1), ev1.plus(x._2, y._2))

      override def div(x: (Domain, Domain1), y: (Domain, Domain1)): (Domain, Domain1) =
        (ev.div(x._1, y._1), ev1.div(x._2, y._2))

      override def times(x: (Domain, Domain1), y: (Domain, Domain1)): (Domain, Domain1) =
        (ev.times(x._1, y._1), ev1.times(x._2, y._2))
    }


  def isSquareMatrix[V](mat: Matrix[V]): Unit =
    if (mat.rows != mat.cols)
      throw new MatrixNotSquareException

  def isSymmetricMatrix[V](mat: Matrix[V]): Unit = {
    isSquareMatrix(mat)

    optimize {
      for (i <- 0 until mat.rows; j <- 0 until i)
        if (mat(i,j) != mat(j,i))
          throw new MatrixNotSymmetricException
    }
  }

  /**
    * Encapsulates renjin script engine and its capabilities.
    * */
  object Renjin {

    private val r_engine_factory = new RenjinScriptEngineFactory()

    val renjin: RenjinScriptEngine = r_engine_factory.getScriptEngine()

    val r: String => SEXP = (s: String) => renjin.eval(s).asInstanceOf[SEXP]

    val R: java.io.File => Unit = (f: java.io.File) => renjin.eval(f)
  }


}

