/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.angel.ml.factorizationmachines

import com.tencent.angel.ml.MLLearner
import com.tencent.angel.ml.conf.MLConf
import com.tencent.angel.ml.feature.LabeledData
import com.tencent.angel.ml.math.vector.{DenseDoubleVector, SparseDoubleSortedVector}
import com.tencent.angel.ml.matrix.psf.update.RandomNormal
import com.tencent.angel.ml.metric.log.LossMetric
import com.tencent.angel.ml.model.MLModel
import com.tencent.angel.psagent.matrix.transport.adapter.RowIndex
import com.tencent.angel.worker.storage.DataBlock
import com.tencent.angel.worker.task.TaskContext
import org.apache.commons.logging.{Log, LogFactory}

import scala.collection.mutable

/**
  * Learner of Factorization machines
  * @param ctx: context of this task
  * @param minP: min value of y
  * @param maxP: max value of y
  * @param feaUsed: array of used feature of the input data
  */
class FMLearner(override val ctx: TaskContext, val minP: Double, val maxP: Double, val feaUsed: Array[Int])
  extends MLLearner(ctx) {

  val LOG: Log = LogFactory.getLog(classOf[FMLearner])
  val fmModel = new FMModel(conf, ctx)

  val learnType = conf.get(MLConf.ML_FM_LEARN_TYPE, MLConf.DEFAULT_ML_FM_LEARN_TYPE)
  val feaNum: Int = conf.getInt(MLConf.ML_FEATURE_NUM, MLConf.DEFAULT_ML_FEATURE_NUM)
  val epochNum: Int = conf.getInt(MLConf.ML_EPOCH_NUM, MLConf.DEFAULT_ML_EPOCH_NUM)

  val rank: Int = conf.getInt(MLConf.ML_FM_RANK, MLConf.DEFAULT_ML_FM_RANK)
  val reg0: Double = conf.getDouble(MLConf.ML_FM_REG0, MLConf.DEFAULT_ML_FM_REG0)
  val reg1: Double = conf.getDouble(MLConf.ML_FM_REG1, MLConf.DEFAULT_ML_FM_REG1)
  val reg2: Double = conf.getDouble(MLConf.ML_FM_REG2, MLConf.DEFAULT_ML_FM_REG2)
  val lr: Double = conf.getDouble(MLConf.ML_LEARN_RATE, MLConf.DEFAULT_ML_LEAR_RATE)
  val vStddev: Double = conf.getDouble(MLConf.ML_FM_V_STDDEV, MLConf.DEFAULT_ML_FM_V_INIT)

  val vIndexes = new RowIndex()
  feaUsed.zipWithIndex.filter((p:(Int, Int))=>p._1!=0).map((p:(Int, Int))=>vIndexes.addRowId(p._2))
  val feaUsedN = vIndexes.getRowsNumber
  LOG.info("vIndexs's row's number = " + vIndexes)

  /**
    * Train a Factorization machines Model
    *
    * @param trainData : input train data storage
    * @param vali      : validate data storage
    * @return : a learned model
    */
  override
  def train(trainData: DataBlock[LabeledData], vali: DataBlock[LabeledData]): MLModel = {
    val start = System.currentTimeMillis()
    LOG.info(s"learnType=$learnType, feaNum=$feaNum, rank=$rank, #trainData=${trainData.size}")
    LOG.info(s"reg0=$reg0, reg1=$reg1, reg2=$reg2, lr=$lr, vStev=$vStddev")

    val beforeInit = System.currentTimeMillis()
    initModels()
    val initCost = System.currentTimeMillis() - beforeInit
    LOG.info(s"Init matrixes cost $initCost ms.")

    globalMetrics.addMetrics(fmModel.FM_OBJ, LossMetric(trainData.size()))

    while (ctx.getIteration < epochNum) {
      val startIter = System.currentTimeMillis()
      val (w0, w, v) = oneIteration(trainData)
      val iterCost = System.currentTimeMillis() - startIter

      val startVali = System.currentTimeMillis()
      val loss = evaluate(trainData, w0.get(0), w, v)
      val valiCost = System.currentTimeMillis() - startVali

      globalMetrics.metrics(fmModel.FM_OBJ, loss)
      LOG.info(s"Epoch=${ctx.getIteration}, evaluate loss=${loss/trainData.size()}. " +
        s"trainCost=$iterCost, " +
        s"valiCost=$valiCost")

      ctx.incIteration()
    }

    val end = System.currentTimeMillis()
    val cost = end - start
    LOG.info(s"FM Learner train cost $cost ms.")
    fmModel
  }

  /**
    * Initialize with random values
    */
  def initModels(): Unit = {
    if(ctx.getTaskId.getIndex == 0) {
      for (row <- 0 until feaNum) {
        fmModel.v.update(new RandomNormal(fmModel.v.getMatrixId(), row, 0.0, vStddev)).get()
      }
    }

    fmModel.v.clock().get()
  }

  /**
    * One iteration to train Factorization Machines
 *
    * @param dataBlock
    * @return
    */
  def oneIteration(dataBlock: DataBlock[LabeledData]):
    (DenseDoubleVector, DenseDoubleVector, mutable.HashMap[Int, DenseDoubleVector]) = {

    val startGet = System.currentTimeMillis()
    val (w0, w, v) = fmModel.pullFromPS(vIndexes)
    val getCost = System.currentTimeMillis() - startGet
    LOG.info(s"Get matrixes cost $getCost ms.")

    val _w0 = w0.clone()
    val _w = w.clone()
    val _v = new mutable.HashMap[Int, DenseDoubleVector]()
    for (vec <- v) {
      _v.put(vec._1, vec._2.clone())
    }
    LOG.info(s"v has ${_v.size} rows.")

    dataBlock.resetReadIndex()
    for (_ <- 0 until dataBlock.size) {
      val data = dataBlock.read()
      val x = data.getX.asInstanceOf[SparseDoubleSortedVector]
      val y = data.getY
      val pre = predict(x, y, _w0.get(0), _w, _v)
      val dm = derviationMultipler(y, pre)

      _w0.plusBy(0, -lr * (dm + reg0 * _w0.get(0)))
      _w.timesBy(1 - lr * reg1).plusBy(x, -lr * dm)
      updateV(x, dm, _v)
    }

    for (update <- _v) {
      v(update._1).plusBy(update._2, -1.0).timesBy(-1.0)
    }

    fmModel.pushToPS(w0.plusBy(_w0, -1.0).timesBy(-1.0).asInstanceOf[DenseDoubleVector],
      w.plusBy(_w, -1.0).timesBy(-1.0).asInstanceOf[DenseDoubleVector],
      v)

    (_w0, _w, _v)
  }

  /**
    * Evaluate the objective value
 *
    * @param dataBlock
    * @param w0
    * @param w
    * @param v
    * @return
    */
  def evaluate(dataBlock: DataBlock[LabeledData], w0: Double, w: DenseDoubleVector,
               v: mutable.HashMap[Int, DenseDoubleVector]):
  Double = {
    var loss = 0.0

    dataBlock.resetReadIndex()
    for (_ <- 0 until dataBlock.size) {
      val data = dataBlock.read()
      val x = data.getX.asInstanceOf[SparseDoubleSortedVector]
      val y = data.getY
      val pre = predict(x, y, w0, w, v)
      loss += (pre - y) * (pre - y)
    }

    loss
  }

  /**
    * Predict an instance
 *
    * @param x：feature vector of instance
    * @param y: label value of instance
    * @param w0: w0 mat of FM
    * @param w: w mat of FM
    * @param v: v mat of FM
    * @return
    */
  def predict(x: SparseDoubleSortedVector, y: Double, w0: Double, w: DenseDoubleVector, v:
  mutable.HashMap[Int, DenseDoubleVector]): Double = {
    var ret: Double = 0.0
    ret += w0
    ret += x.dot(w)

    for (f <- 0 until rank) {
      var ret1 = 0.0
      var ret2 = 0.0
      for (i <- 0 until x.size()) {
        val tmp = x.getValues()(i) * v(x.getIndices()(i)).get(f)
        ret1 += tmp
        ret2 += tmp * tmp
      }
      ret += 0.5 * (ret1 * ret1 - ret2)
    }

    ret = if (ret < maxP) ret else maxP
    ret = if (ret > minP) ret else minP

    ret
  }

  /**
    * \frac{\partial loss}{\partial x} = dm * \frac{\partial y}{\partial x}
 *
    * @param y: label of the instance
    * @param pre: predict value of the instance
    * @return : dm value
    */
  def derviationMultipler(y: Double, pre: Double): Double = {
    learnType match {
      // For classification:
      // loss=-ln(\delta (pre\cdot y))
      // \frac{\partial loss}{\partial x}=(\delta (pre\cdot y)-1)y\frac{\partial y}{\partial x}
      case "c" => -y * (1.0 - 1.0 / (1 + Math.exp(-y * pre)))
      // For regression:
      // loss = (pre-y)^2
      // \frac{\partial loss}{\partial x}=2(pre-y)\frac{\partial y}{\partial x}
      //      case "r" => 2 * (pre - y)
      case "r" => pre - y
    }
  }

  /**
    * Update v mat
 *
    * @param x: a train instance
    * @param dm: dm value of the instance
    * @param v: v mat
    */
  def updateV(x: SparseDoubleSortedVector, dm: Double, v: mutable.HashMap[Int, DenseDoubleVector]):
  Unit = {

    for (f <- 0 until rank) {
      // calculate dot(vf, x)
      var dot = 0.0
      for (i <- 0 until x.size()) {
        dot += x.getValues()(i) * v(x.getIndices()(i)).get(f)
      }

      for (i <- 0 until x.size()) {
        val j = x.getIndices()(i)
        val grad = dot * x.getValues()(i) - v(j).get(f) * x.getValues()(i) * x.getValues()(i)
        v(j).plusBy(f, -lr * (dm * grad + reg2 * v(j).get(f)))
      }
    }
  }

}
