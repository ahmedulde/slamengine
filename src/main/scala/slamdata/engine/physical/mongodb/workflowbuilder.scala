package slamdata.engine.physical.mongodb

import collection.immutable.ListMap

import slamdata.engine.fp._
import slamdata.engine.fs.Path
import slamdata.engine.{Error, RenderTree, Terminal, NonTerminal}
import WorkflowTask._

import scalaz._
import Scalaz._
import monocle.Macro._
import monocle.syntax._

sealed trait WorkflowBuilderError extends Error
object WorkflowBuilderError {
  case object CouldNotPatchRoot extends WorkflowBuilderError {
    def message = "Could not patch ROOT"
  }
  case object CannotObjectConcatExpr extends WorkflowBuilderError {
    def message = "Cannot object concat an expression"
  }
  case object CannotArrayConcatExpr extends WorkflowBuilderError {
    def message = "Cannot array concat an expression"
  }
  case object NotGrouped extends WorkflowBuilderError {
    def message = "The pipeline builder has not been grouped by another set, so a group op doesn't make sense"
  }
  case class InvalidGroup(op: WorkflowOp) extends WorkflowBuilderError {
    def message = "Can not group " + op
  }
  case object InvalidSortBy extends WorkflowBuilderError {
    def message = "The sort by set has an invalid structure"
  }
  case object UnknownStructure extends WorkflowBuilderError {
    def message = "The structure is unknown due to a missing project or group operation"
  }
  case class UnsupportedDistinct(message: String) extends WorkflowBuilderError
}

/**
 * A `WorkflowBuilder` consists of a graph of operations, a structure, and a
 * base mod for that structure.
 */
