/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar
package repl

import slamdata.Predef._
import quasar.api._, datasource._, resource._
import quasar.common.{PhaseResultListen, PhaseResultTell, PhaseResults}
import quasar.common.data.Data
import quasar.contrib.fs2.convert.fromStreamT
import quasar.contrib.fs2.pipe
import quasar.contrib.iota._
import quasar.contrib.pathy._
import quasar.contrib.std.uuid._
import quasar.ejson.EJson
import quasar.ejson.implicits._
import quasar.fp.minspace
import quasar.fp.ski._
import quasar.frontend.data.DataCodec
import quasar.impl.schema.{SstConfig, SstSchema}
import quasar.mimir.MimirRepr
import quasar.run.{QuasarError, MonadQuasarErr, Sql2QueryEvaluator, SqlQuery}
import quasar.run.ResourceRouter.DatasourceResourcePrefix
import quasar.run.optics.{stringUuidP => UuidString}
import quasar.sst._
import quasar.yggdrasil.util.NullRemover

import java.lang.Exception
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Path => JPath}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import argonaut.{Json, JsonParser, JsonScalaz}, JsonScalaz._
import cats.arrow.FunctionK
import cats.effect._
import eu.timepit.refined.refineV
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.scalaz._
import fs2.{Stream, StreamApp}, StreamApp.ExitCode
import fs2.async.Ref
import matryoshka.data.Fix
import matryoshka.implicits._
import pathy.Path._
import scalaz._, Scalaz._
import shims.{eqToScalaz => _, orderToScalaz => _, _}
import spire.std.double._

