package slamdata.engine.physical.mongodb

import scala.collection.immutable.{ListMap}

import com.mongodb.DBObject

import scalaz._
import Scalaz._

import slamdata.engine.{RenderTree, Terminal, NonTerminal, RenderedTree}
import slamdata.engine.fp._

sealed trait PipelineOp {
  import PipelineOp._
  import ExprOp._

  def bson: Bson.Doc

  def rewriteRefs(applyVar0: PartialFunction[DocVar, DocVar]): this.type = {
    val applyVar = (f: DocVar) => applyVar0.lift(f).getOrElse(f)

    def applyExprOp(e: ExprOp): ExprOp = e.mapUp {
      case f : DocVar => applyVar(f)
    }

    def applyFieldName(name: BsonField): BsonField = {
      applyVar(DocField(name)).deref.getOrElse(name) // TODO: Delete field if it's transformed away to nothing???
    }

    def applySelector(s: Selector): Selector = s.mapUpFields(PartialFunction(applyFieldName _))

    def applyReshape(shape: Reshape): Reshape = shape match {
      case Reshape.Doc(value) => Reshape.Doc(value.transform {
        case (k, -\/(e)) => -\/(applyExprOp(e))
        case (k, \/-(r)) => \/-(applyReshape(r))
      })

      case Reshape.Arr(value) => Reshape.Arr(value.transform {
        case (k, -\/(e)) => -\/(applyExprOp(e))
        case (k, \/-(r)) => \/-(applyReshape(r))
      })
    }

    def applyGrouped(grouped: Grouped): Grouped = Grouped(grouped.value.transform {
      case (k, groupOp) => applyExprOp(groupOp) match {
        case groupOp : GroupOp => groupOp
        case _ => sys.error("Transformation changed the type -- error!")
      }
    })

    def applyMap[A](m: ListMap[BsonField, A]): ListMap[BsonField, A] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyNel[A](m: NonEmptyList[(BsonField, A)]): NonEmptyList[(BsonField, A)] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyFindQuery(q: FindQuery): FindQuery = {
      q.copy(
        query   = applySelector(q.query),
        max     = q.max.map(applyMap _),
        min     = q.min.map(applyMap _),
        orderby = q.orderby.map(applyNel _)
      )
    }

    (this match {
      case Project(shape, xId) => Project(applyReshape(shape), xId)
      case Group(grouped, by)  => Group(applyGrouped(grouped), by.bimap(applyExprOp _, applyReshape _))
      case Match(s)            => Match(applySelector(s))
      case Redact(e)           => Redact(applyExprOp(e))
      case v @ Limit(_)        => v
      case v @ Skip(_)         => v
      case v @ Unwind(f)       => Unwind(applyVar(f))
      case v @ Sort(l)         => Sort(applyNel(l))
      case v @ Out(_)          => v
      case g : GeoNear         => g.copy(distanceField = applyFieldName(g.distanceField), query = g.query.map(applyFindQuery _))
    }).asInstanceOf[this.type]
  }
}

object PipelineOp {
  private val PipelineOpNodeType = List("PipelineOp")
  private val ProjectNodeType = List("PipelineOp", "Project")
  private val SortNodeType = List("PipelineOp", "Sort")
  private val SortKeyNodeType = List("PipelineOp", "Sort", "Key")
  
  implicit def PipelineOpRenderTree(implicit RG: RenderTree[Grouped], RS: RenderTree[Selector]) = new RenderTree[PipelineOp] {
    def render(op: PipelineOp) = op match {
      case Project(shape, xId)       =>
        NonTerminal("",
          renderReshape(shape) :+ Terminal(xId.toString, Nil),
          PipelineOpNodeType :+ "Project")
      case Group(grouped, by)        => NonTerminal("",
                                          RG.render(grouped) :: 
                                            by.fold(exprOp => Terminal(exprOp.bson.repr.toString, PipelineOpNodeType :+ "Group" :+ "By"), 
                                                    shape => NonTerminal("", renderReshape(shape), PipelineOpNodeType :+ "Group" :+ "By")) ::
                                            Nil, 
                                          PipelineOpNodeType :+ "Group")
      case Match(selector)           => NonTerminal("", RS.render(selector) :: Nil, PipelineOpNodeType :+ "Match")
      case Sort(keys)                => NonTerminal("", (keys.map { case (expr, ot) => Terminal(expr.bson.repr.toString + ", " + ot, SortKeyNodeType) } ).toList, SortNodeType)
      case Unwind(field)             => Terminal(field.bson.repr.toString, PipelineOpNodeType :+ "Unwind")
      case _                         => Terminal(op.toString, PipelineOpNodeType)
    }
  }

