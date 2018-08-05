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
package io.github.mandar2812.dynaml.tensorflow.utils

import org.platanios.tensorflow.api._
import _root_.io.github.mandar2812.dynaml.pipes._

/**
  * Scales attributes of a vector pattern using the sample mean and variance of
  * each dimension. This assumes that there is no covariance between the data
  * dimensions.
  *
  * @param mean Sample mean of the data
  * @param sigma Sample variance of each data dimension
  * @author mandar2812 date: 07/03/2018.
  *
  * */
case class GaussianScalerTF(mean: Tensor, sigma: Tensor) extends TFScaler {

  override val i: Scaler[Tensor] = Scaler((xc: Tensor) => xc.multiply(sigma).add(mean))

  override def run(data: Tensor): Tensor = data.subtract(mean).divide(sigma)

  def apply(indexers: Indexer*): GaussianScalerTF = this.copy(mean(indexers:_*), sigma(indexers:_*))

}


case class GaussianScalerTO(mean: Output, sigma: Output) extends TOScaler {

  override val i: Scaler[Output] = Scaler((xc: Output) => xc.multiply(sigma).add(mean))

  override def run(data: Output): Output = data.subtract(mean).divide(sigma)

  def apply(indexers: Indexer*): GaussianScalerTO = this.copy(mean(indexers:_*), sigma(indexers:_*))

}