final class Evaluator[F[_]: Effect: MonadQuasarErr: PhaseResultListen: PhaseResultTell: Timer](
    stateRef: Ref[F, ReplState],
    sources: Datasources[F, Stream[F, ?], UUID, Json, SstConfig[Fix[EJson], Double]],
    queryEvaluator: QueryEvaluator[F, SqlQuery, Stream[F, MimirRepr]])(
    implicit ec: ExecutionContext) {

  import Command._
  import DatasourceError._
  import Evaluator._

  val F = Effect[F]

  def evaluate(cmd: Command): F[Result[Stream[F, String]]] = {
    val exitCode = if (cmd === Exit) Some(ExitCode.Success) else None
    recoverErrors(doEvaluate(cmd)).map(Result(exitCode, _))
  }

  ////

  private def children(path: ResourcePath)
      : F[Stream[F, (ResourceName, ResourcePathType)]] =
    path match {
      case ResourcePath.Root =>
        Stream.emit((ResourceName(DatasourceResourcePrefix), ResourcePathType.prefix))
          .covary[F].point[F]

      case DatasourceResourcePrefix /: ResourcePath.Root =>
        sources.allDatasourceMetadata.map(_.evalMap {
          case (id, DatasourceMeta(_, n, _)) =>
            sources.pathIsResource(id, ResourcePath.root())
              .flatMap(fromEither[ExistentialError[UUID], Boolean])
              .map(b => (
                ResourceName(s"$id"),
                if (b) ResourcePathType.leafResource else ResourcePathType.prefix))
        })

      case DatasourceResourcePrefix /: UuidString(id) /: p =>
        sources.prefixedChildPaths(id, p)
          .flatMap(fromEither[DiscoveryError[UUID], Stream[F, (ResourceName, ResourcePathType)]])

      case _ =>
        fromEither[DiscoveryError[UUID], Stream[F, (ResourceName, ResourcePathType)]](
          pathNotFound[DiscoveryError[UUID]](path).left)
    }

  private def schema(path: ResourcePath): F[Option[SstSchema[Fix[EJson], Double]]] =
    path match {
      case DatasourceResourcePrefix /: UuidString(id) /: p =>
        sources.resourceSchema(id, p, SstConfig.Default)
          .flatMap(fromEither[DiscoveryError[UUID], Option[SstSchema[Fix[EJson], Double]]])

      case _ =>
        fromEither[DiscoveryError[UUID], Option[SstSchema[Fix[EJson], Double]]](
          pathNotFound[DiscoveryError[UUID]](path).left)
    }

  private def doEvaluate(cmd: Command): F[Stream[F, String]] =
    cmd match {
      case Help =>
        liftS1(helpMsg)

      case Debug(level) =>
        stateRef.modify(_.copy(debugLevel = level)) *>
          liftS1(s"Set debug level: $level")

      case SummaryCount(rows) =>
        val count: Option[Option[Int Refined Positive]] =
          if (rows === 0) Some(None)
          else refineV[Positive](rows).fold(κ(None), p => Some(Some(p)))
        count match {
          case None => liftS1("Rows must be a positive integer or 0 to indicate no limit")
          case Some(c) => stateRef.modify(_.copy(summaryCount = c)) *>
            liftS1 {
              val r = c.map(_.toString).getOrElse("unlimited")
              s"Set rows to show in result: $r"
            }
        }

      case Format(fmt) =>
        stateRef.modify(_.copy(format = fmt)) *>
          liftS1(s"Set output format: $fmt")

      case Mode(mode) =>
        stateRef.modify(_.copy(mode = mode)) *>
          liftS1(s"Set output mode: $mode")

      case SetPhaseFormat(fmt) =>
        stateRef.modify(_.copy(phaseFormat = fmt)) *>
          liftS1(s"Set phase format: $fmt")

      case SetTimingFormat(fmt) =>
        stateRef.modify(_.copy(timingFormat = fmt)) *>
          liftS1(s"Set timing format: $fmt")

      case SetVar(n, v) =>
        stateRef.modify(state => state.copy(variables = state.variables + (n -> v))) *>
          liftS1(s"Set variable ${n.value} = ${v.value}")

      case UnsetVar(n) =>
        stateRef.modify(state => state.copy(variables = state.variables - n)) *>
          liftS1(s"Unset variable ${n.value}")

      case ListVars =>
        stateRef.get.map(_.variables.value).map(
          _.toList.map { case (VarName(name), VarValue(value)) => s"$name = $value" }
            .mkString("Variables:\n", "\n", "")).map(Stream.emit(_))

      case DatasourceList =>
        sources.allDatasourceMetadata
          .flatMap(_.map({ case (k, v) => s"$k ${printMetadata(v)}" }).compile.toList)
          .map(_.mkString("Datasources:\n", "\n", "")).map(Stream.emit(_))

      case DatasourceTypes =>
        doSupportedTypes.map(
          _.toList.map(printType)
            .mkString("Supported datasource types:\n", "\n", "")).map(Stream.emit(_))

      case DatasourceLookup(id) =>
        sources.datasourceRef(id)
          .flatMap(fromEither[ExistentialError[UUID], DatasourceRef[Json]])
          .map(ref =>
            List(s"Datasource[$id](name = ${ref.name.shows}, type = ${printType(ref.kind)})", ref.config.spaces2).mkString("\n"))
          .map(Stream.emit(_))

      case DatasourceAdd(name, tp, cfg) =>
        for {
          tps <- supportedTypes
          dsType <- findTypeF(tps, tp)
          cfgJson <- JsonParser.parse(cfg).fold(raiseEvalError, _.point[F])
          r <- sources.addDatasource(DatasourceRef(dsType, name, cfgJson))
          i <- r.fold(e => raiseEvalError(e.shows), _.point[F])
        } yield Stream.emit(s"Added datasource $i (${name.value})")

      case DatasourceRemove(id) =>
        (sources.removeDatasource(id) >>= ensureNormal[ExistentialError[UUID]]).map(
          κ(Stream.emit(s"Removed datasource $id")))

      case ResourceSchema(replPath) =>
        for {
          cwd <- stateRef.get.map(_.cwd)
          path = newPath(cwd, replPath)
          s <- schema(path)
          t = s.map(_.sst.fold(_.asEJson[Fix[EJson]], _.asEJson[Fix[EJson]]))
        } yield {
          Stream.emit(
            t.flatMap(ejs => DataCodec.Precise.encode(ejs.cata(Data.fromEJson)))
              .fold("No schema available.")(_.spaces2))
        }

      case Cd(path: ReplPath) =>
        for {
          cwd <- stateRef.get.map(_.cwd)
          dir = newPath(cwd, path)
          _ <- ensureValidDir(dir)
          _ <- stateRef.modify(_.copy(cwd = dir))
        } yield Stream.emit(s"cwd is now ${printPath(dir)}")

      case Ls(path: Option[ReplPath]) =>
        def postfix(tpe: ResourcePathType): String = tpe match {
          case ResourcePathType.LeafResource => ""
          case _ => "/"
        }

        def convert(s: Stream[F, (ResourceName, ResourcePathType)])
            : Stream[F, String] =
          s.map { case (name, tpe) => s"${name.value}${postfix(tpe)}" }

        for {
          cwd <- stateRef.get.map(_.cwd)
          p = path.map(newPath(cwd, _)).getOrElse(cwd)
          cs <- children(p)
        } yield convert(cs)

      case Pwd =>
        stateRef.get.map(s => Stream.emit(printPath(s.cwd)))

      case Select(q) =>
        def doSelect(sql: SqlQuery, state: ReplState): F[(String, Stream[F, CharBuffer])] =
          for {
            (qres, phaseResults) <- PhaseResultListen[F].listen(
              evaluateQuery(sql, state.summaryCount))
            log = printLog(state.debugLevel, state.phaseFormat, phaseResults).map(_ + "\n").getOrElse("")
            rendered = renderMimirReprStream(state.format, qres)
          } yield ((log, rendered))

        for {
          state <- stateRef.get
          sql = SqlQuery(q, state.variables, toADir(state.cwd))
          res <- state.mode match {
            case OutputMode.Console =>
              doSelect(sql, state).map { case (log, rendered) =>
                val maxLines = state.summaryCount
                  .map(_.value + OutputFormat.headerLines(state.format))
                Stream.emit(log) ++ printQueryResults(maxLines, rendered.map(_.toString))
              }
            case OutputMode.File =>
              Paths.createTempFile("results", ".txt").map { tmpFile =>
                Stream.emit(s"Writing results to ${tmpFile.toFile.getAbsolutePath}") ++
                  Stream.eval {
                    for {
                      (log, rendered) <- doSelect(sql, state)
                      _ <- writeToPath(tmpFile, Stream(CharBuffer.wrap(log)).covary[F] ++ rendered)
                    } yield "Done"
                  }
              }
          }
        } yield res

      case Explain(q) =>
        for {
          state <- stateRef.get
          (_, phaseResults) <- PhaseResultListen[F].listen(
            Sql2QueryEvaluator.sql2ToQScript[Fix, F](SqlQuery(q, state.variables, toADir(state.cwd))))
          log = printLog(Order[DebugLevel].max(DebugLevel.Normal, state.debugLevel), state.phaseFormat, phaseResults)
        } yield Stream.emits(log.toList)

      case NoOp =>
        liftS(List.empty)

      case Exit =>
        liftS1("Exiting...")
    }

    private def doSupportedTypes: F[ISet[DatasourceType]] =
      sources.supportedDatasourceTypes >>!
        (types => stateRef.modify(_.copy(supportedTypes = types.some)))

    private def ensureNormal[E: Show](c: Condition[E]): F[Unit] =
      c match {
        case Condition.Normal() => F.unit
        case Condition.Abnormal(err) => raiseEvalError(err.shows)
      }

    private def ensureValidDir(p: ResourcePath): F[Unit] =
      p.fold[F[Unit]](
        f => children(ResourcePath.fromPath(fileParent(f))) flatMap { s =>
          s.exists(t => t._1 === ResourceName(fileName(f).value) && t._2 =/= ResourcePathType.leafResource)
            .compile.fold(false)(_ || _)
            .flatMap(_.unlessM(raiseEvalError(s"${printPath(p)} is not a directory")))
        },
        F.unit)

    private def evaluateQuery(q: SqlQuery, summaryCount: Option[Int Refined Positive])
        : F[Stream[F, MimirRepr]] =
      queryEvaluator.evaluate(q) map { s =>
        summaryCount.map(c => s.take(c.value.toLong)).getOrElse(s)
      }

    private def findType(tps: ISet[DatasourceType], tp: DatasourceType.Name)
        : Option[DatasourceType] =
      tps.toList.find(_.name === tp)

    private def findTypeF(tps: ISet[DatasourceType], tp: DatasourceType.Name)
        : F[DatasourceType] =
      findType(tps, tp) match {
        case None => raiseEvalError(s"Unsupported datasource type: $tp")
        case Some(z) => z.point[F]
      }

    private def formatJson(codec: DataCodec)(data: Data): Option[String] =
      codec.encode(data).map(_.pretty(minspace))

    private def fromEither[E: Show, A](e: E \/ A): F[A] =
      e match {
        case -\/(err) => raiseEvalError(err.shows)
        case \/-(a) => a.point[F]
      }

    // This is just similar to `..` from file systems, not meant to
    // be equivalent. E.g. a difference with `..`` from filesystem is that
    // `cd ../..` from dir `/mydir` won't give an error but evaluates to `/`
    private def interpretDotsAsParent(p: ResourcePath): ResourcePath = {
      val names = ResourcePath.resourceNamesIso.get(p)
      val interpreted = names.foldLeft(IList.empty[ResourceName]) { case (acc, n) =>
        if (n === ResourceName("..")) acc.dropRight(1)
        else acc :+ n
      }
      ResourcePath.resourceNamesIso(interpreted)
    }

    private def liftS(ss: List[String]): F[Stream[F, String]] =
      F.pure(Stream.emits(ss))

    private def liftS1(s: String): F[Stream[F, String]] = liftS(List(s))

    private def newPath(cwd: ResourcePath, change: ReplPath): ResourcePath =
      change match {
        case ReplPath.Absolute(p) => interpretDotsAsParent(p)
        case ReplPath.Relative(p) => interpretDotsAsParent(cwd ++ p)
      }

    private def printType(t: DatasourceType): String =
      s"${t.name}-v${t.version}"

    private def printMetadata(m: DatasourceMeta): String =
      s"${m.name.shows} (${printType(m.kind)}): ${printCondition[Exception](m.status, _.getMessage)}"

    private def printCondition[A](c: Condition[A], onAbnormal: A => String) =
      c match {
        case Condition.Normal() => "ok"
        case Condition.Abnormal(a) => s"error: ${onAbnormal(a)}"
      }

    private def printLog(debugLevel: DebugLevel, phaseFormat: PhaseFormat, results: PhaseResults): Option[String] =
      debugLevel match {
        case DebugLevel.Silent  => none
        case DebugLevel.Normal  => (printPhaseResults(phaseFormat, results.takeRight(1)) + "\n").some
        case DebugLevel.Verbose => (printPhaseResults(phaseFormat, results) + "\n").some
      }

    private def printPath(p: ResourcePath): String =
      posixCodec.printPath(p.toPath)

    private def printPhaseResults(phaseFormat: PhaseFormat, results: PhaseResults): String =
      phaseFormat match {
        case PhaseFormat.Tree => results.map(_.showTree).mkString("\n\n")
        case PhaseFormat.Code => results.map(_.showCode).mkString("\n\n")
      }

    private def printQueryResults(maxLines: Option[Int], stream: Stream[F, String]): Stream[F, String] = {

      def cutOffAfterMaxLines(maxLines: Int, start: (Int, String), current: String): (Int, String) =
        current.foldLeft(start) { case (acc, c) =>
          val count = acc._1 + (if (c === '\n') 1 else 0)
          if (count < maxLines)
            (count, acc._2 + c.toString)
          else
            (count, acc._2)
        }

      maxLines match {
        case None =>
          stream
        case Some(max) =>
          stream.fold((0, "")) { case (acc, s) =>
            if (acc._1 < max)
              cutOffAfterMaxLines(max, acc, s)
            else
              acc
          }.map(_._2)
      }
    }

    private def raiseEvalError[A](s: String): F[A] =
      F.raiseError(new EvalError(s))

    private def recoverErrors(fa: F[Stream[F, String]]): F[Stream[F, String]] =
      F.recover(fa) {
        case ee: EvalError => Stream.emit(s"Evaluation error: ${ee.getMessage}")
        case QuasarError.throwableP(qe) => Stream.emit(s"Quasar error: $qe")
        case NonFatal(t) =>
          Stream.emit(s"Unexpected error: ${t.getClass.getCanonicalName}: ${t.getMessage}" +
            t.getStackTrace.mkString("\n  ", "\n  ", ""))
      }

    private def renderMimirRepr(format: OutputFormat, repr: MimirRepr): Stream[F, CharBuffer] = {
      def convert(s: StreamT[IO, CharBuffer]): Stream[F, CharBuffer] =
        fromStreamT(s).translate(λ[FunctionK[IO, F]](_.to[F]))

      def renderCsv(assumeHomogeneous: Boolean): Stream[F, CharBuffer] = {
        import repr.P.trans._
        val table2 = repr.table.transform(Scan(TransSpec1.Id, NullRemover): TransSpec[Source1])
        convert(table2.renderCsv(assumeHomogeneous))
      }

      format match {
        case OutputFormat.Table =>
          val ds: Stream[IO, Data] = mimir.tableToData(repr)
          //TODO make Prettify.renderTable streaming so that we are not memory bound here
          val r: IO[List[String]] = ds.compile.toList.map(Prettify.renderTable)
          Stream.eval(r).flatMap { ss =>
            Stream.fromIterator[IO, String](ss.iterator)
          }.intersperse("\n").map(CharBuffer.wrap(_)).translate(λ[FunctionK[IO, F]](_.to[F]))
        case OutputFormat.Precise =>
          convert(repr.table.renderJson(precise = true))
        case OutputFormat.Readable =>
          convert(repr.table.renderJson(precise = false))
        case OutputFormat.Csv =>
          renderCsv(assumeHomogeneous = false)
        case OutputFormat.HomogeneousCsv =>
          renderCsv(assumeHomogeneous = true)
      }
    }

    private def renderMimirReprStream(format: OutputFormat, s: Stream[F, MimirRepr])
        : Stream[F, CharBuffer] =
      s.flatMap(renderMimirRepr(format, _))

    private def supportedTypes: F[ISet[DatasourceType]] =
      stateRef.get.map(_.supportedTypes) >>=
        (_.map(_.point[F]).getOrElse(doSupportedTypes))

    private def toADir(path: ResourcePath): ADir =
      path.fold(f => fileParent(f) </> dir(fileName(f).value), rootDir)

    private def writeToPath(path: JPath, s: Stream[F, CharBuffer]): F[Unit] =
      s.through(pipe.charBufferToByte(StandardCharsets.UTF_8))
        .through(fs2.io.file.writeAllAsync(path))
        .compile.drain
}

