// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/**
 * A copy of a collection other threads keep changing. Kotlin's `toList()` reads the size first and
 * then takes the first element without checking, so a run removed in between throws
 * NoSuchElementException. Copying through `toArray()` is safe for the java.util.concurrent sets.
 */
internal fun <T> Collection<T>.snapshot(): List<T> = ArrayList(this)
