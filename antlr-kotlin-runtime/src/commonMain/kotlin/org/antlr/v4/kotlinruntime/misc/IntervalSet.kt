// Copyright 2017-present Strumenta and contributors, licensed under Apache 2.0.
// Copyright 2024-present Strumenta and contributors, licensed under BSD 3-Clause.
package org.antlr.v4.kotlinruntime.misc

import com.strumenta.antlrkotlin.runtime.ext.appendCodePoint
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Token
import org.antlr.v4.kotlinruntime.Vocabulary
import kotlin.jvm.JvmField

/**
 * This class implements the [IntSet] backed by a sorted array of
 * non-overlapping intervals. It is particularly efficient for representing
 * large collections of numbers, where the majority of elements appear as part
 * of a sequential range of numbers that are all part of the set. For example,
 * the set `{ 1, 2, 3, 4, 7, 8 }` may be represented as `{ [1, 4], [7, 8] }`.
 *
 * This class is able to represent sets containing any combination of values in
 * the range [Int.MIN_VALUE] to [Int.MAX_VALUE] (inclusive).
 */
@Suppress("LocalVariableName")
public class IntervalSet : IntSet {
  public companion object {
    @JvmField
    public val COMPLETE_CHAR_SET: IntervalSet = of(Lexer.MIN_CHAR_VALUE, Lexer.MAX_CHAR_VALUE).also {
      it.isReadonly = true
    }

    @JvmField
    public val EMPTY_SET: IntervalSet = IntervalSet().also {
      it.isReadonly = true
    }

    /**
     * Create a set with a single element, [a].
     */
    public fun of(a: Int): IntervalSet {
      val s = IntervalSet()
      s.add(a)
      return s
    }

    /**
     * Create a set with all int(s) within range `a..b` (inclusive).
     */
    public fun of(a: Int, b: Int): IntervalSet {
      val s = IntervalSet()
      s.add(a, b)
      return s
    }

    /**
     * Combine all sets in the array returned the or'd value.
     */
    public fun or(vararg sets: IntervalSet): IntervalSet {
      val r = IntervalSet()

      for (s in sets) {
        r.addAll(s)
      }

      return r
    }

    /**
     * Compute the set difference between two interval sets.
     *
     * The specific operation is `left - right`.
     * If either of the input sets is `null`, it is treated as though it was an empty set.
     */
    public fun subtract(left: IntervalSet, right: IntervalSet): IntervalSet {
      if (left.isNil) {
        return IntervalSet()
      }

      val result = IntervalSet(left)

      if (right.isNil) {
        // Right set has no elements; just return the copy of the current set
        return result
      }

      var resultI = 0
      var rightI = 0

      while (resultI < result._intervals.size && rightI < right._intervals.size) {
        val resultInterval = result._intervals[resultI]
        val rightInterval = right._intervals[rightI]

        // Operation: (resultInterval - rightInterval) and update indexes

        if (rightInterval.b < resultInterval.a) {
          rightI++
          continue
        }

        if (rightInterval.a > resultInterval.b) {
          resultI++
          continue
        }

        var beforeCurrent: Interval? = null
        var afterCurrent: Interval? = null

        if (rightInterval.a > resultInterval.a) {
          beforeCurrent = Interval(resultInterval.a, rightInterval.a - 1)
        }

        if (rightInterval.b < resultInterval.b) {
          afterCurrent = Interval(rightInterval.b + 1, resultInterval.b)
        }

        if (beforeCurrent != null) {
          if (afterCurrent != null) {
            // Split the current interval into two
            result._intervals[resultI] = beforeCurrent
            result._intervals.add(resultI + 1, afterCurrent)
            resultI++
            rightI++
            continue
          } else {
            // Replace the current interval
            result._intervals[resultI] = beforeCurrent
            resultI++
            continue
          }
        } else {
          if (afterCurrent != null) {
            // Replace the current interval
            result._intervals[resultI] = afterCurrent
            rightI++
            continue
          } else {
            // Remove the current interval (thus no need to increment resultI)
            result._intervals.removeAt(resultI)
            continue
          }
        }
      }

      // If rightI reached right.intervals.size(), no more intervals to subtract from result.
      // If resultI reached result.intervals.size(), we would be subtracting from an empty set.
      // Either way, we are done.
      return result
    }
  }