object Evaluator {
  final case class Result[A](exitCode: Option[ExitCode], result: A)

  final class EvalError(msg: String) extends java.lang.RuntimeException(msg)

  def apply[F[_]: Effect: MonadQuasarErr: PhaseResultListen: PhaseResultTell: Timer](
      stateRef: Ref[F, ReplState],
      sources: Datasources[F, Stream[F, ?], UUID, Json, SstConfig[Fix[EJson], Double]],
      queryEvaluator: QueryEvaluator[F, SqlQuery, Stream[F, MimirRepr]])(
      implicit ec: ExecutionContext)
      : Evaluator[F] =
    new Evaluator[F](stateRef, sources, queryEvaluator)

  val helpMsg =
    """Quasar REPL, Copyright © 2014–2018 SlamData Inc.
      |
      |Available commands:
      |  exit
      |  help
      |  ds (list | ls)
      |  ds types
      |  ds add [name] [type] [cfg]
      |  ds (remove | rm) [uuid]
      |  ds (lookup | get) [uuid]
      |  pwd
      |  cd [path]
      |  ls [path]
      |  schema [path]
      |  [query]
      |  (explain | compile) [query]
      |  set debug = 0 | 1 | 2
      |  set format = table | precise | readable | csv | homogeneouscsv
      |  set mode = console | file
      |  set summaryCount = [rows]
      |  set [var] = [value]
      |  env
      |
      |TODO:
      |  set phaseFormat = tree | code
      |  set timingFormat = tree | onlytotal""".stripMargin
}
