/* Scala.js compiler
 * Copyright 2013 LAMP/EPFL
 * @author  Sébastien Doeraene
 */

package org.scalajs.core.compiler

import scala.tools.nsc._

import scala.collection.mutable

import org.scalajs.core.ir.Trees.JSNativeLoadSpec

/** Additions to Global meaningful for the JavaScript backend
 *
 *  @author Sébastien Doeraene
 */
trait JSGlobalAddons extends JSDefinitions
                        with Compat210Component {
  val global: Global

  import global._
  import jsDefinitions._
  import definitions._

  /** JavaScript primitives, used in jscode */
  object jsPrimitives extends JSPrimitives { // scalastyle:ignore
    val global: JSGlobalAddons.this.global.type = JSGlobalAddons.this.global
    val jsAddons: ThisJSGlobalAddons =
      JSGlobalAddons.this.asInstanceOf[ThisJSGlobalAddons]
  }

  /** global javascript interop related helpers */
  object jsInterop { // scalastyle:ignore
    import scala.reflect.NameTransformer
    import scala.reflect.internal.Flags

    /** Symbols of constructors and modules that are to be exported */
    private val exportedSymbols =
      mutable.Map.empty[Symbol, List[ExportInfo]]

    /** JS native load specs of the symbols in the current compilation run. */
    private val jsNativeLoadSpecs =
      mutable.Map.empty[Symbol, JSNativeLoadSpec]

    private val exportPrefix = "$js$exported$"
    private val methodExportPrefix = exportPrefix + "meth$"
    private val propExportPrefix = exportPrefix + "prop$"

    trait ExportInfo {
      val jsName: String
      val pos: Position
      val isNamed: Boolean
    }

    def clearGlobalState(): Unit = {
      exportedSymbols.clear()
      jsNativeLoadSpecs.clear()
    }

    private def assertValidForRegistration(sym: Symbol): Unit = {
      assert(sym.isConstructor || sym.isClass,
          "Can only register constructors or classes for export")
    }

    def registerForExport(sym: Symbol, infos: List[ExportInfo]): Unit = {
      assert(!exportedSymbols.contains(sym), "Same symbol exported twice")
      assertValidForRegistration(sym)
      exportedSymbols.put(sym, infos)
    }

    def registeredExportsOf(sym: Symbol): List[ExportInfo] = {
      assertValidForRegistration(sym)
      exportedSymbols.getOrElse(sym, Nil)
    }

    /** creates a name for an export specification */
    def scalaExportName(jsName: String, isProp: Boolean): TermName = {
      val pref = if (isProp) propExportPrefix else methodExportPrefix
      val encname = NameTransformer.encode(jsName)
      newTermName(pref + encname)
    }

    /** checks if the given symbol is a JSExport */
    def isExport(sym: Symbol): Boolean =
      sym.unexpandedName.startsWith(exportPrefix) &&
      !sym.hasFlag(Flags.DEFAULTPARAM)

    /** retrieves the originally assigned jsName of this export and whether it
     *  is a property
     */
    def jsExportInfo(name: Name): (String, Boolean) = {
      def dropPrefix(prefix: String) ={
        if (name.startsWith(prefix)) {
          // We can't decode right away due to $ separators
          val enc = name.encoded.substring(prefix.length)
          Some(NameTransformer.decode(enc))
        } else None
      }

      dropPrefix(methodExportPrefix).map((_,false)) orElse
      dropPrefix(propExportPrefix).map((_,true)) getOrElse
      sys.error("non-exported name passed to jsInfoSpec")
    }

    def isJSProperty(sym: Symbol): Boolean = isJSGetter(sym) || isJSSetter(sym)

    @inline private def enteringUncurryIfAtPhaseAfter[A](op: => A): A = {
      if (currentRun.uncurryPhase != NoPhase &&
          isAtPhaseAfter(currentRun.uncurryPhase)) {
        enteringPhase(currentRun.uncurryPhase)(op)
      } else {
        op
      }
    }

    /** has this symbol to be translated into a JS getter (both directions)? */
    def isJSGetter(sym: Symbol): Boolean = {
      /* We only get here when `sym.isMethod`, thus `sym.isModule` implies that
       * `sym` is the module's accessor. In 2.12, module accessors are synthesized
       * after uncurry, thus their first info is a MethodType at phase fields.
       */
      sym.isModule || (sym.tpe.params.isEmpty && enteringUncurryIfAtPhaseAfter {
        sym.tpe match {
          case _: NullaryMethodType              => true
          case PolyType(_, _: NullaryMethodType) => true
          case _                                 => false
        }
      })
    }

    /** has this symbol to be translated into a JS setter (both directions)? */
    def isJSSetter(sym: Symbol): Boolean =
      nme.isSetterName(sym.name) && sym.isMethod && !sym.isConstructor

    /** has this symbol to be translated into a JS bracket access (JS to Scala) */
    def isJSBracketAccess(sym: Symbol): Boolean =
      sym.hasAnnotation(JSBracketAccessAnnotation)

    /** has this symbol to be translated into a JS bracket call (JS to Scala) */
    def isJSBracketCall(sym: Symbol): Boolean =
      sym.hasAnnotation(JSBracketCallAnnotation)

    /** Gets the unqualified JS name of a symbol.
     *
     *  If it is not explicitly specified with an `@JSName` annotation, the
     *  JS name is inferred from the Scala name.
     */
    def jsNameOf(sym: Symbol): String = {
      sym.getAnnotation(JSNameAnnotation).flatMap(_.stringArg(0)) getOrElse {
        val base = sym.unexpandedName.decoded.stripSuffix("_=")
        if (!sym.isMethod) base.stripSuffix(" ")
        else base
      }
    }

    /** Gets the fully qualified JS name of a static module Symbol compiled
     *  with the 0.6.8 binary format or earlier.
     */
    def compat068FullJSNameOf(sym: Symbol): String = {
      assert(sym.isModuleClass,
          s"compat068FullJSNameOf called for non-module-class symbol $sym")
      sym.getAnnotation(JSFullNameAnnotation).flatMap(_.stringArg(0)) getOrElse {
        jsNameOf(sym)
      }
    }

    /** Stores the JS native load spec of a symbol for the current compilation
     *  run.
     */
    def storeJSNativeLoadSpec(sym: Symbol, spec: JSNativeLoadSpec): Unit = {
      assert(sym.isClass,
          s"storeJSNativeLoadSpec called for non-class symbol $sym")

      jsNativeLoadSpecs(sym) = spec
    }

    /** Gets the JS native load spec of a symbol in the current compilation run.
     */
    def jsNativeLoadSpecOf(sym: Symbol): JSNativeLoadSpec = {
      assert(sym.isClass,
          s"jsNativeLoadSpecOf called for non-class symbol $sym")

      jsNativeLoadSpecs(sym)
    }

  }

}