  private[mongodb] def renderReshape(shape: Reshape): List[RenderedTree] = {
    def renderField(field: BsonField, value: ExprOp \/ Reshape) = {
      val (label, typ) = field match {
        case BsonField.Index(value) => value.toString -> "Index"
        case _ => field.bson.repr.toString -> "Name"
      }
      value match {
        case -\/  (exprOp) => Terminal(label + " -> " + exprOp.bson.repr.toString, ProjectNodeType :+ typ)
        case  \/- (shape)  => NonTerminal(label, renderReshape(shape), ProjectNodeType :+ typ)
      }
    }

    val fields = shape match { case Reshape.Doc(map) => map; case Reshape.Arr(map) => map }
    fields.map { case (k, v) => renderField(k, v) }.toList
  }

  implicit def GroupedRenderTree = new RenderTree[Grouped] {
    val GroupedNodeType = List("Grouped")

    def render(grouped: Grouped) = NonTerminal("", 
                                    (grouped.value.map { case (name, expr) => Terminal(name.bson.repr.toString + " -> " + expr.bson.repr.toString, GroupedNodeType :+ "Name") } ).toList, 
                                    GroupedNodeType)
  }
  
  private[PipelineOp] abstract sealed class SimpleOp(op: String) extends PipelineOp {
    def rhs: Bson

    def bson = Bson.Doc(ListMap(op -> rhs))
  }

  sealed trait Reshape {
    def toDoc: Reshape.Doc
    def toJs: Js.Expr => Option[Js.Expr]

    def bson: Bson.Doc

    private def projectSeq(fs: List[BsonField.Leaf]): Option[ExprOp \/ Reshape] = fs match {
      case Nil => Some(\/- (this))
      case (x : BsonField.Leaf) :: Nil => this.project(x)
      case (x : BsonField.Leaf) :: xs => this.project(x).flatMap(_.fold(
        expr    => None,
        reshape => reshape.projectSeq(xs)
      ))
    }

    def \ (f: BsonField): Option[ExprOp \/ Reshape] = projectSeq(f.flatten)

    private def project(leaf: BsonField.Leaf): Option[ExprOp \/ Reshape] = leaf match {
      case x @ BsonField.Name(_) => projectField(x)
      case x @ BsonField.Index(_) => projectIndex(x)
    }

    private def projectField(f: BsonField.Name): Option[ExprOp \/ Reshape] = this match {
      case Reshape.Doc(m) => m.get(f)
      case Reshape.Arr(_) => None
    }

    private def projectIndex(f: BsonField.Index): Option[ExprOp \/ Reshape] = this match {
      case Reshape.Doc(_) => None
      case Reshape.Arr(m) => m.get(f)
    }

    def get(field: BsonField): Option[ExprOp \/ Reshape] = {
      def get0(cur: Reshape, els: List[BsonField.Leaf]): Option[ExprOp \/ Reshape] = els match {
        case Nil => ???
        
        case x :: Nil => cur.toDoc.value.get(x.toName)

        case x :: xs => cur.toDoc.value.get(x.toName).flatMap(_.fold(_ => None, get0(_, xs)))
      }

      get0(this, field.flatten)
    }

    def set(field: BsonField, newv: ExprOp \/ Reshape): Reshape = {
      def getOrDefault(o: Option[ExprOp \/ Reshape]): Reshape = {
        o.map(_.fold(_ => Reshape.EmptyArr, identity)).getOrElse(Reshape.EmptyArr)
      }

      def set0(cur: Reshape, els: List[BsonField.Leaf]): Reshape = els match {
        case Nil => ??? // TODO: Refactor els to be NonEmptyList

        case (x : BsonField.Name) :: Nil => Reshape.Doc(cur.toDoc.value + (x -> newv))

        case (x : BsonField.Index) :: Nil => cur match {
          case Reshape.Arr(m) => Reshape.Arr(m + (x -> newv))
          case Reshape.Doc(m) => Reshape.Doc(m + (x.toName -> newv))
        }

        case (x : BsonField.Name) :: xs => 
          val doc = cur.toDoc.value

          Reshape.Doc(doc + (x -> \/- (set0(getOrDefault(doc.get(x)), xs))))

        case (x : BsonField.Index) :: xs => cur match {
          case Reshape.Arr(m) => Reshape.Arr(m + (x -> \/- (set0(getOrDefault(m.get(x)), xs))))
          case Reshape.Doc(m) => Reshape.Doc(m + (x.toName -> \/- (set0(getOrDefault(m.get(x.toName)), xs))))
        } 
      }

      set0(this, field.flatten)
    }
  }

