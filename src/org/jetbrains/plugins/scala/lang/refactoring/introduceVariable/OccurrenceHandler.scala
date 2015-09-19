package org.jetbrains.plugins.scala
package lang
package refactoring
package introduceVariable

import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.refactoring.scopeSuggester.ScopeItem


/**
 * Created by Kate Ustyuzhanina on 8/18/15.
 */
object OccurrenceHandler {
  def apply(typeElement: ScTypeElement,
            usualOccurrence: Array[ScTypeElement], isReplaceAllUsual: Boolean): OccurrenceHandler = {
    new OccurrenceHandler(typeElement, usualOccurrence, isReplaceAllUsual,
      Array[ScTypeElement](), false, Array[ScTypeElement](), false)
  }

  def apply(typeElement: ScTypeElement, isReplaceAllUsual: Boolean, isReplaceOccurrenceIncompanionObject: Boolean,
            isReplaceOccurrenceInInheritors: Boolean, scopeItem: ScopeItem): OccurrenceHandler  = {
    new OccurrenceHandler(typeElement, scopeItem.occurrences, isReplaceAllUsual, scopeItem.occInCompanionObj,
      isReplaceOccurrenceIncompanionObject, scopeItem.occurrencesFromInheretors, isReplaceOccurrenceInInheritors)
  }
}

class OccurrenceHandler(typeElement: ScTypeElement, usualOccurrence: Array[ScTypeElement], isReplaceAllUsual: Boolean,
                        companiomObjOccurrence: Array[ScTypeElement], isReplaceInCompanion: Boolean,
                        extendedClassOccurrence: Array[ScTypeElement], isReplaceInExtendedClasses: Boolean) {
  def getUsualOccurrences = {
    if (isReplaceAllUsual) {
      usualOccurrence
    } else {
      Array(typeElement)
    }
  }

  def getCompanionObjOccurrences = {
    getOccurrences(companiomObjOccurrence, isReplaceInCompanion)
  }

  def getExtendedOccurrences = {
    getOccurrences(extendedClassOccurrence, isReplaceInExtendedClasses)
  }

  def getOccurrencesCount = {
    usualOccurrence.length + companiomObjOccurrence.length + extendedClassOccurrence.length
  }

  def getAllOccurrences = {
    usualOccurrence ++ companiomObjOccurrence ++ extendedClassOccurrence
  }

  private def getOccurrences(occ: Array[ScTypeElement], needAll: Boolean): Array[ScTypeElement] = {
    if (needAll) {
      occ
    } else {
      Array[ScTypeElement]()
    }
  }
}