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
package io.github.mandar2812.dynaml.evaluation

import org.platanios.tensorflow.api.Tensor

/**
  * Evaluates classification models, by calculating confusion matrices.
  *
  * @param num_classes The number of classes in the classification task
  * @param preds Predictions expressed as class probabilities/one-hot vectors
  * @param targets Class labels expressed as one-hot vectors
  * */
class ClassificationMetricsTF(num_classes: Int, preds: Tensor, targets: Tensor) extends
  MetricsTF(Seq("Class Fidelity Score"), preds, targets) {

  val confusion_matrix: Tensor = targets.transpose().matmul(preds)

  val class_score: Tensor = {
    val d = confusion_matrix.trace
    val s = confusion_matrix.sum()
    d.divide(s)
  }

  override protected def run(): Tensor = class_score

  override def print(): Unit = {
    println("\nClassification Model Performance: "+name)
    scala.Predef.print("Number of classes: ")
    pprint.pprintln(num_classes)
    println("============================")
    println()

    println("Confusion Matrix: ")
    println(confusion_matrix.summarize(maxEntries = confusion_matrix.size.toInt))
    println()

    scala.Predef.print("Class Prediction Fidelity Score: ")
    pprint.pprintln(class_score.scalar.asInstanceOf[Float])
  }
}
