package io.github.idavydov.ivybp
import kotlin.math.ceil
import kotlin.math.floor
data class PressureScale(val min: Int, val max: Int, val step: Int) {
 val ticks: List<Int> get() = (min..max step step).toList()
}
fun pressureScale(values: List<Int>): PressureScale {
 val low=values.minOrNull() ?: 60
 val high=values.maxOrNull() ?: 140
 val step=if(high-low>100) 50 else 20
 val bottom=(floor((low-10).toDouble()/step)*step).toInt().coerceAtLeast(0)
 val top=(ceil((high+10).toDouble()/step)*step).toInt().coerceAtLeast(bottom+step)
 return PressureScale(bottom,top,step)
}