  private val _intervals: MutableList<Interval>

  /**
   * The list of sorted, disjoint intervals.
   */
  public val intervals: List<Interval>
    get() = _intervals

  override val isNil: Boolean
    get() = _intervals.size == 0

  /**
   * Returns the maximum value contained in the set if not [isNil].
   *
   * @return The maximum value contained in the set
   * @throws RuntimeException If set is empty
   */
  public val maxElement: Int
    get() {
      if (isNil) {
        throw RuntimeException("set is empty")
      }

      val last = _intervals[_intervals.size - 1]
      return last.b
    }

  /**
   * Returns the minimum value contained in the set if not [isNil].
   *
   * @return The minimum value contained in the set
   * @throws RuntimeException If set is empty
   */
  public val minElement: Int
    get() {
      if (isNil) {
        throw RuntimeException("set is empty")
      }

      return _intervals[0].a
    }

  public var isReadonly: Boolean = false
    set(value) {
      if (field && !value) {
        throw IllegalStateException("can't alter readonly IntervalSet")
      }

      field = value
    }

  // This is the most used constructor.
  // Before, the vararg one was getting used, allocating an IntArray for no reason
  public constructor() {
    // The initial size of 16 is a best guess by evaluating
    // the size when executed under multiple grammars
    _intervals = ArrayList(16)
  }

  public constructor(intervals: MutableList<Interval>) {
    _intervals = intervals
  }

  public constructor(set: IntervalSet) {
    _intervals = ArrayList(set._intervals.size)
    addAll(set)
  }

  public fun clear() {
    if (isReadonly) {
      throw IllegalStateException("can't alter readonly IntervalSet")
    }

    _intervals.clear()
  }

  /**
   * Add a single element to the set.
   *
   * An isolated element is stored as a range `el..el`.
   */
  override fun add(el: Int): Unit =
    add(el, el)

  /**
   * Add interval; i.e., add all integers from a to b to set.
   *
   * If `b < a`, do nothing.
   * Keep list in sorted order (by left range value).
   * If there is overlap, combine ranges. For example,
   * if this is `{1..5, 10..20}`, adding `6..7` yields
   * `{1..5, 6..7, 10..20}`. Adding `4..8` yields `{1..8, 10..20}`.
   */
  public fun add(a: Int, b: Int): Unit =
    add(Interval.of(a, b))

  // Copy on write, so we can cache a..a intervals and sets of that
  private fun add(addition: Interval) {
    if (isReadonly) {
      throw IllegalStateException("can't alter readonly IntervalSet")
    }

    if (addition.b < addition.a) {
      return
    }

    // Find position in list.
    // Use iterators as we modify list in place
    val iter = _intervals.listIterator()

    while (iter.hasNext()) {
      val r = iter.next()

      if (addition == r) {
        return
      }

      if (addition.adjacent(r) || !addition.disjoint(r)) {
        // Next to each other, make a single larger interval
        val bigger = addition.union(r)
        iter.set(bigger)

        // Make sure we didn't just create an interval that
        // should be merged with next interval in list
        while (iter.hasNext()) {
          val next = iter.next()

          if (!bigger.adjacent(next) && bigger.disjoint(next)) {
            break
          }

          // If we bump up against or overlap next, merge
          iter.remove()                 // Remove this one
          iter.previous()               // Move backwards to what we just set
          iter.set(bigger.union(next))  // Set to 3 merged ones
          iter.next()                   // First call to next after previous duplicates the result
        }

        return
      }

      if (addition.startsBeforeDisjoint(r)) {
        // Insert before r
        iter.previous()
        iter.add(addition)
        return
      }

      // If disjoint and after r, a future iteration will handle it
    }

    // Ok, must be after last interval (and disjoint from last interval), just add it
    _intervals.add(addition)
  }

  override fun addAll(set: IntSet): IntervalSet {
    if (set is IntervalSet) {
      // Walk set and add each interval
      val setIntervals = set._intervals

      for (i in 0..<setIntervals.size) {
        val I = setIntervals[i]
        add(I.a, I.b)
      }
    } else {
      for (value in set.toList()) {
        add(value)
      }
    }

    return this
  }

  public fun complement(minElement: Int, maxElement: Int): IntervalSet =
    complement(of(minElement, maxElement))

