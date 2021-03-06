package slamdata.engine.analysis

import scalaz.{Tree => ZTree, Validation, Semigroup, NonEmptyList, Foldable1, Show, Cord}

import slamdata.engine.{RenderTree, Terminal, NonTerminal, RenderedTree}

sealed trait AnnotatedTree[N, A] extends Tree[N] { self =>
  def attr(node: N): A
}

trait AnnotatedTreeInstances {
  implicit def AnnotatedTreeRenderTree[N, A](implicit RN: RenderTree[N], RA: RenderTree[A]) = new RenderTree[AnnotatedTree[N, A]] {
    override def render(t: AnnotatedTree[N, A]) = {
      def renderNode(n: N): RenderedTree = {
        val r = RN.render(n)
        NonTerminal(r.nodeType, r.label,
          RA.render(t.attr(n)).copy(nodeType=List("Annotation"), label=None) ::
            t.children(n).map(renderNode(_)))
      }

      renderNode(t.root)
    }
  }
}

object AnnotatedTree extends AnnotatedTreeInstances {
  def unit[N](root0: N, children0: N => List[N]): AnnotatedTree[N, Unit] = const(root0, children0, ())

  def const[N, A](root0: N, children0: N => List[N], const: A): AnnotatedTree[N, A] = new AnnotatedTree[N, A] {
    def root: N = root0

    def children(node: N): List[N] = children0(node)

    def attr(node: N): A = const
  }

  def apply[N, A](root0: N, children0: N => List[N], attr0: N => A): AnnotatedTree[N, A] = new AnnotatedTree[N, A] {
    def root: N = root0

    def children(node: N): List[N] = children0(node)

    def attr(node: N): A = attr0(node)
  }
}
