package com.softwaremill.macwire

import language.experimental.macros

import reflect.macros.Context
import annotation.tailrec

object MacwireMacros {
  def wire[T]: T = macro wire_impl[T]

  def wire_impl[T: c.WeakTypeTag](c: Context): c.Expr[T] = {
    import c.universe._

    def findValuesOfTypeInEnclosingClass(t: Type): List[Name] = {
      @tailrec
      def doFind(trees: List[Tree], acc: List[Name]): List[Name] = trees match {
        case Nil => acc
        case tree :: tail => tree match {
          // TODO: subtyping
          case ValDef(_, name, tpt, _) if tpt.tpe == t => doFind(tail, name.encodedName :: acc)
          case _ => doFind(tail, acc)
        }
      }

      val ClassDef(_, _, _, Template(_, _, body)) = c.enclosingClass
      doFind(body, Nil)
    }

    def findValueOfType(t: Type): Option[Name] = {
      val ClassDef(_, _, _, Template(parents, _, _)) = c.enclosingClass

      println("---")

      parents.foreach(p => {
        p match {
          case p2: Ident => {
            println("Filter " + t)
            println(p.tpe.declarations.foreach(x => {
              val TypeRef(ThisType(sym2), sym, args) = t

              // We have to replace the references to <Parent>.this with <Enclosing class>.this, as all types
              // from the Parent are inherited in Enclosing. If we want to use a value defined in Parent (it will
              // have type Parent.this.X) for a class defined in Enclosing (it will have type Enclosing.this.X),
              // we need the substitution to get a match.
              // TODO: what if the class's parameter is in one class, and the class definition in another (both inherited)?
              // x - candidate for parameter value
              // p.symbol - <Parent>
              // sym2 - <Enclosing>
              val xx = x.typeSignature.map(tt => {
                tt match {
                  case ttt: ThisType if ttt.sym == p.symbol => ThisType(sym2)
                  case _ => tt
                }
              })

              println(showRaw(p.tpe),
                "\n\t", showRaw(x.typeSignature),
                "\n\t", showRaw(p.symbol),
                "\n\t", showRaw(sym),
                "\n\t", x.typeSignature,
                //"\n\t", x.typeSignature.substituteSymbols(List(c.universe.NoSymbol), List(p.symbol)))
                "\n\t", xx
              )

              println(x.typeSignature, t, x.typeSignature =:= t, xx =:= t)
              println()
            }))
          }
          case _ => println("- " + c.universe.showRaw(p))
        }
      })

      println("---")

      // ^-- cleanup above

      val namesOpt = firstNotEmpty(
        () => findValuesOfTypeInEnclosingClass(t)
      )

      namesOpt match {
        case None => {
          c.error(c.enclosingPosition, s"Cannot find a value of type ${t}")
          None
        }
        case Some(List(name)) => Some(name)
        case Some(names) => {
          c.error(c.enclosingPosition, s"Found multiple values of type ${t}: $names")
          None
        }
      }
    }

    def createNewTargetWithParams(): Expr[T] = {
      val targetType = implicitly[c.WeakTypeTag[T]]
      val targetConstructorOpt = targetType.tpe.members.find(_.name.decoded == "<init>")
      val result = targetConstructorOpt match {
        case None => {
          c.error(c.enclosingPosition, "Cannot find constructor for " + targetType)
          reify { null.asInstanceOf[T] }
        }
        case Some(targetConstructor) => {
          val targetConstructorParams = targetConstructor.asMethod.paramss.flatten

          val newT = Select(New(Ident(targetType.tpe.typeSymbol)), nme.CONSTRUCTOR)

          val constructorParams = for (param <- targetConstructorParams) yield {
            val wireToOpt = findValueOfType(param.typeSignature).map(Ident(_))

            // If no value is found, an error has been already reported.
            wireToOpt.getOrElse(reify(null).tree)
          }

          val newTWithParams = Apply(newT, constructorParams)
          c.info(c.enclosingPosition, s"Generated code: ${c.universe.show(newTWithParams)}", force = false)
          c.Expr(newTWithParams)
        }
      }

      result
    }

    createNewTargetWithParams()
  }

  def firstNotEmpty[T](fs: (() => List[T])*): Option[List[T]] = {
    for (f <- fs) {
      val r = f()
      if (!r.isEmpty) return Some(r)
    }

    None
  }
}