  object Reshape {
    val EmptyArr = Reshape.Arr(ListMap())
    val EmptyDoc = Reshape.Doc(ListMap())

    def unapply(v: Reshape): Option[Reshape] = Some(v)
    
    def getAll(r: Reshape): List[(BsonField, ExprOp)] = {
      def getAll0(f0: BsonField, e: ExprOp \/ Reshape) = e.fold(
        e => (f0 -> e) :: Nil,
        r => getAll(r).map { case (f, e) => (f0 \ f) -> e })

      r match {
        case Reshape.Arr(m) =>
          m.toList.map { case (f, e) => getAll0(f, e) }.flatten
        case Reshape.Doc(m) =>
          m.toList.map { case (f, e) => getAll0(f, e) }.flatten
      }
    }

    def setAll(r: Reshape, fvs: Iterable[(BsonField, ExprOp \/ Reshape)]) =
      fvs.foldLeft(r) {
        case (r0, (field, value)) => r0.set(field, value)
      }

    def merge(r1: Reshape, r2: Reshape): Option[Reshape] = (r1, r2) match {
      case (Reshape.Doc(_), Reshape.Doc(_)) =>
        val lmap = Reshape.getAll(r1).map(t => t._1 -> -\/ (t._2)).toListMap
        val rmap = Reshape.getAll(r2).map(t => t._1 -> -\/ (t._2)).toListMap
        if ((lmap.keySet & rmap.keySet).forall(k => lmap.get(k) == rmap.get(k)))
          Some(Reshape.setAll(
            r1,
            Reshape.getAll(r2).map(t => t._1 -> -\/ (t._2))))
        else None
      // TODO: Attempt to merge Arr+Arr as well
      case _ => None
    }

    case class Doc(value: ListMap[BsonField.Name, ExprOp \/ Reshape]) extends Reshape {
      def bson: Bson.Doc = Bson.Doc(value.map {
        case (field, either) => field.asText -> either.fold(_.bson, _.bson)
      })

      def toDoc = this
      def toJs = {
        base =>
          value.toList.map { case (key, expr) =>
            expr.fold(ExprOp.toJs(_)(base), _.toJs(base)).map(Js.BinOp("=", key.toJs(Js.Ident("rez")), _))
          }.sequence.map(x => Js.BlockExpr(None, Js.VarDef(List("rez" -> Js.AnonObjDecl(Nil))) +: x, Js.Ident("rez")))
      }

      override def toString = s"Reshape.Doc(List$value)"
    }
    case class Arr(value: ListMap[BsonField.Index, ExprOp \/ Reshape]) extends Reshape {      
      def bson: Bson.Doc = Bson.Doc(value.map {
        case (field, either) => field.asText -> either.fold(_.bson, _.bson)
      })

      def minIndex: Option[Int] = {
        val keys = value.keys

        keys.headOption.map(_ => keys.map(_.value).min)
      }

      def maxIndex: Option[Int] = {
        val keys = value.keys

        keys.headOption.map(_ => keys.map(_.value).max)
      }

      def offset(i0: Int) = Reshape.Arr(value.map {
        case (BsonField.Index(i), v) => BsonField.Index(i0 + i) -> v
      })

      def toDoc: Doc = Doc(value.map(t => t._1.toName -> t._2))
      def toJs = base =>
        value.toList.map { case (key, expr) =>
          expr.fold(ExprOp.toJs(_)(base), _.toJs(base)).map(Js.BinOp("=", key.toJs(base), _))
        }.sequence.map(x => Js.BlockExpr(None, x, base))

      // def flatten: (Map[BsonField.Index, ExprOp], Reshape.Arr)

      override def toString = s"Reshape.Arr(List$value)"
    }
    implicit val ReshapeMonoid = new Monoid[Reshape] {
      def zero = Reshape.Arr(ListMap.empty)

      def append(v10: Reshape, v20: => Reshape): Reshape = {
        val v1 = v10.toDoc
        val v2 = v20.toDoc

        val m1 = v1.value
        val m2 = v2.value
        val keys = m1.keySet ++ m2.keySet

        Reshape.Doc(keys.foldLeft(ListMap.empty[BsonField.Name, ExprOp \/ Reshape]) {
          case (map, key) =>
            val left  = m1.get(key)
            val right = m2.get(key)

            val result = ((left |@| right) {
              case (-\/(e1), -\/(e2)) => -\/ (e2)
              case (-\/(e1), \/-(r2)) => \/- (r2)
              case (\/-(r1), \/-(r2)) => \/- (append(r1, r2))
              case (\/-(r1), -\/(e2)) => -\/ (e2)
            }) orElse (left) orElse (right)

            map + (key -> result.get)
        })
      }
    }
  }

