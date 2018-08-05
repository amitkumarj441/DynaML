package io.github.mandar2812.dynaml.models.svm

import breeze.linalg.{DenseMatrix, DenseVector, norm}
import breeze.numerics.sqrt
import io.github.mandar2812.dynaml.analysis.VectorField
import org.apache.log4j.Logger
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.stat.Statistics
import org.apache.spark.rdd.RDD
import io.github.mandar2812.dynaml.models.KernelizedModel
import io.github.mandar2812.dynaml.kernels._
import io.github.mandar2812.dynaml.prototype.{GreedyEntropySelector, QuadraticRenyiEntropy}
import org.apache.spark.storage.StorageLevel

import scala.util.Random

/**
 * Implementation of the Fixed Size
 * Kernel based LS SVM
 *
 * Fixed Size implies that the model
 * chooses a subset of the original
 * data to calculate a low rank approximation
 * to the kernel matrix.
 *
 * Feature Extraction is done in the primal
 * space using the Nystrom approximation.
 *
 * @author mandar2812
 */
abstract class KernelSparkModel(data: RDD[LabeledPoint], task: String)
  extends KernelizedModel[RDD[(Long, LabeledPoint)], RDD[LabeledPoint],
    DenseVector[Double], DenseVector[Double], Double, Int, Int](task)
  with Serializable {

  override protected val g = LSSVMSparkModel.indexedRDD(data)

  protected var processed_g = g

  val colStats = Statistics.colStats(g.map(_._2.features))

  override protected val nPoints: Long = colStats.count

  protected var featuredims: Int = g.first()._2.features.size

  protected var effectivedims: Int = featuredims + 1

  protected var prototypes: List[DenseVector[Double]] = List()

  implicit val vecField = VectorField(featuredims)

  val logger = Logger.getLogger(this.getClass)

  override def getXYEdges: RDD[LabeledPoint] = data

  def getRegParam: Double

  def setRegParam(l: Double): this.type

  def setMiniBatchFraction(f: Double): this.type = {
    assert(f <= 1.0 && f >= 0.0, "Mini Batch Fraction must be between 0 and 1.0")
    this.optimizer.setMiniBatchFraction(f)
    this
  }

  override def initParams() = DenseVector.ones[Double](effectivedims)

  override def optimumSubset(M: Int): Unit = {
    points = (0L to this.npoints - 1).toList
    if (M < this.npoints) {
      logger.info("Calculating sample variance of the data set")

      //Get the original features of the data
      //Calculate the column means and variances
      val (mean, variance) = (DenseVector(colStats.mean.toArray),
        DenseVector(colStats.variance.toArray))

      //Use the adjusted value of the variance
      val adjvarance: DenseVector[Double] = variance :/= (npoints.toDouble - 1)
      val density = new GaussianDensityKernel

      logger.info("Using Silvermans rule of thumb to set bandwidth of density kernel")
      logger.info("Std Deviation of the data: " + adjvarance.toString())
      logger.info("norm: " + norm(adjvarance))
      density.setBandwidth(DenseVector.tabulate[Double](featuredims) {
        i => 1.06 * math.sqrt(adjvarance(i)) / math.pow(npoints, 0.2)
      })
      logger.info("Building low rank approximation to kernel matrix")

      prototypes = GreedyEntropySelector.subsetSelectionQRE(this.g,
        new QuadraticRenyiEntropy(density), M, 25, 0.00001)
    }
  }

  override def applyKernel(kernel: SVMKernel[DenseMatrix[Double]],
                           M: Int = math.sqrt(npoints).toInt): Unit = {

    if(M != this.prototypes.length) {
      this.optimumSubset(M)
    }

    if(this.processed_g.first()._2.features.size > featuredims) {
      this.processed_g.unpersist()
    }

    val (mean, variance) = (DenseVector(colStats.mean.toArray),
      DenseVector(colStats.variance.toArray))

    val scalingFunc = KernelSparkModel.scalePrototype(mean, variance) _

    val scaledPrototypes = prototypes map scalingFunc

    val kernelMatrix =
      kernel.buildKernelMatrix(scaledPrototypes, M)
    val decomposition = kernelMatrix.eigenDecomposition(M)

    var selectedEigenVectors: List[DenseMatrix[Double]] = List()
    var selectedEigenvalues: List[Double] = List()

    (0 until M).foreach((p) => {
      //Check the Girolami criterion
      // (1.u)^2 >= 2M/(1+M)
      //This increases parsimony
      val u = decomposition._2(::, p)
      if(math.pow(norm(u,1), 2.0) >= 2.0*M/(1.0+M.toDouble)) {
        selectedEigenvalues :+= decomposition._1(p)
        selectedEigenVectors :+= u.toDenseMatrix
      }

    })
    logger.info("Selected Components: "+selectedEigenvalues.length)
    effectivedims = selectedEigenvalues.length + 1
    val decomp = (DenseVector(selectedEigenvalues.toArray),
      DenseMatrix.vertcat(selectedEigenVectors:_*).t)

    this.featureMap = kernel.featureMapping(decomp)(scaledPrototypes)
    this.params = DenseVector.ones[Double](effectivedims)
  }

  override def applyFeatureMap: Unit = {
    val meanb = g.context.broadcast(DenseVector(colStats.mean.toArray))
    val varianceb = g.context.broadcast(DenseVector(colStats.variance.toArray))
    val featureMapb = g.context.broadcast(featureMap)
    this.processed_g = g.map((point) => {
      val vec = DenseVector(point._2.features.toArray)
      val ans = vec - meanb.value
      ans :/= sqrt(varianceb.value)

      (point._1, new LabeledPoint(
        point._2.label,
        Vectors.dense(DenseVector.vertcat(
          featureMapb.value(ans),
          DenseVector(1.0))
          .toArray)
      ))
    }).persist(StorageLevel.MEMORY_ONLY_SER)
  }

  override def trainTest(test: List[Long]) = {
    val training_data = this.processed_g.filter((keyValue) =>
      !test.contains(keyValue._1)).map(_._2)

    val test_data = this.processed_g.filter((keyValue) =>
      test.contains(keyValue._1)).map(_._2)

    training_data.persist(StorageLevel.MEMORY_AND_DISK)
    test_data.persist(StorageLevel.MEMORY_AND_DISK)
    (training_data, test_data)
  }

  /**
    * Calculates the energy of the configuration,
    * in most global optimization algorithms
    * we aim to find an approximate value of
    * the hyper-parameters such that this function
    * is minimized.
    *
    * @param h The value of the hyper-parameters in the configuration space
    * @param options Optional parameters about configuration
    * @return Configuration Energy E(h)
    **/
  override def energy(h: Map[String, Double], options: Map[String, String]): Double = {
    //set the kernel paramters if options is defined
    //then set model parameters and cross validate
    var kernelflag = true
    if(options.contains("kernel")) {
      val kern = options("kernel") match {
        case "RBF" => new RBFKernel().setHyperParameters(h)
        case "Polynomial" => new PolynomialKernel().setHyperParameters(h)
        case "Exponential" => new ExponentialKernel().setHyperParameters(h)
        case "Laplacian" => new LaplacianKernel().setHyperParameters(h)
        case "Cauchy" => new CauchyKernel().setHyperParameters(h)
        case "RationalQuadratic" => new RationalQuadraticKernel().setHyperParameters(h)
        case "Wave" => new WaveKernel().setHyperParameters(h)
      }
      //check if h and this.current_state have the same kernel params
      //calculate kernParam(h)
      //calculate kernParam(current_state)
      //if both differ in any way then apply
      //the kernel
      val nprototypes = if(options.contains("subset")) options("subset").toInt
      else math.sqrt(this.npoints).toInt
      val kernh = h.filter((couple) => kern.hyper_parameters.contains(couple._1))
      val kerncs = current_state.filter((couple) => kern.hyper_parameters.contains(couple._1))
      if(!(kernh sameElements kerncs)) {
        this.applyKernel(kern, nprototypes)
        kernelflag = false
      }
    }
    this.applyFeatureMap

    val (_,_,e) = this.crossvalidate(4, h("RegParam"), optionalStateFlag = kernelflag)
    current_state = h
    1.0-e
  }

}

object KernelSparkModel {
  def scalePrototype(mean: DenseVector[Double],
                     variance: DenseVector[Double])
                    (prototype: DenseVector[Double]): DenseVector[Double] =
    (prototype - mean)/sqrt(variance)
}
