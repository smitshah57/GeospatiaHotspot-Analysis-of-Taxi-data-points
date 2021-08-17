package cse512

import org.apache.log4j.{ Level, Logger }
import org.apache.spark.sql.{ DataFrame, SparkSession }
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.functions._

object HotcellAnalysis {
  Logger.getLogger("org.spark_project").setLevel(Level.WARN)
  Logger.getLogger("org.apache").setLevel(Level.WARN)
  Logger.getLogger("akka").setLevel(Level.WARN)
  Logger.getLogger("com").setLevel(Level.WARN)

  def runHotcellAnalysis(spark: SparkSession, pointPath: String): DataFrame =
    {
      // Load the original data from a data source
      var pickupInfo = spark.read.format("csv").option("delimiter", ";").option("header", "false").load(pointPath);
      pickupInfo.createOrReplaceTempView("nyctaxitrips")
      
      // Assign cell coordinates based on pickup points
      spark.udf.register("CalculateX", (pickupPoint: String) => ((
      HotcellUtils.CalculateCoordinate(pickupPoint, 0))))
      spark.udf.register("CalculateY", (pickupPoint: String) => ((
      HotcellUtils.CalculateCoordinate(pickupPoint, 1))))
      spark.udf.register("CalculateZ", (pickupTime: String) => ((
      HotcellUtils.CalculateCoordinate(pickupTime, 2))))
      pickupInfo = spark.sql("select CalculateX(nyctaxitrips._c5),CalculateY(nyctaxitrips._c5), CalculateZ(nyctaxitrips._c1) from nyctaxitrips")
      var newCoordinateName = Seq("x", "y", "z")
      pickupInfo = pickupInfo.toDF(newCoordinateName: _*)
      pickupInfo.createOrReplaceTempView("pickupinfo")
      
      // Define the min and max of x, y, z
      val minX = -74.50 / HotcellUtils.coordinateStep
      val maxX = -73.70 / HotcellUtils.coordinateStep
      val minY = 40.50 / HotcellUtils.coordinateStep
      val maxY = 40.90 / HotcellUtils.coordinateStep
      val minZ = 1
      val maxZ = 31
      val numCells = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)

      val points_DF = spark.sql("select x,y,z from pickupinfo where x>=" + minX + " and y>= " + minY + " and z>= " + minZ +" and x<= " + maxX +  " and y<= " + maxY +  " and z<= " + maxZ + " order by z,y,x").persist();
      points_DF.createOrReplaceTempView("Df0")

      val points_DF1 = spark.sql("select x,y,z,count(*) as point_value from Df0 group by z,y,x order by z,y,x").persist();
      points_DF1.createOrReplaceTempView("Df1")

      spark.udf.register("squared", (inputX: Int) => ((HotcellUtils.squared(inputX))))

      val points_sum = spark.sql("select count(*) as count_value, sum(point_value) as sum_value,sum(squared(point_value)) as squaredsum from Df1");
      points_sum.createOrReplaceTempView("points_sum")

      val points_sum0 = points_sum.first().getLong(1);
      val points_sum1 = points_sum.first().getDouble(2);
      val points_sum2 = points_sum.first().getLong(0);

      val mean =(points_sum0.toDouble / numCells.toDouble).toDouble;
      val standard_deviation = math.sqrt(((points_sum1.toDouble / numCells.toDouble) - (mean.toDouble * mean.toDouble))).toDouble

      spark.udf.register("NeighbourCount", (minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int, inputX: Int, inputY: Int, inputZ: Int)
      => ((HotcellUtils.count_number_of_neighbours(minX, minY, minZ, maxX, maxY, maxZ, inputX, inputY, inputZ))))

      val neighbour = spark.sql("select NeighbourCount("+minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ + "," + "a1.x,a1.y,a1.z) as nCount,count(*) as countall, a1.x as x,a1.y as y,a1.z as z, sum(a2.point_value) as sumtotal from Df1 as a1, Df1 as a2 where (a2.x = a1.x+1 or a2.x = a1.x or a2.x = a1.x-1) and (a2.y = a1.y+1 or a2.y = a1.y or a2.y =a1.y-1) and (a2.z = a1.z+1 or a2.z = a1.z or a2.z =a1.z-1) group by a1.z,a1.y,a1.x order by a1.z,a1.y,a1.x").persist()
      neighbour.createOrReplaceTempView("Df2");

      spark.udf.register("GScore", (x: Int, y: Int, z: Int, mean:Double, standard_deviation: Double, count: Int, sum: Int, numcells: Int) => ((
      HotcellUtils.getstats(x, y, z, mean, standard_deviation, count, sum, numcells))))
      
      val neighbour1 = spark.sql("select GScore(x,y,z,"+mean+","+standard_deviation+",ncount,sumtotal,"+numCells+") as gtstat,x, y, z from Df2 order by gtstat desc");
      neighbour1.createOrReplaceTempView("Df3")
      
      val answer = spark.sql("select x,y,z from Df3")
      answer.createOrReplaceTempView("Df4")
      return answer 
      
    }
}