package org.kuleuven.esat.kernels

import breeze.linalg.{DenseMatrix, DenseVector}

/**
 * Standard Polynomial SVM Kernel
 * of the form K(Xi,Xj) = (Xi^T * Xj + d)^r
 */
class PolynomialKernel(
    private var degree: Int,
    private var offset: Double)
  extends SVMKernel[DenseMatrix[Double]]
  with Serializable{

  def setDegree(d: Int): Unit = {
    this.degree = d
  }

  def setOffset(o: Int): Unit = {
    this.offset = o
  }

  override def evaluate(x: DenseVector[Double], y: DenseVector[Double]): Double =
    Math.pow(x dot y + this.offset, this.degree)

  override def buildKernelMatrix(
      mappedData: List[DenseVector[Double]],
      length: Int): KernelMatrix[DenseMatrix[Double]] =
    SVMKernel.buildSVMKernelMatrix(mappedData, length, this.evaluate)
}