  case class Grouped(value: ListMap[BsonField.Leaf, ExprOp.GroupOp]) {
    type LeafMap[V] = ListMap[BsonField.Leaf, V]
    
    def bson = Bson.Doc(value.map(t => t._1.asText -> t._2.bson))

    override def toString = s"Grouped(List$value)"
  }

  case class Project(shape: Reshape, idx: IdHandling)
      extends SimpleOp("$project") {
    def rhs = idx match {
      case IdHandling.ExcludeId =>
        Bson.Doc(shape.bson.value + (WorkflowOp.IdLabel -> Bson.Bool(false)))
      case _         => shape.bson
    }

    def empty: Project = shape match {
      case Reshape.Doc(_) => Project.EmptyDoc

      case Reshape.Arr(_) => Project.EmptyArr
    }

    def set(field: BsonField, value: ExprOp \/ Reshape): Project =
      Project(
        shape.set(field, value),
        if (field == WorkflowOp.IdName) IdHandling.IncludeId else idx)

    def getAll: List[(BsonField, ExprOp)] = Reshape.getAll(shape)

    def get(ref: ExprOp.DocVar): Option[ExprOp \/ Reshape] = ref match {
      case ExprOp.DocVar(_, Some(field)) => shape.get(field)
      case _ => Some(\/- (shape))
    }

    def setAll(fvs: Iterable[(BsonField, ExprOp \/ Reshape)]): Project =
      Project(
        Reshape.setAll(shape, fvs),
        if (fvs.exists(_._1 == WorkflowOp.IdName))
          IdHandling.IncludeId
        else idx)

    def deleteAll(fields: List[BsonField]): Project = {
      empty.setAll(getAll.filterNot(t => fields.exists(t._1.startsWith(_))).map(t => t._1 -> -\/ (t._2)))
    }

    def id: Project = {
      def loop(prefix: Option[BsonField], p: Project): Project = {
        def nest(child: BsonField): BsonField =
          prefix.map(_ \ child).getOrElse(child)

        Project(
          p.shape match {
            case Reshape.Doc(m) =>
              Reshape.Doc(
                m.transform {
                  case (k, v) =>
                    v.fold(
                      _ => -\/  (ExprOp.DocVar.ROOT(nest(k))),
                      r =>  \/- (loop(Some(nest(k)), Project(r, p.idx)).shape))
                })
            case Reshape.Arr(m) =>
              Reshape.Arr(
                m.transform {
                  case (k, v) =>
                    v.fold(
                      _ => -\/  (ExprOp.DocVar.ROOT(nest(k))),
                      r =>  \/- (loop(Some(nest(k)), Project(r, p.idx)).shape))
                })
          },
          p.idx)
      }

      loop(None, this)
    }
  }
  object Project {
    import ExprOp.DocVar

    val EmptyDoc = Project(Reshape.EmptyDoc, IdHandling.IgnoreId)
    val EmptyArr = Project(Reshape.EmptyArr, IdHandling.IgnoreId)
  }
  case class Match(selector: Selector) extends SimpleOp("$match") {
    def rhs = selector.bson
  }
  case class Redact(value: ExprOp) extends SimpleOp("$redact") {
    def rhs = value.bson

    def fields: List[ExprOp.DocVar] = {
      import scalaz.std.list._

      ExprOp.foldMap({
        case f : ExprOp.DocVar => f :: Nil
      })(value)
    }
  }