final case class WorkflowBuilder private (
  graph: WorkflowOp,
  base: ExprOp.DocVar,
  struct: SchemaChange,
  groupBy: List[WorkflowBuilder] = Nil) { self =>
  import WorkflowBuilder._
  import WorkflowOp._
  import PipelineOp._
  import ExprOp.{DocVar}

  def build: Error \/ Workflow = base match {
    case DocVar.ROOT(None) => \/-(graph.finish)
    case base =>
      copy(graph = struct.shift(graph, base), base = DocVar.ROOT()).build
  }

  def asLiteral = asExprOp.collect { case (x @ ExprOp.Literal(_)) => x }

  def expr1(f: DocVar => Error \/ ExprOp): Error \/ WorkflowBuilder =
    f(base).map { expr =>
      val that = WorkflowBuilder.fromExpr(graph, expr)
      copy(graph = that.graph, base = that.base)
    }

  def expr2(that: WorkflowBuilder)(f: (DocVar, DocVar) => Error \/ ExprOp):
      Error \/ WorkflowBuilder = {
    this.merge(that) { (lbase, rbase, list) =>
      f(lbase, rbase).flatMap {
        case DocVar.ROOT(None) => \/-((this applyLens _graph).set(list))
        case expr =>
          mergeGroups(this.groupBy, that.groupBy).map { mergedGroups =>
            WorkflowBuilder(
              ProjectOp(list, Reshape.Doc(ListMap(ExprName -> -\/ (expr)))).coalesce,
              ExprVar,
              SchemaChange.Init,
              mergedGroups)
          }
      }
    }
  }

  def expr3(p2: WorkflowBuilder, p3: WorkflowBuilder)(f: (DocVar, DocVar, DocVar) => Error \/ ExprOp): Error \/ WorkflowBuilder = {
    val nest = (lbase: DocVar, rbase: DocVar, list: WorkflowOp) => {
      mergeGroups(this.groupBy, p2.groupBy, p3.groupBy).map { mergedGroups =>
        WorkflowBuilder(
          ProjectOp(list, Reshape.Doc(ListMap(LeftName -> -\/ (lbase), RightName -> -\/ (rbase)))).coalesce,
          DocVar.ROOT(),
          SchemaChange.Init,
          mergedGroups)
      }
    }

    for {
      p12    <- this.merge(p2)(nest)
      p123   <- p12.merge(p3)(nest)
      pfinal <- p123.expr1 { root =>
        f(root \ LeftName \ LeftName, root \ LeftName \ RightName, root \ RightName)
      }
    } yield pfinal
  }

  def makeObject(name: String): Error \/ WorkflowBuilder =
    asExprOp.collect {
      case x : ExprOp.GroupOp => applyGroupBy(x, name)
    }.getOrElse {
      \/- (copy(
              graph = ProjectOp(graph, Reshape.Doc(ListMap(BsonField.Name(name) -> -\/ (base)))).coalesce,
              base = DocVar.ROOT(),
              struct = struct.makeObject(name)))
    }
  
  private def applyGroupBy(expr: ExprOp.GroupOp, name: String): Error \/ WorkflowBuilder =
    groupBy match {
      case Nil => -\/(WorkflowBuilderError.NotGrouped)

      case b :: bs =>
        val (construct, inner) = ExprOp.GroupOp.decon(expr)

        graph match {
          case me: WPipelineOp =>
            val rewritten =
              copy(
                graph = ProjectOp(me.src, Reshape.Doc(ListMap(ExprName -> -\/(inner)))).coalesce)

            rewritten.merge(b) { (grouped, by, list) =>
              \/- (WorkflowBuilder(
                GroupOp(list, Grouped(ListMap(BsonField.Name(name) -> construct(grouped))), -\/ (by)).coalesce,
                DocVar.ROOT(),
                self.struct.makeObject(name),
                bs))
            }
          case _ => -\/(WorkflowBuilderError.InvalidGroup(graph))
        }
    }

  def makeArray: WorkflowBuilder = {
    copy(
      graph = ProjectOp(graph, Reshape.Arr(ListMap(BsonField.Index(0) -> -\/ (base)))).coalesce,
      base = DocVar.ROOT(),
      struct = struct.makeArray(0))
  }

  def objectConcat(that: WorkflowBuilder): Error \/ WorkflowBuilder = {
    import SchemaChange._

    def copyOneField(key: Js.Expr, expr: Js.Expr) =
      Js.BinOp("=", Js.Access(Js.Ident("rez"), key), expr)
    def copyAllFields(expr: Js.Expr) =
      Js.ForIn(Js.Ident("attr"), expr,
        Js.If(
          Js.Call(Js.Select(expr, "hasOwnProperty"), List(Js.Ident("attr"))),
          copyOneField(Js.Ident("attr"), Js.Access(expr, Js.Ident("attr"))),
          None))
    def mergeUnknownSchemas(entries: List[Js.Stmt]) =
      Js.Call(
        Js.Select(
          Js.AnonFunDecl(List("rez"), entries :+ Js.Return(Js.Ident("rez"))),
          "apply"),
        List(Js.Ident("this"), Js.AnonElem(List(Js.AnonObjDecl(Nil)))))
    val jsBase = Js.Ident("this")

    this.merge(that) { (left, right, list) =>
      mergeGroups(this.groupBy, that.groupBy).flatMap { mergedGroups =>
        def builderWithUnknowns(src: WorkflowOp, fields: List[Js.Stmt]) =
          WorkflowBuilder(
            MapOp(src,
              MapOp.mapMap(mergeUnknownSchemas(fields))).coalesce,
            ExprVar,
            Init,
            mergedGroups)

        (this.struct.simplify, that.struct.simplify) match {
          case (MakeObject(m1), MakeObject(m2)) =>
            def convert(root: DocVar) = (keys: Seq[String]) =>
              keys.map(BsonField.Name.apply).map(name => name -> -\/ (root \ name)): Seq[(BsonField.Name, ExprOp \/ Reshape)]

            val leftTuples  = convert(left)(m1.keys.toSeq)
            val rightTuples = convert(right)(m2.keys.toSeq)
            \/-(WorkflowBuilder(
              ProjectOp(list,
                Reshape.Doc(ListMap((leftTuples ++ rightTuples): _*))).coalesce,
              DocVar.ROOT(),
              MakeObject(m1 ++ m2),
              mergedGroups))
          case (Init, MakeObject(m)) =>
            \/-(builderWithUnknowns(
              list,
              List(copyAllFields((fromDocVar(left)).toJs(jsBase))) ++
                m.toList.map { case (k, v) =>
                  copyOneField(Js.Str(k), (fromDocVar(right \ BsonField.Name(k))).toJs(jsBase))
                }))
          case (MakeObject(m), Init) =>
            \/-(builderWithUnknowns(
              list,
              m.toList.map { case (k, v) =>
                copyOneField(Js.Str(k), (fromDocVar(left \ BsonField.Name(k))).toJs(jsBase))
              } ++
                List(copyAllFields((fromDocVar(right)).toJs(jsBase)))))
          case (Init, Init) =>
            \/-(builderWithUnknowns(
              list,
              List(
                copyAllFields((fromDocVar(left)).toJs(jsBase)),
                copyAllFields((fromDocVar(right)).toJs(jsBase)))))
          case (l @ FieldProject(s1, f1), r @ FieldProject(s2, f2)) =>
            def convert(root: DocVar) = (keys: Seq[String]) =>
              keys.map(BsonField.Name.apply).map(name => name -> -\/(root)): Seq[(BsonField.Name, ExprOp \/ Reshape)]

            val leftTuples  = convert(left)(List(f1))
            val rightTuples = convert(right)(List(f2))
            \/-(builderWithUnknowns(
              ProjectOp(list,
                Reshape.Doc(ListMap((leftTuples ++ rightTuples): _*))),
              List(
                copyAllFields(l.toJs(jsBase)),
                copyAllFields(r.toJs(jsBase)))))
          case _ => -\/(WorkflowBuilderError.CannotObjectConcatExpr)
        }
      }
    }
  }

  def arrayConcat(that: WorkflowBuilder): Error \/ WorkflowBuilder = {
    (this.struct.simplify, that.struct.simplify) match {
      case (s1 @ SchemaChange.MakeArray(m1), s2 @ SchemaChange.MakeArray(m2)) =>
        def convert(root: DocVar) = (shift: Int, keys: Seq[Int]) => 
          (keys.map { index => 
            BsonField.Index(index + shift) -> -\/ (root \ BsonField.Index(index))
          }): Seq[(BsonField.Index, ExprOp \/ Reshape)]

        this.merge(that) { (left, right, list) =>
          val rightShift = m1.keys.max + 1
          val leftTuples  = convert(left)(0, m1.keys.toSeq)
          val rightTuples = convert(right)(rightShift, m2.keys.toSeq)

          mergeGroups(this.groupBy, that.groupBy).map { mergedGroups =>
            WorkflowBuilder(
              ProjectOp(list, Reshape.Arr(ListMap((leftTuples ++ rightTuples): _*))).coalesce,
              DocVar.ROOT(),
              SchemaChange.MakeArray(m1 ++ m2.map(t => (t._1 + rightShift) -> t._2)),
              mergedGroups)
          }
        }

      // TODO: Here's where we'd handle Init case

      case _ => -\/ (WorkflowBuilderError.CannotObjectConcatExpr)
    }
  }

  def flattenObject: WorkflowBuilder = {
    val field = base.toJs
    copy(
      graph = MapReduceOp(graph,
        MapReduce(
          Js.AnonFunDecl(Nil,
            List(
              Js.ForIn(Js.Ident("attr"), field,
                Js.Call(Js.Ident("emit"),
                  List(
                    Js.Call(Js.Ident("ObjectId"), Nil),
                    Js.Access(field, Js.Ident("attr"))))))),
          MapReduce.reduceNOP)),
      base = ExprVar)
  }

  def flattenArray: WorkflowBuilder =
    copy(graph = UnwindOp(graph, base).coalesce)

  def projectField(name: String): WorkflowBuilder =
    copy(
      graph = ProjectOp(graph, Reshape.Doc(ListMap(ExprName -> -\/ (base \ BsonField.Name(name))))).coalesce,
      base = ExprVar,
      struct = struct.projectField(name))
    
  def projectIndex(index: Int): WorkflowBuilder =
    copy(
      // TODO: Replace the map/reduce with this projection when
      //       https://jira.mongodb.org/browse/SERVER-4589 is fixed
      // graph = ProjectOp(graph, Reshape.Doc(ListMap(
      //   ExprName -> -\/ (base \ BsonField.Index(index))))).coalesce,
      graph = MapOp(graph,
        Js.AnonFunDecl(List("key"),
          List(
            Js.Return(Js.AnonElem(List(
              Js.Ident("key"),
              Js.Access(
                Js.Select(Js.Ident("this"), ExprLabel),
                Js.Num(index, false)))))))).coalesce,
      base = ExprVar,
      struct = struct.projectIndex(index))

  def isGrouped = !groupBy.isEmpty

  def groupBy(that: WorkflowBuilder): WorkflowBuilder = {
    copy(groupBy = that :: groupBy)
  }

  def reduce(f: ExprOp => ExprOp.GroupOp): Error \/ WorkflowBuilder = {
    // TODO: Currently we cheat and defer grouping until we makeObject / 
    //       makeArray. Alas that's not guaranteed and we should find a 
    //       more reliable way.
    expr1(e => \/-(f(e)))
  }

  def sortBy(that: WorkflowBuilder, sortTypes: List[SortType]):
      Error \/ WorkflowBuilder = {
    this.merge(that) { (sort, by, list) =>
      (that.struct.simplify, by) match {
        case (SchemaChange.MakeArray(els), DocVar(_, Some(by))) =>
          if (els.size != sortTypes.length) -\/ (WorkflowBuilderError.InvalidSortBy)
          else {
            val sortFields = (els.zip(sortTypes).foldLeft(List.empty[(BsonField, SortType)]) {
              case (acc, ((idx, s), sortType)) =>
                val index = BsonField.Index(idx)

                val key: BsonField = by \ index \ BsonField.Name("key")

                (key -> sortType) :: acc
            }).reverse

            sortFields match {
              case Nil => -\/ (WorkflowBuilderError.InvalidSortBy)

              case x :: xs => 
                mergeGroups(this.groupBy, that.groupBy).map { mergedGroups =>
                  WorkflowBuilder(
                    SortOp(list, NonEmptyList.nel(x, xs)),
                    sort,
                    self.struct,
                    mergedGroups)
                }
            }
          }

        case _ => -\/ (WorkflowBuilderError.InvalidSortBy)
      }
    }
  }

  def join(that: WorkflowBuilder,
    tpe: slamdata.engine.LogicalPlan.JoinType,
    leftKey: ExprOp, rightKey: Js.Expr):
      WorkflowBuilder = {

    import slamdata.engine.LogicalPlan.JoinType
    import slamdata.engine.LogicalPlan.JoinType._
    import Js._
    import PipelineOp._

    val joinOnField: BsonField.Name = BsonField.Name("joinOn")
    val leftField: BsonField.Name = BsonField.Name("left")
    val rightField: BsonField.Name = BsonField.Name("right")
    val nonEmpty: Selector.SelectorExpr = Selector.NotExpr(Selector.Size(0))

    def padEmpty(side: BsonField): ExprOp =
      ExprOp.Cond(
        ExprOp.Eq(
          ExprOp.Size(ExprOp.DocField(side)),
          ExprOp.Literal(Bson.Int32(0))),
        ExprOp.Literal(Bson.Arr(List(Bson.Doc(ListMap())))),
        ExprOp.DocField(side))

    def buildProjection(src: WorkflowOp, l: ExprOp, r: ExprOp): WorkflowOp =
      chain(src,
        ProjectOp(_, Reshape.Doc(ListMap(
          leftField -> -\/(l),
          rightField -> -\/(r)))),
        ProjectOp(_, Reshape.Doc(ListMap(
          ExprName -> -\/(ExprOp.DocVar(ExprOp.DocVar.ROOT, None))))))

    def buildJoin(src: WorkflowOp, tpe: JoinType): WorkflowOp =
      tpe match {
        case FullOuter => 
          buildProjection(src, padEmpty(ExprName \ leftField), padEmpty(ExprName \ rightField))
        case LeftOuter =>           
          buildProjection(
            MatchOp(src, Selector.Doc(ListMap(ExprName \ leftField -> nonEmpty))),
            ExprOp.DocField(ExprName \ leftField), padEmpty(ExprName \ rightField))
        case RightOuter =>           
          buildProjection(
            MatchOp(src, Selector.Doc(ListMap(ExprName \ rightField -> nonEmpty))),
            padEmpty(ExprName \ leftField), ExprOp.DocField(ExprName \ rightField))
        case Inner =>
          MatchOp(
            src,
            Selector.Doc(ListMap(
              ExprName \ leftField -> nonEmpty,
              ExprName \ rightField -> nonEmpty)))
      }

    def rightMap(keyExpr: Expr): AnonFunDecl =
      MapOp.mapKeyVal(
        keyExpr,
        AnonObjDecl(List(
          ("left", AnonElem(Nil)),
          ("right", AnonElem(List(Ident("this")))))))

    val rightReduce =
      AnonFunDecl(List("key", "values"),
        List(
          VarDef(List(("result",
            AnonObjDecl(List(
              ("left", AnonElem(Nil)),
              ("right", AnonElem(Nil))))))),
          Call(Select(Ident("values"), "forEach"),
            List(AnonFunDecl(List("value"),
              // TODO: replace concat here with a more efficient operation
              //      (push or unshift)
              List(
                BinOp("=",
                  Select(Ident("result"), "left"),
                  Call(Select(Select(Ident("result"), "left"), "concat"),
                    List(Select(Ident("value"), "left")))),
                BinOp("=",
                  Select(Ident("result"), "right"),
                  Call(Select(Select(Ident("result"), "right"), "concat"),
                    List(Select(Ident("value"), "right")))))))),
          Return(Ident("result"))))

    WorkflowBuilder(
      chain(
        FoldLeftOp(NonEmptyList(
          chain(
            this.graph,
            ProjectOp(_,
              Reshape.Doc(ListMap(
                joinOnField -> -\/(leftKey),
                leftField   -> -\/(ExprOp.DocVar(ExprOp.DocVar.ROOT, None))))),
            WorkflowOp.GroupOp(_,
              Grouped(ListMap(leftField -> ExprOp.AddToSet(ExprOp.DocVar(ExprOp.DocVar.ROOT, Some(leftField))))),
              -\/(ExprOp.DocVar(ExprOp.DocVar.ROOT, Some(joinOnField)))),
            ProjectOp(_,
              Reshape.Doc(ListMap(
                leftField -> -\/(DocVar.ROOT(leftField)),
                rightField -> -\/(ExprOp.Literal(Bson.Arr(Nil)))))),
            ProjectOp(_,
              Reshape.Doc(ListMap(
                ExprName -> -\/(ExprOp.DocVar(ExprOp.DocVar.ROOT, None)))))),
          chain(that.graph,
            MapOp(_, rightMap(rightKey)),
            ReduceOp(_, rightReduce)))),
        buildJoin(_, tpe),
        UnwindOp(_, ExprOp.DocField(ExprName \ leftField)),
        UnwindOp(_, ExprOp.DocField(ExprName \ rightField))),
      ExprVar,
      SchemaChange.Init)
  }

  def cross(that: WorkflowBuilder) =
    this.join(that,
      slamdata.engine.LogicalPlan.JoinType.Inner,
      ExprOp.Literal(Bson.Int64(1)), Js.Num(1, false))

  private def rewrite[A <: WorkflowOp](op: A, base: DocVar): (A, DocVar) = {
    (op.rewriteRefs(PartialFunction(base \\ _))) -> (op match {
      case GroupOp(_, _, _) => DocVar.ROOT()
      case ProjectOp(_, _)  => DocVar.ROOT()
      case _                => base
    })
  }

  def >>> (op: WorkflowOp => WorkflowOp): WorkflowBuilder = {
    val (newGraph, newBase) = rewrite(op(graph), base)
    copy(graph = newGraph, base = newBase)
  }

  def squash: WorkflowBuilder = {
    if (graph.vertices.collect { case UnwindOp(_, _) => () }.isEmpty) this
    else
      copy(
        graph = struct.shift(graph, base) match {
          case op @ ProjectOp(_, _) =>
            op.set(BsonField.Name("_id"), -\/(ExprOp.Exclude))
          case op                   => op // FIXME: not excluding _id here
        },
        base = DocVar.ROOT())
  }

  def distinct: Error \/ WorkflowBuilder = {
    struct match {
      case SchemaChange.MakeObject(fields) => \/- (
          WorkflowBuilder(
            GroupOp(
              graph,
              Grouped(ListMap(
                      fields.keys.toList.map(name => 
                        BsonField.Name(name) -> ExprOp.First(base \ BsonField.Name(name))
                      ): _*)),
              \/- (Reshape.Doc(ListMap(
                      fields.keys.toList.map(name => 
                        BsonField.Name(name) -> -\/ (base \ BsonField.Name(name))
                      ): _*)))
              ),
            base,
            struct,
            Nil))
            
      case _ => 
        base match {
          case ExprOp.DocVar(ExprOp.DocVar.ROOT, Some(name @ BsonField.Name(_))) => \/- (
            WorkflowBuilder(
              GroupOp(
                graph,
                Grouped(ListMap(
                  name -> ExprOp.First(base)
                )),
                -\/ (base)),
              base,
              struct,
              Nil))
            
          case _ => -\/ (WorkflowBuilderError.UnsupportedDistinct("Cannot distinct with unknown shape (" + struct + "; " + base + ")"))
        }
    }
  }

  def asExprOp = this applyLens _graph modify (_.coalesce) match {
    case WorkflowBuilder(ProjectOp(_, Reshape.Doc(fields)), `ExprVar`, _, _) =>
      fields.toList match {
        case (`ExprName`, -\/ (e)) :: Nil => Some(e)
        case _ => None
      }
    case WorkflowBuilder(PureOp(bson), _, _, _) =>
      Some(ExprOp.Literal(bson))
    case _ => None
  }

  // TODO: At least some of this should probably be deferred to
  //       WorkflowOp.coalesce.
  private def merge[A](that: WorkflowBuilder)(f: (DocVar, DocVar, WorkflowOp) => Error \/ A):
      Error \/ A = {

    def step(left: WorkflowOp, right: WorkflowOp): Error \/ ((DocVar, DocVar), WorkflowOp) = {
      def delegate = step(right, left).map { case ((r, l), merged) => ((l, r), merged) }

      if (left == right)
        \/- ((DocVar.ROOT(), DocVar.ROOT()) -> left)
      else
        (left, right) match {
          case (PureOp(lbson), PureOp(rbson)) =>
            \/-(
              (LeftVar, RightVar) ->
                PureOp(Bson.Doc(ListMap(
                  LeftLabel -> lbson,
                  RightLabel -> rbson))))
          case (PureOp(bson), r) =>
            \/-(
              (LeftVar, RightVar) ->
                ProjectOp(r,
                  Reshape.Doc(ListMap(
                    LeftName -> -\/(ExprOp.Literal(bson)),
                    RightName -> -\/(DocVar.ROOT())))).coalesce)
          case (_, PureOp(_)) => delegate

          case (_: SourceOp, _: SourceOp) =>
            -\/(WorkflowBuilderError.CouldNotPatchRoot)

          case (left : GeoNearOp, r : WPipelineOp) =>
            step(left, r.src).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(r, rb)
              ((lb0, rb), right0.reparent(src))
            }
          case (_, _ : GeoNearOp) => delegate

          case (left: WorkflowOp.ShapePreservingOp, r: WPipelineOp) =>
            step(left, r.src).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(r, rb)
              ((lb0, rb), right0.reparent(src))
            }
          case (_: WPipelineOp, _: WorkflowOp.ShapePreservingOp) => delegate

          case (left @ ProjectOp(lsrc, shape), r: SourceOp) =>
            \/-((LeftVar, RightVar) ->
              ProjectOp(lsrc,
                Reshape.Doc(ListMap(
                  LeftName -> \/- (shape),
                  RightName -> -\/ (DocVar.ROOT())))).coalesce)
          case (_: SourceOp, ProjectOp(_, _)) => delegate

          case (left @ GroupOp(lsrc, Grouped(_), b1), right @ GroupOp(rsrc, Grouped(_), b2)) if (b1 == b2) =>
            step(lsrc, rsrc).map { case ((lb, rb), src) =>
              val (GroupOp(_, Grouped(g1_), b1), lb0) = rewrite(left, lb)
              val (GroupOp(_, Grouped(g2_), b2), rb0) = rewrite(right, rb)

              val (to, _) = BsonField.flattenMapping(g1_.keys.toList ++ g2_.keys.toList)

              val g1 = g1_.map(t => (to(t._1): BsonField.Leaf) -> t._2)
              val g2 = g2_.map(t => (to(t._1): BsonField.Leaf) -> t._2)

              val g = g1 ++ g2
              val b = \/-(Reshape.Arr(ListMap(
                BsonField.Index(0) -> b1,
                BsonField.Index(1) -> b2)))

              ((lb0, rb0) ->
                ProjectOp.EmptyDoc(GroupOp(src, Grouped(g), b).coalesce).setAll(to.mapValues(f => -\/ (DocVar.ROOT(f)))).coalesce)
            }
          case (left @ GroupOp(_, Grouped(_), _), r: WPipelineOp) =>
            step(left.src, r).map { case ((lb, rb), src) =>
              val (GroupOp(_, Grouped(g1_), b1), lb0) = rewrite(left, lb)
              val uniqName = BsonField.genUniqName(g1_.keys.map(_.toName))
              val uniqVar = DocVar.ROOT(uniqName)

              ((lb0, uniqVar) ->
                chain(src,
                  GroupOp(_, Grouped(g1_ + (uniqName -> ExprOp.Push(rb))), b1),
                  UnwindOp(_, uniqVar)).coalesce)
            }
          case (_: WPipelineOp, GroupOp(_, _, _)) => delegate

          case (left @ ProjectOp(lsrc, _), right @ ProjectOp(rsrc, _)) =>
            step(lsrc, rsrc).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(right, rb)
              ((LeftVar \\ lb0, RightVar \\ rb0) ->
                ProjectOp(src,
                  Reshape.Doc(ListMap(
                    LeftName -> \/-(left0.shape),
                    RightName -> \/-(right0.shape)))).coalesce)
            }
          case (left @ ProjectOp(lsrc, _), r: WPipelineOp) =>
            step(lsrc, r.src).map { case ((lb, rb), op) =>
              val (left0, lb0) = rewrite(left, lb)
              ((LeftVar \\ lb0, RightVar \\ rb) ->
                ProjectOp(op,
                  Reshape.Doc(ListMap(
                    LeftName -> \/- (left0.shape),
                    RightName -> -\/ (DocVar.ROOT())))).coalesce)
            }
          case (_: WPipelineOp, ProjectOp(_, _)) => delegate

          case (left @ RedactOp(lsrc, _), right @ RedactOp(rsrc, _)) =>
            step(lsrc, rsrc).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(right, rb)
              ((lb0, rb0) -> RedactOp(RedactOp(src, left0.value).coalesce, right0.value).coalesce)
            }
          case (left @ UnwindOp(lsrc, lfield), right @ UnwindOp(rsrc, rfield)) if lfield == rfield =>
            step(lsrc, rsrc).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(right, rb)
              ((lb0, rb0) -> UnwindOp(src, left0.field))
            }
          case (left @ UnwindOp(lsrc, _), right @ UnwindOp(rsrc, _)) =>
            step(lsrc, rsrc).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(right, rb)
              ((lb0, rb0) -> UnwindOp(UnwindOp(src, left0.field).coalesce, right0.field).coalesce)
            }
          case (left @ UnwindOp(lsrc, lfield), right @ RedactOp(_, _)) =>
            step(lsrc, right).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(right, rb)
              ((lb0, rb0) -> left0.reparent(src))
            }
          case (RedactOp(_, _), UnwindOp(_, _)) => delegate

          case (left @ MapOp(_, _), r @ ProjectOp(rsrc, shape)) =>
            step(left, rsrc).map { case ((lb, rb), src) =>
                val (left0, lb0) = rewrite(left, lb)
                val (right0, rb0) = rewrite(r, rb)
                ((LeftVar \\ lb0, RightVar \\ rb) ->
                  ProjectOp(src,
                    Reshape.Doc(ListMap(
                      LeftName -> -\/(DocVar.ROOT()),
                      RightName -> \/-(shape)))).coalesce)
            }
          case (_: ProjectOp, MapOp(_, _)) => delegate

          case (left: WorkflowOp, right: WPipelineOp) =>
            step(left, right.src).map { case ((lb, rb), src) =>
              val (left0, lb0) = rewrite(left, lb)
              val (right0, rb0) = rewrite(right, rb)
              ((lb0, rb0) -> right0.reparent(src))
            }
          case (_: WPipelineOp, _: WorkflowOp) => delegate

          case _ =>
            -\/(WorkflowBuilderError.UnknownStructure)
        }
    }

    step(this.graph, that.graph).flatMap {
      case ((lbase, rbase), op) => f(lbase \\ this.base, rbase \\ that.base, op)
    }
  }

  private def mergeGroups(groupBys0: List[WorkflowBuilder]*):
      Error \/ List[WorkflowBuilder] =
    if (groupBys0.isEmpty) \/-(Nil)
    else {
      /*
        p1    p2
        |     |
        a     d
        |
        b
        |
        c

           
        c     X
        |     |
        b     X
        |     |
        a     d


        a     d     -> merge to A
        |     |                 |
        b     X     -> merge to B
        |     |                 |
        c     X     -> merge to C
       */
      val One = pure(Bson.Int64(1L))

      val maxLen = groupBys0.view.map(_.length).max

      val groupBys: List[List[WorkflowBuilder]] = groupBys0.toList.map(_.reverse.padTo(maxLen, One).reverse)

      type EitherError[X] = Error \/ X

      groupBys.transpose.map {
        case Nil => \/- (One)
        case x :: xs => xs.foldLeftM[EitherError, WorkflowBuilder](x) { (a, b) => 
          if (a == b) \/-(a) else a.makeArray arrayConcat b.makeArray
        }
      }.sequenceU
    }
}

