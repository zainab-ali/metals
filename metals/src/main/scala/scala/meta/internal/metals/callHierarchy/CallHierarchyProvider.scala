package scala.meta.internal.metals.callHierarchy

import scala.meta.internal.metals._
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TextDocument

import scala.meta.internal.parsing.Trees

import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyItem
import scala.meta.Tree
import com.google.gson.JsonElement
import scala.meta.pc.CancelToken
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    definition: DefinitionProvider,
    references: ReferenceProvider,
    icons: Icons,
    compilers: () => Compilers,
    trees: Trees,
    buildTargets: BuildTargets,
)(implicit ec: ExecutionContext)
    extends SemanticdbFeatureProvider
    with CallHierarchyHelpers {

  override def reset(): Unit = ()

  override def onDelete(file: AbsolutePath): Unit = ()

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = ()

  private val callHierarchyItemBuilder =
    new CallHierarchyItemBuilder(workspace, icons, compilers, buildTargets)

  private val incomingCallsFinder = new IncomingCallsFinder(definition, trees)
  private val outgoingCallsFinder =
    new OutgoingCallsFinder(semanticdbs, definition, references, trees)

  /**
   * Prepare call hierarchy request by returning a call hierarchy item, resolved for the given text document position.
   */
  def prepare(params: CallHierarchyPrepareParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[List[CallHierarchyItem]] = {

    def resolvedSymbolOccurence2CallHierarchyItem(
        rso: ResolvedSymbolOccurrence,
        source: AbsolutePath,
        doc: TextDocument,
    ): Future[CallHierarchyItem] = {
      val result = for {
        occurence <- rso.occurrence
        if occurence.role.isDefinition
        range <- occurence.range
        tree <- trees.findLastEnclosingAt(source, range.toLSP.getStart)
        definition <- getSpecifiedOrFindDefinition(Some(tree))
      } yield callHierarchyItemBuilder.build(
        source,
        doc,
        occurence,
        definition.root.pos.toLSP,
        Array(occurence.symbol),
        token,
      )

      result
        .getOrElse(Future.successful(None))
        .collect { case Some(value) => value }
    }

    val source = params.getTextDocument.getUri.toAbsolutePath
    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results =
          definition.positionOccurrences(source, params.getPosition, doc)
        Future.sequence(
          results.map(result =>
            resolvedSymbolOccurence2CallHierarchyItem(result, source, doc)
          )
        )
      case None =>
        Future.successful(Nil)
    }
  }

  /**
   * The data entry field that is preserved between a call hierarchy prepare and
   * incoming calls or outgoing calls requests have a unknown type (Object in Java)
   */
  private def getInfo(data: Object): CallHierarchyItemInfo = data
    .asInstanceOf[JsonElement]
    .as[CallHierarchyItemInfo]
    .get

  /**
   * Resolve incoming calls for a given call hierarchy item.
   */
  def incomingCalls(
      params: CallHierarchyIncomingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyIncomingCall]] = {
    val info = getInfo(params.getItem.getData)

    def getIncomingCallsInSpecifiedSource(
        source: AbsolutePath
    ): List[Future[CallHierarchyIncomingCall]] = {
      semanticdbs.textDocument(source).documentIncludingStale match {
        case Some(doc) if (!containsDuplicates(info.visited)) =>
          val results = trees
            .get(source)
            .map(root =>
              incomingCallsFinder.find(
                source,
                doc,
                root,
                info,
              ) ++ incomingCallsFinder.findSynthetics(source, doc, info)
            )
            .getOrElse(Nil)

          results.map(
            _.toLSP(source, doc, callHierarchyItemBuilder, info.visited, token)
          )
        case _ =>
          Nil
      }
    }

    pathsToCheck(
      references,
      params.getItem().getUri().toAbsolutePath,
      info.symbols.toSet,
      info.isLocal,
      info.searchLocal,
    )
      .flatMap(paths =>
        Future.sequence(paths.flatMap(getIncomingCallsInSpecifiedSource))
      )
  }

  /**
   * Resolve outgoing calls for a given call hierarchy item.
   */
  def outgoingCalls(
      params: CallHierarchyOutgoingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyOutgoingCall]] = {
    val source = params.getItem.getUri.toAbsolutePath

    val info = getInfo(params.getItem.getData)

    def searchOutgoingCalls(
        doc: TextDocument,
        root: Tree,
    ): Future[List[CallHierarchyOutgoingCall]] = {
      for {
        calls <- outgoingCallsFinder.find(
          source,
          doc,
          root,
        )
        callsSynthetics <- outgoingCallsFinder.findSynthetics(
          source,
          doc,
          root,
        )
        results <- Future.sequence(
          (calls ++ callsSynthetics).map(
            _.toLSP(callHierarchyItemBuilder, info.visited, token)
          )
        )
      } yield results
    }

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) if (!containsDuplicates(info.visited)) =>
        trees
          .findLastEnclosingAt(
            source,
            params.getItem.getSelectionRange.getStart,
          )
          .map(root => searchOutgoingCalls(doc, root))
          .getOrElse(Future.successful(Nil))
      case _ =>
        Future.successful(Nil)
    }
  }
}
