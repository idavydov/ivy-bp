package io.github.idavydov.ivybp
import org.junit.Test
import org.junit.Assert.*
class ChartScaleTest {
 @Test fun containsPressuresWithEvenlySpacedTicks() {
  val s=pressureScale(listOf(83,124,87,131))
  assertTrue(s.min<83); assertTrue(s.max>131)
  assertEquals(s.min,s.ticks.first()); assertEquals(s.max,s.ticks.last())
  assertTrue(s.ticks.zipWithNext().all { (a,b) -> b-a==s.step })
 }
 @Test fun emptyOrConstantDataHasUsableAxis() {
  assertTrue(pressureScale(emptyList()).max>pressureScale(emptyList()).min)
  val s=pressureScale(listOf(120,120));assertTrue(s.min<120 && s.max>120)
 }
}
