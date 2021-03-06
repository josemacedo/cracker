package cracker

import org.apache.spark.SparkContext._
import org.apache.spark.SparkContext
import scala.collection.mutable.ListBuffer
import java.io.FileWriter
import org.apache.spark.rdd.RDD
import util.CCUtil
import util.CCUtil
import util.CCUtil
import util.CCProperties

object CrackerTreeMain {
	
	def main(args : Array[String]) : Unit =
		{
			val timeBegin = System.currentTimeMillis()
			
			/*
			 * additional properties:
			 * crackerUseUnionInsteadOfJoin : true | false
			 * crackerCoalescePartition : true | false
			 */
			
			val propertyLoad = new CCProperties("CRACKER_TREE_SPLIT", args(0)).load
			val crackerUseUnionInsteadOfJoin = propertyLoad.getBoolean("crackerUseUnionInsteadOfJoin", true)
			val crackerCoalescePartition = propertyLoad.getBoolean("crackerCoalescePartition", true)
			val crackerForceEvaluation = propertyLoad.getBoolean("crackerForceEvaluation", true)
			val crackerSkipPropagation = propertyLoad.getBoolean("crackerSkipPropagation", false)
			
			val property = propertyLoad.getImmutable
			val cracker = new CrackerAlgorithm(property)

			val util = new CCUtil(property)
			val spark = util.getSparkContext()
			val stats = new CrackerStats(property, util, spark)

			val timeSparkLoaded = System.currentTimeMillis()
			val file = spark.textFile(property.dataset, property.sparkPartition)

			util.io.printFileStart(property.appName)

			//            val (parsedData, fusedData) = util.loadVertexEdgeFile(file)
			val (parsedData, fusedData) = util.loadEdgeFromFile(file)

			var ret = fusedData.map(item => (item._1, new CrackerTreeMessageIdentification((item._2.toSet + item._1).min, item._2.toSet)))

			val timeDataLoaded = System.currentTimeMillis()

			var control = false;
			var step = 0

			var treeRDD : Option[RDD[(Long, CrackerTreeMessageTree)]] = Option.empty
			
			// if not done, CC of size 1 are not recognized
			treeRDD = Option.apply(ret.map(t => (t._1, new CrackerTreeMessageTree(-1, Set()))))

			while (!control) {
				// simplification step
			    val timeStepStart = System.currentTimeMillis()

				stats.printSimplification(step, ret)
				
				ret = ret.flatMap(item => cracker.emitBlue(item, false))
				
				stats.printMessageStats(step + 1, ret)

				ret = ret.reduceByKey(cracker.reduceBlue).cache

				val active = ret.count
				control = active == 0

				val timeStepBlue = System.currentTimeMillis()
				util.printTimeStep(step + 1, timeStepBlue-timeStepStart)

				if (!control) {
					stats.printSimplification(step+1, ret)
					// reduction step
					val tmp = ret.flatMap(item => cracker.emitRed(item))

					stats.printMessageStats(step + 2, tmp)

					val tmpReduced = tmp.reduceByKey(cracker.reduceRed)

					ret = tmpReduced.filter(t => t._2.first.isDefined).map(t => (t._1, t._2.first.get))
					treeRDD = cracker.mergeTree(treeRDD, tmpReduced.filter(t => t._2.second.isDefined).map(t => (t._1, t._2.second.get)), crackerUseUnionInsteadOfJoin, crackerForceEvaluation)

					val timeStepEnd = System.currentTimeMillis()
					step = step + 2
					util.io.printTimeStep(timeStepStart, timeStepBlue, timeStepEnd)
					util.printTimeStep(step, timeStepEnd-timeStepBlue)
				} else {
					step = step + 1
					util.io.printTime(timeStepStart, timeStepBlue, "blue")
				}
			}
			
			stats.printSimplification(step, ret)
			
			if(!crackerSkipPropagation)
			{

			var treeRDDPropagationTmp = treeRDD.get
			
			if(crackerUseUnionInsteadOfJoin && crackerCoalescePartition)
			{
			    val timeStepStart = System.currentTimeMillis()
				treeRDDPropagationTmp = treeRDDPropagationTmp.coalesce(property.sparkPartition)
				val timeStepBlue = System.currentTimeMillis()
				util.io.printTime(timeStepStart, timeStepBlue, "coalescing")
			}
			
			stats.printMessageStats(step, treeRDDPropagationTmp)

			var treeRDDPropagation = treeRDDPropagationTmp.reduceByKey(cracker.reducePrepareDataForPropagation).map(t => (t._1, t._2.getMessagePropagation(t._1))).cache

			control = false
			while (!control) {
				val timeStepStart = System.currentTimeMillis()
				treeRDDPropagation = treeRDDPropagation.flatMap(item => cracker.mapPropagate(item))
				
				stats.printMessageStats(step + 1, treeRDDPropagation)
				
				treeRDDPropagation = treeRDDPropagation.reduceByKey(cracker.reducePropagate).cache
				control = treeRDDPropagation.map(t => t._2.min != -1).reduce { case (a, b) => a && b }

				step = step + 1
				val timeStepBlue = System.currentTimeMillis()
				util.io.printTime(timeStepStart, timeStepBlue, "propagation")
				util.printTimeStep(step, timeStepBlue-timeStepStart)
			}

			val timeEnd = System.currentTimeMillis()

			util.testEnded(treeRDDPropagation.map(t => (t._2.min, 1)).reduceByKey { case (a, b) => a + b },
				step,
				timeBegin,
				timeEnd,
				timeSparkLoaded,
				timeDataLoaded,
				stats.reduceInputMessageNumberAccumulator.value,
				stats.reduceInputSizeAccumulator.value,
				getBitmaskStat(crackerUseUnionInsteadOfJoin,crackerCoalescePartition,crackerForceEvaluation))
				
			} else
			{
				val timeEnd = System.currentTimeMillis()
				val vertexNumber = fusedData.count
				
				util.testEnded(treeRDD.get.map(t => (1L, 1)).reduceByKey { case (a, b) => a + b },
				step,
				timeBegin,
				timeEnd,
				timeSparkLoaded,
				timeDataLoaded,
				stats.reduceInputMessageNumberAccumulator.value + cracker.getMessageNumberForPropagation(step, vertexNumber),
				stats.reduceInputSizeAccumulator.value + cracker.getMessageSizeForPropagation(step, vertexNumber),
				getBitmaskStat(crackerUseUnionInsteadOfJoin,crackerCoalescePartition,crackerForceEvaluation))
			}
		}
	
	def bool2int(b:Boolean) = if (b) 1 else 0
	
	def getBitmaskStat(	crackerUseUnionInsteadOfJoin : Boolean,
	        			crackerCoalescePartition : Boolean,
	        			crackerForceEvaluation : Boolean) : String =
	{
	    bool2int(crackerUseUnionInsteadOfJoin).toString+bool2int(crackerCoalescePartition).toString+bool2int(crackerForceEvaluation).toString
	}

}