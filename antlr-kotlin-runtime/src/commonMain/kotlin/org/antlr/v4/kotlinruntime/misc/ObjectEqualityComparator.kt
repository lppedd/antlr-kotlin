// Copyright 2017-present Strumenta and contributors, licensed under Apache 2.0.
// Copyright 2024-present Strumenta and contributors, licensed under BSD 3-Clause.
package org.antlr.v4.kotlinruntime.misc

/**
 * This default implementation of [EqualityComparator] uses object equality
 * for comparisons by calling [Any.hashCode] and [Any.equals].
 *
 * @author Sam Harwell
 */
public object ObjectEqualityComparator : AbstractEqualityComparator<Any?>() {
  override fun hashCode(obj: Any?): Int =
    obj?.hashCode() ?: 0

  /**
   * This implementation relies on object equality.
   *
   * If both objects are `null`, this method returns `true`.
   *
   * Otherwise, if only [a] is `null` this method returns `false`.
   *
   * Otherwise, this method returns the result of `a == b`.
   */
  override fun equals(a: Any?, b: Any?): Boolean =
    (a == null && b == null) || a == b
}
