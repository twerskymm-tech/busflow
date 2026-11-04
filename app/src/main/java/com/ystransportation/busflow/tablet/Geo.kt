package com.ystransportation.busflow.tablet

import com.mapbox.geojson.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geometry helpers for the planned path (a list of points along the roads). */
object Geo {
    private const val R = 6371000.0

    /** Google encoded polyline → points. */
    fun decode(str: String, precision: Int = 5): List<Point> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<Point>()
        var i = 0
        var lat = 0
        var lng = 0
        while (i < str.length) {
            var result = 0
            var shift = 0
            var b: Int
            do { b = str[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && i < str.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
            result = 0
            shift = 0
            do { b = str[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && i < str.length)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
            out.add(Point.fromLngLat(lng / factor, lat / factor))
        }
        return out
    }

    fun dist(a: Point, b: Point): Double {
        val la1 = Math.toRadians(a.latitude()); val la2 = Math.toRadians(b.latitude())
        val dLa = la2 - la1; val dLo = Math.toRadians(b.longitude() - a.longitude())
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearing(a: Point, b: Point): Double {
        val la1 = Math.toRadians(a.latitude()); val la2 = Math.toRadians(b.latitude())
        val dLo = Math.toRadians(b.longitude() - a.longitude())
        val y = sin(dLo) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLo)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Distance along the path to each point, from the start. */
    fun cumulative(path: List<Point>): DoubleArray {
        val c = DoubleArray(path.size)
        for (i in 1 until path.size) c[i] = c[i - 1] + dist(path[i - 1], path[i])
        return c
    }

    /** The path point a given distance after point [from]. */
    fun indexAtDistance(cum: DoubleArray, from: Int, meters: Double): Int {
        if (cum.isEmpty()) return 0
        val goal = cum[from.coerceIn(0, cum.size - 1)] + meters
        var i = from.coerceIn(0, cum.size - 1)
        while (i < cum.size - 1 && cum[i] < goal) i++
        return i
    }

    /** Distance (m) from p to the segment a–b. */
    fun segDist(p: Point, a: Point, b: Point): Double {
        val k = 111320.0 * cos(Math.toRadians(p.latitude()))
        val ax = (a.longitude() - p.longitude()) * k; val ay = (a.latitude() - p.latitude()) * 111320.0
        val bx = (b.longitude() - p.longitude()) * k; val by = (b.latitude() - p.latitude()) * 111320.0
        val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
        var t = if (len > 0) -(ax * dx + ay * dy) / len else 0.0
        t = max(0.0, min(1.0, t))
        val x = ax + t * dx; val y = ay + t * dy
        return sqrt(x * x + y * y)
    }

    /**
     * Nearest path point to p, looking from [back] metres before [from] to [ahead] metres after it.
     * Returns (index, distance in metres).
     */
    fun nearestInWindow(path: List<Point>, cum: DoubleArray, p: Point, from: Int, back: Double, ahead: Double): Pair<Int, Double> {
        if (path.isEmpty()) return 0 to Double.MAX_VALUE
        val f = from.coerceIn(0, path.size - 1)
        var lo = f
        while (lo > 0 && cum[f] - cum[lo - 1] <= back) lo--
        var best = f
        var bd = Double.MAX_VALUE
        var i = lo
        while (i < path.size && cum[i] - cum[f] <= ahead) {
            val d = if (i < path.size - 1) segDist(p, path[i], path[i + 1]) else dist(p, path[i])
            if (d < bd) { bd = d; best = i }
            i++
        }
        // the end of the matched piece is a better "where I am" than its start
        if (best < path.size - 1 && dist(p, path[best + 1]) < dist(p, path[best])) best += 1
        return best to bd
    }

    /** The first place (from the start) where the path comes close to p — used for stops. */
    fun firstNear(path: List<Point>, p: Point, from: Int, within: Double = 40.0): Int {
        var best = from
        var bd = Double.MAX_VALUE
        for (i in from until path.size) {
            val d = dist(p, path[i])
            if (d < bd) { bd = d; best = i }
        }
        val lim = bd + within
        for (i in from..best) if (dist(p, path[i]) <= lim) return i
        return best
    }

    /**
     * Picks path points to send to Mapbox: keeps every turn (simplify with [tolerance] m),
     * never leaves more than [maxGap] m between two points. Returns path indexes from..to.
     */
    fun sample(path: List<Point>, cum: DoubleArray, from: Int, to: Int, tolerance: Double = 8.0, maxGap: Double = 220.0): List<Int> {
        if (to <= from) return listOf(from)
        val keep = BooleanArray(path.size)
        keep[from] = true; keep[to] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(from to to)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            var idx = -1
            var md = 0.0
            for (i in a + 1 until b) {
                val d = segDist(path[i], path[a], path[b])
                if (d > md) { md = d; idx = i }
            }
            if (idx > 0 && md > tolerance) { keep[idx] = true; stack.addLast(a to idx); stack.addLast(idx to b) }
        }
        val out = ArrayList<Int>()
        var last = -1
        for (i in from..to) {
            if (!keep[i]) continue
            if (last >= 0) {
                // fill long gaps with in-between path points
                var j = last
                while (cum[i] - cum[j] > maxGap) {
                    val next = indexAtDistance(cum, j, maxGap).coerceAtMost(i)
                    if (next <= j || next >= i) break
                    out.add(next); j = next
                }
            }
            out.add(i); last = i
        }
        return out
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
