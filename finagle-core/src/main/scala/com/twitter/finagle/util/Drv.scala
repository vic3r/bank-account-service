package com.twitter.finagle.util

import scala.annotation.tailrec
import scala.collection.mutable

trait Drv extends (Rng => Int)

/**
 * Create discrete random variables representing arbitrary distributions.
 */
object Drv {
  private val ε = 0.01

  // We need the sum to be approximately equal to 1.0 +/- ε
  private[this] def isNormalized(sum: Double): Boolean = {
    1 - ε < sum && sum < 1 + ε
  }

  /**
   * A Drv using the Aliasing method [1]: a distribution is described
   * by a set of probabilities and aliases. In order to pick a value
   * j in distribution Pr(Y = j), j=1..n, we first pick a random
   * integer in the uniform distribution over 1..n. We then inspect
   * the probability table whose value represents a biased coin; the
   * random integer is returned with this probability, otherwise the
   * index in the alias table is chosen.
   *
   * "It is a peculiar way to throw dice, but the results are
   * indistinguishable from the real thing." -Knuth (TAOCP Vol. 2;
   * 3.4.1 p.121).
   *
   * [1] Alastair J. Walker. 1977. An Efficient Method for Generating
   * Discrete Random Variables with General Distributions. ACM Trans.
   * Math. Softw. 3, 3 (September 1977), 253-256.
   * DOI=10.1145/355744.355749
   * https://doi.acm.org/10.1145/355744.355749
   *
   * Package private for testing.
   */
  private[util] case class Aliased(alias: IndexedSeq[Int], prob: IndexedSeq[Double]) extends Drv {
    require(prob.size == alias.size)
    private[this] val N = alias.size

    def apply(rng: Rng): Int = {
      val i = rng.nextInt(N)
      val p = prob(i)
      if (p == 1 || rng.nextDouble() < p) i
      else alias(i)
    }
  }

  /**
   * Generate probability and alias tables in the manner of to Vose
   * [1]. This algorithm is simple, efficient, and intuitive. Vose's
   * algorithm is O(n) in the distribution size. The paper below
   * contains correctness and complexity proofs.
   *
   * [1] Michael D. Vose. 1991. A Linear Algorithm for Generating Random
   * Numbers with a Given Distribution. IEEE Trans. Softw. Eng. 17, 9
   * (September 1991), 972-975. DOI=10.1109/32.92917
   * https://dx.doi.org/10.1109/32.92917
   *
   * Package private for testing.
   */
  private[util] def newVose(dist: Seq[Double]): Drv = {
    val N = dist.size

    val alias = new Array[Int](N)
    val prob = new Array[Double](N)

    val small = mutable.Queue[Int]()
    val large = mutable.Queue[Int]()
    val p = new Array[Double](N)
    dist.copyToArray(p, 0, N)

    @tailrec
    def fillQueues(i: Int): Unit = if (i < N) {
      p(i) *= N
      if (p(i) < 1d) small.enqueue(i)
      else large.enqueue(i)
      fillQueues(i + 1)
    }
    fillQueues(0)

    while (large.nonEmpty && small.nonEmpty) {
      val s = small.dequeue()
      val l = large.dequeue()

      prob(s) = p(s)
      alias(s) = l

      p(l) = (p(s) + p(l)) - 1d // Same as p(l)-(1-p(s)), but more stable
      if (p(l) < 1) small.enqueue(l)
      else large.enqueue(l)
    }

    while (large.nonEmpty) prob(large.dequeue()) = 1d

    while (small.nonEmpty) prob(small.dequeue()) = 1d

    Aliased(alias, prob)
  }

  /**
   * Create a new Drv representing the passed in distribution of
   * probabilities. These must add up to 1, however we cannot
   * reliably test for this due to numerical stability issues: we're
   * operating on the honor's system.
   */
  def apply(dist: Seq[Double]): Drv = {
    require(dist.nonEmpty)
    val sum = dist.sum
    if (!isNormalized(sum))
      throw new AssertionError("Bad sum %.001f".format(sum))
    newVose(dist)
  }

  /**
   * Create a probability distribution based on a set of weights
   * (ratios).
   */
  def fromWeights(weights: Seq[Double]): Drv = {
    require(weights.nonEmpty)
    val sum = weights.sum

    newVose(
      if (sum == 0d) Seq.fill(weights.size) { 1d / weights.size }
      else if (isNormalized(sum)) weights
      else weights.map(_ / sum)
    )
  }
}