  override fun complement(elements: IntSet): IntervalSet {
    if (elements.isNil) {
      // Nothing in common with null set
      return IntervalSet()
    }

    val vocabularyIS = if (elements is IntervalSet) {
      elements
    } else {
      val temp = IntervalSet()
      temp.addAll(elements)
      temp
    }

    return vocabularyIS.subtract(this)
  }

  override fun subtract(a: IntSet): IntervalSet {
    if (a.isNil) {
      return IntervalSet(this)
    }

    if (a is IntervalSet) {
      return subtract(this, a)
    }

    val other = IntervalSet()
    other.addAll(a)
    return subtract(this, other)
  }

  override fun or(a: IntSet): IntervalSet {
    val o = IntervalSet()
    o.addAll(this)
    o.addAll(a)
    return o
  }

  override fun and(a: IntSet): IntervalSet {
    if (a.isNil) {
      // Nothing in common with null set
      return IntervalSet()
    }

    val myIntervals = _intervals
    val theirIntervals = (a as IntervalSet)._intervals
    var intersection: IntervalSet? = null
    val mySize = myIntervals.size
    val theirSize = theirIntervals.size
    var i = 0
    var j = 0

    // Iterate down both interval lists looking for nondisjoint intervals
    while (i < mySize && j < theirSize) {
      val mine = myIntervals[i]
      val theirs = theirIntervals[j]

      if (mine.startsBeforeDisjoint(theirs)) {
        // Move this iterator looking for interval that might overlap
        i++
      } else if (theirs.startsBeforeDisjoint(mine)) {
        // Move other iterator looking for interval that might overlap
        j++
      } else if (mine.properlyContains(theirs)) {
        // Overlap, add intersection, get next theirs
        if (intersection == null) {
          intersection = IntervalSet()
        }

        intersection.add(mine.intersection(theirs))
        j++
      } else if (theirs.properlyContains(mine)) {
        // Overlap, add intersection, get next mine
        if (intersection == null) {
          intersection = IntervalSet()
        }

        intersection.add(mine.intersection(theirs))
        i++
      } else if (!mine.disjoint(theirs)) {
        // Overlap, add intersection
        if (intersection == null) {
          intersection = IntervalSet()
        }

        intersection.add(mine.intersection(theirs))
        // Move the iterator of lower range [a..b], but not
        // the upper range as it may contain elements that will collide
        // with the next iterator. So, if mine=[0..115] and
        // theirs=[115..200], then intersection is 115 and move mine
        // but not theirs as theirs may collide with the next range
        // in thisIter.
        // Move both iterators to next ranges
        if (mine.startsAfterNonDisjoint(theirs)) {
          j++
        } else if (theirs.startsAfterNonDisjoint(mine)) {
          i++
        }
      }
    }

    return intersection ?: IntervalSet()
  }

  override operator fun contains(el: Int): Boolean {
    val n = _intervals.size
    var l = 0
    var r = n - 1

    // Binary search for the element in the (sorted, disjoint) array of intervals
    while (l <= r) {
      val m = (l + r) / 2

      val I = _intervals[m]
      val a = I.a
      val b = I.b

      if (b < el) {
        l = m + 1
      } else if (a > el) {
        r = m - 1
      } else { // el >= a && el <= b
        return true
      }
    }

    return false
  }

  override fun hashCode(): Int {
    var hash = MurmurHash.initialize()

    for (I in _intervals) {
      hash = MurmurHash.update(hash, I.a)
      hash = MurmurHash.update(hash, I.b)
    }

    hash = MurmurHash.finish(hash, _intervals.size * 2)
    return hash
  }

  /**
   * Are two `IntervalSets` equal? Because all intervals are sorted
   * and disjoint, equals is a simple linear walk over both lists
   * to make sure they are the same.
   *
   * [Interval.equals] is used by the [List.equals] method to check the ranges.
   */
  override fun equals(other: Any?): Boolean =
    other is IntervalSet && _intervals == other._intervals

  override fun toString(): String =
    toString(false)

