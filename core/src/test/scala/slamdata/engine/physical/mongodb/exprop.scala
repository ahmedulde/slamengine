package slamdata.engine.physical.mongodb

import collection.immutable.ListMap

import org.specs2.mutable._

import slamdata.engine.{DisjunctionMatchers}
import slamdata.engine.fp._

class ExprOpSpec extends Specification with DisjunctionMatchers {
  import ExprOp._

  "ExprOp" should {

    "escape literal string with $" in {
      val x = Bson.Text("$1")
      Literal(x).bson must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape literal string with no leading '$'" in {
      val x = Bson.Text("abc")
      Literal(x).bson must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape simple integer literal" in {
      val x = Bson.Int32(0)
      Literal(x).bson must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape simple array literal" in {
      val x = Bson.Arr(Bson.Text("abc") :: Bson.Int32(0) :: Nil)
      Literal(x).bson must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape string nested in array" in {
      val x = Bson.Arr(Bson.Text("$1") :: Nil)
      Literal(x).bson must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape simple doc literal" in {
      val x = Bson.Doc(ListMap("a" -> Bson.Text("b")))
      Literal(x).bson must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape string nested in doc" in {
      val x = Bson.Doc(ListMap("a" -> Bson.Text("$1")))
      Literal(x).bson must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "render $$ROOT" in {
      DocVar.ROOT().bson.repr must_== "$$ROOT"
    }

    "treat DocField as alias for DocVar.ROOT()" in {
      DocField(BsonField.Name("foo")) must_== DocVar.ROOT(BsonField.Name("foo"))
    }

    "render $foo under $$ROOT" in {
      DocVar.ROOT(BsonField.Name("foo")).bson.repr must_== "$foo"
    }

    "render $foo.bar under $$CURRENT" in {
      DocVar.CURRENT(BsonField.Name("foo") \ BsonField.Name("bar")).bson.repr must_== "$$CURRENT.foo.bar"
    }

    "render $redact result variables" in {
      Workflow.$Redact.DESCEND.bson.repr must_== "$$DESCEND"
      Workflow.$Redact.PRUNE.bson.repr   must_== "$$PRUNE"
      Workflow.$Redact.KEEP.bson.repr    must_== "$$KEEP"
    }
  }

  "toJs" should {
    import org.threeten.bp._
    import slamdata.engine.javascript.JsFn
    import slamdata.engine.javascript.JsCore._

    "handle addition with epoch date literal" in {
      toJs(ExprOp.Add(ExprOp.Literal(Bson.Date(Instant.ofEpochMilli(0))), DocField(BsonField.Name("epoch")))) must beRightDisj(
        JsFn(JsFn.base, New("Date", List(Select(JsFn.base.fix, "epoch").fix)).fix))
    }
  }
}