  object Redact {
    val DESCEND = ExprOp.DocVar(ExprOp.DocVar.Name("DESCEND"),  None)
    val PRUNE   = ExprOp.DocVar(ExprOp.DocVar.Name("PRUNE"),    None)
    val KEEP    = ExprOp.DocVar(ExprOp.DocVar.Name("KEEP"),     None)
  }
  
  case class Limit(value: Long) extends SimpleOp("$limit") {
    def rhs = Bson.Int64(value)
  }
  case class Skip(value: Long) extends SimpleOp("$skip") {
    def rhs = Bson.Int64(value)
  }
  case class Unwind(field: ExprOp.DocVar) extends SimpleOp("$unwind") {
    def rhs = field.bson
  }
  case class Group(grouped: Grouped, by: ExprOp \/ Reshape) extends SimpleOp("$group") {
    import ExprOp.{DocVar, GroupOp}

    def toProject: Project = grouped.value.foldLeft(Project.EmptyArr) {
      case (p, (f, v)) => p.set(f, -\/ (v))
    }

    def empty = copy(grouped = Grouped(ListMap()))

    def getAll: List[(BsonField.Leaf, GroupOp)] = grouped.value.toList

    def set(field: BsonField.Leaf, value: GroupOp): Group = {
      copy(grouped = Grouped(grouped.value + (field -> value)))
    }

    def deleteAll(fields: List[BsonField.Leaf]): Group = {
      empty.setAll(getAll.filterNot(t => fields.exists(t._1 == _)))
    }

    def setAll(vs: Seq[(BsonField.Leaf, GroupOp)]) = copy(grouped = Grouped(ListMap(vs: _*)))

    def get(ref: DocVar): Option[ExprOp \/ Reshape] = ref match {
      case DocVar(_, Some(name)) => name.flatten match {
        case x :: Nil => grouped.value.get(x).map(-\/ apply)
        case _ => None
      }

      case _ => Some(\/- (Reshape.Doc(grouped.value.map { case (leaf, expr) => leaf.toName -> -\/ (expr) })))
    }

    def rhs = {
      val Bson.Doc(m) = grouped.bson

      Bson.Doc(m + (WorkflowOp.IdLabel -> by.fold(_.bson, _.bson)))
    }
  }
  case class Sort(value: NonEmptyList[(BsonField, SortType)]) extends SimpleOp("$sort") {
    // Note: ListMap preserves the order of entries.
    def rhs = Bson.Doc(ListMap((value.map { case (k, t) => k.asText -> t.bson }).list: _*))
    
    override def toString = "Sort(NonEmptyList(" + value.map(t => t._1.toString + " -> " + t._2).list.mkString(", ") + "))"
  }
  case class Out(collection: Collection) extends SimpleOp("$out") {
    def rhs = Bson.Text(collection.name)
  }
  case class GeoNear(near: (Double, Double), distanceField: BsonField, 
                     limit: Option[Int], maxDistance: Option[Double],
                     query: Option[FindQuery], spherical: Option[Boolean],
                     distanceMultiplier: Option[Double], includeLocs: Option[BsonField],
                     uniqueDocs: Option[Boolean]) extends SimpleOp("$geoNear") {

    def rhs = Bson.Doc(List(
      List("near"           -> Bson.Arr(Bson.Dec(near._1) :: Bson.Dec(near._2) :: Nil)),
      List("distanceField"  -> distanceField.bson),
      limit.toList.map(limit => "limit" -> Bson.Int32(limit)),
      maxDistance.toList.map(maxDistance => "maxDistance" -> Bson.Dec(maxDistance)),
      query.toList.map(query => "query" -> query.bson),
      spherical.toList.map(spherical => "spherical" -> Bson.Bool(spherical)),
      distanceMultiplier.toList.map(distanceMultiplier => "distanceMultiplier" -> Bson.Dec(distanceMultiplier)),
      includeLocs.toList.map(includeLocs => "includeLocs" -> includeLocs.bson),
      uniqueDocs.toList.map(uniqueDocs => "uniqueDocs" -> Bson.Bool(uniqueDocs))
    ).flatten.toListMap)
  }
}