object WorkflowBuilder {
  import WorkflowOp._
  import ExprOp.{DocVar}

  private val ExprLabel  = "value"
  val ExprName   = BsonField.Name(ExprLabel)
  val ExprVar    = ExprOp.DocVar.ROOT(ExprName)

  private val LeftLabel  = "lEft"
  private val LeftName   = BsonField.Name(LeftLabel)
  private val LeftVar    = DocVar.ROOT(LeftName)

  private val RightLabel = "rIght"
  private val RightName  = BsonField.Name(RightLabel)
  private val RightVar   = DocVar.ROOT(RightName)

  def read(coll: Collection) =
    WorkflowBuilder(ReadOp(coll), DocVar.ROOT(), SchemaChange.Init)
  def pure(bson: Bson) =
    WorkflowBuilder(PureOp(bson), DocVar.ROOT(), SchemaChange.Init)

  def fromExpr(src: WorkflowOp, expr: ExprOp): WorkflowBuilder =
    WorkflowBuilder(
      ProjectOp(src, PipelineOp.Reshape.Doc(ListMap(ExprName -> -\/ (expr)))).coalesce,
      ExprVar,
      SchemaChange.Init)

  val _graph  = mkLens[WorkflowBuilder, WorkflowOp]("graph")
  val _base   = mkLens[WorkflowBuilder, DocVar]("base")
  val _struct = mkLens[WorkflowBuilder, SchemaChange]("struct")
  
  implicit def WorkflowBuilderRenderTree(implicit RO: RenderTree[WorkflowOp], RE: RenderTree[ExprOp]): RenderTree[WorkflowBuilder] = new RenderTree[WorkflowBuilder] {
    def render(v: WorkflowBuilder) = NonTerminal("",
      RO.render(v.graph) ::
        RE.render(v.base) ::
        Terminal(v.struct.toString, "WorkflowBuilder" :: "SchemaChange" :: Nil) ::
        NonTerminal("", v.groupBy.map(WorkflowBuilderRenderTree(RO, RE).render(_)), "WorkflowBuilder" :: "GroupBy" :: Nil) ::
        Nil,
      "WorkflowBuilder" :: Nil)
  }
}