  public fun toString(elemAreChar: Boolean): String {
    if (_intervals.isEmpty()) {
      return "{}"
    }

    val buf = StringBuilder(64)

    if (size() > 1) {
      buf.append("{")
    }

    val n = _intervals.size
    var index = 0

    while (index < n) {
      val I = _intervals[index++]
      val a = I.a
      val b = I.b

      if (a == b) {
        if (a == Token.EOF) {
          buf.append("<EOF>")
        } else if (elemAreChar) {
          buf.append("'")
          buf.appendCodePoint(a)
          buf.append("'")
        } else {
          buf.append(a)
        }
      } else {
        if (elemAreChar) {
          buf.append("'")
          buf.appendCodePoint(a)
          buf.append("'..'")
          buf.appendCodePoint(b)
          buf.append("'")
        } else {
          buf.append(a)
          buf.append("..")
          buf.append(b)
        }
      }

      if (index < n) {
        buf.append(", ")
      }
    }

    if (size() > 1) {
      buf.append("}")
    }

    return buf.toString()
  }

  public fun toString(vocabulary: Vocabulary): String {
    if (_intervals.isEmpty()) {
      return "{}"
    }

    val buf = StringBuilder(64)

    if (size() > 1) {
      buf.append("{")
    }

    val n = _intervals.size
    var index = 0

    while (index < n) {
      val I = _intervals[index++]
      val a = I.a
      val b = I.b

      if (a == b) {
        buf.append(elementName(vocabulary, a))
      } else {
        for (p in a..b) {
          if (p > a) {
            buf.append(", ")
          }

          buf.append(elementName(vocabulary, p))
        }
      }

      if (index < n) {
        buf.append(", ")
      }
    }

    if (size() > 1) {
      buf.append("}")
    }

    return buf.toString()
  }

  private fun elementName(vocabulary: Vocabulary, a: Int): String =
    when (a) {
      Token.EOF -> "<EOF>"
      Token.EPSILON -> "<EPSILON>"
      else -> vocabulary.getDisplayName(a)
    }

  override fun size(): Int {
    val numIntervals = _intervals.size

    if (numIntervals == 1) {
      val firstInterval = _intervals[0]
      return firstInterval.b - firstInterval.a + 1
    }

    var n = 0

    for (i in 0..<numIntervals) {
      val I = _intervals[i]
      n += (I.b - I.a + 1)
    }

    return n
  }

  public fun toIntegerList(): IntegerList {
    val values = IntegerList(size())
    val n = _intervals.size

    for (i in 0..<n) {
      val I = _intervals[i]
      val a = I.a
      val b = I.b

      for (v in a..b) {
        values.add(v)
      }
    }

    return values
  }

  override fun toList(): List<Int> {
    val values = ArrayList<Int>(32)
    val n = _intervals.size

    for (i in 0..<n) {
      val I = _intervals[i]
      val a = I.a
      val b = I.b

      for (v in a..b) {
        values.add(v)
      }
    }

    return values
  }

  public fun toSet(): Set<Int> {
    val s = HashSet<Int>(32)

    for (I in _intervals) {
      val a = I.a
      val b = I.b

      for (v in a..b) {
        s.add(v)
      }
    }

    return s
  }

  /**
   * Get the ith element of ordered set.
   *
   * Used only by `RandomPhrase` so don't bother to implement
   * if you're not doing that for a new ANTLR code gen target.
   */
  public operator fun get(i: Int): Int {
    val n = _intervals.size
    var index = 0

    for (j in 0..<n) {
      val I = _intervals[j]
      val a = I.a
      val b = I.b

      for (v in a..b) {
        if (index == i) {
          return v
        }

        index++
      }
    }

    return -1
  }

  override fun remove(el: Int) {
    if (isReadonly) {
      throw IllegalStateException("can't alter readonly IntervalSet")
    }

    val n = _intervals.size

    for (i in 0..<n) {
      val I = _intervals[i]
      val a = I.a
      val b = I.b

      if (el < a) {
        break // List is sorted and el is before this interval; not here
      }

      // If whole interval x..x, rm
      if (el == a && el == b) {
        _intervals.removeAt(i)
        break
      }

      // If on left edge x..b, adjust left
      if (el == a) {
        I.a++
        break
      }

      // If on right edge a..x, adjust right
      if (el == b) {
        I.b--
        break
      }

      // If in middle a..x..b, split interval
      if (el < b) {
        // Found in this interval
        val oldB = I.b
        I.b = el - 1      // [a..x-1]
        add(el + 1, oldB) // add [x+1..b]
      }
    }
  }
}
