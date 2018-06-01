/*
 * This file is part of Sloth, an SMT solver for strings.
 * Copyright (C) 2018  Matthew Hague, Philipp Ruemmer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package strsolver.preprop

import strsolver.Regex2AFA

import ap.terfor.Term
import ap.terfor.preds.PredConj

import dk.brics.automaton.{BasicAutomata, BasicOperations, RegExp,
                           Automaton => BAutomaton, State => BState, Transition}

import scala.collection.JavaConversions.{asScalaIterator,
                                         iterableAsScalaIterable}
import scala.collection.mutable.{HashMap => MHashMap}

object BricsAutomaton {
  private def toBAutomaton(aut : Automaton) : BAutomaton = aut match {
    case that : BricsAutomaton =>
      that.underlying
    case _ =>
      throw new IllegalArgumentException
  }

  def apply(c : Term, context : PredConj) : BricsAutomaton = {
    val converter = new Regex2AFA(context)
    new BricsAutomaton(converter.buildBricsAut(c))
  }

  def apply() : BricsAutomaton = new BricsAutomaton(new BAutomaton)
}

/**
 * Wrapper for the BRICS automaton class
 */
class BricsAutomaton(val underlying : BAutomaton) extends Automaton {

  import BricsAutomaton.toBAutomaton

  type State = BState

  override def toString : String = underlying.toString

  /**
   * Nr. of bits of letters in the vocabulary. Letters are
   * interpreted as numbers in the range <code>[0, 2^vocabularyWidth)</code>
   */
  val vocabularyWidth : Int = 8  // really?

  /**
   * Union
   */
  def |(that : Automaton) : Automaton =
    new BricsAutomaton(BasicOperations.union(this.underlying,
                                             toBAutomaton(that)))

  /**
   * Intersection
   */
  def &(that : Automaton) : Automaton =
    new BricsAutomaton(BasicOperations.intersection(this.underlying,
                                                    toBAutomaton(that)))

  /**
   * Check whether this automaton describes the empty language.
   */
  def isEmpty : Boolean =
    underlying.isEmpty

  /**
   * Check whether the automaton accepts a given word.
   */
  def apply(word : Seq[Int]) : Boolean =
    BasicOperations.run(
      this.underlying,
      SeqCharSequence(for (c <- word.toIndexedSeq) yield c.toChar).toString)

  /**
   * Replace a-transitions with new a-transitions between pairs of states
   */
  def replaceTransitions(a : Char,
                         states : Iterator[(State, State)]) : Automaton = {
    // A \ a-transitions
    val (newAut, map) = substWithStateMap((min, max, addTran) => {
      if (min <= a && a <= max)
        if (min < a) addTran(min, (a-1).toChar)
        if (a < max) addTran((a+1).toChar, max)
      else
        addTran(min, max)
    })
    // (A \ a-transition) + {q1 -a-> q2 | (q1,q2) in box}
    for ((q1, q2) <- states)
      map(q1).addTransition(new Transition(a, a, map(q2)))

    newAut.underlying.restoreInvariant

    newAut
  }

  /**
   * Change initial and final states to s0 and sf respectively.  Returns
   * a new automaton.
   */
  def setInitAccept(s0 : State, sf : State) : Automaton = {
      // TODO: painful to copy, can we improve?
      val (newAut, map) = cloneWithStateMap
      newAut.underlying.setInitialState(map(s0))
      newAut.underlying.getAcceptStates.foreach(_.setAccept(false))
      map(sf).setAccept(true)
      newAut.underlying.restoreInvariant
      newAut
  }

  /**
   * Iterate over automaton states
   */
  def getStates : Iterator[State] = underlying.getStates.iterator

  /**
  * Apply f(q1, min, max, q2) to each transition q1 -[min,max]-> q2
  */
  def foreachTransition(f : (State, Char, Char, State) => Any) =
    for (q <- underlying.getStates; t <- q.getTransitions)
      f(q, t.getMin, t.getMax, t.getDest)

  /*
   * Get any word accepted by this automaton, or <code>None</code>
   * if the language is empty
   */
  def getAcceptedWord : Option[Seq[Int]] =
    (this.underlying getShortestExample true) match {
      case null => None
      case str  => Some(for (c <- str) yield c.toInt)
    }

  /**
   * Clone automaton, and also return a map telling how the
   * states are related
   */
  def cloneWithStateMap : (BricsAutomaton, Map[State, State]) =
    substWithStateMap((min, max, addTran) => addTran(min, max))

  /**
   * Subst aut and provides a mapping between equivalent states.
   *
   * Allows the transitions to be transformed via a function
   * transformTran(min, max, addTran), which can use addTran(newMin,
   * newMax) to replace transitions q --min, max--> q' with q --newMin,
   * newMax -> q' (can use addTran multiple times)
   *
   * @param transformTran a function processing transitions
   * @return (aut', map) where aut' is a clone of aut and map takes a
   * state of aut into its equivalent state in aut'
   */
  private def substWithStateMap(
    transformTran: (Char, Char, (Char, Char) => Unit) => Unit
  ) : (BricsAutomaton, Map[State, State]) = {
    val newAut = new BAutomaton
    val smap = new MHashMap[State, State]

    val states = underlying.getStates
    val s0 = underlying.getInitialState

    for (s <- states) {
      val p = new State
      p.setAccept(s.isAccept)
      smap += s -> p
    }
    for ((s, p) <- smap; t <- s.getTransitions)
      transformTran(t.getMin, t.getMax, (min, max) =>
        p.addTransition(new Transition(min, max, smap(t.getDest)))
      )
    newAut.setInitialState(smap(s0))
    newAut.restoreInvariant
    (new BricsAutomaton(newAut), smap.toMap)
  }
}