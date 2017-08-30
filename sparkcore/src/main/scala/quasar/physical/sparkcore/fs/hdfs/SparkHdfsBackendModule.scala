/*
 * Copyright 2014–2017 SlamData Inc.
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

package quasar.physical.sparkcore.fs.hdfs

import slamdata.Predef._
import quasar.{Data, DataCodec}
import quasar.contrib.pathy._
import quasar.contrib.scalaz.readerT._
import quasar.connector.EnvironmentError
import quasar.effect._
import quasar.fp, fp.ski.κ, fp.TaskRef,  fp.free._
import quasar.fs._,
  mount._,
  FileSystemError._, PathError._, WriteFile._,
  BackendDef.{DefinitionError, DefErrT},
  QueryFile.ResultHandle, ReadFile.ReadHandle, WriteFile.WriteHandle
import quasar.physical.sparkcore.fs.{queryfile => corequeryfile, _}
import quasar.physical.sparkcore.fs.SparkCoreBackendModule
import quasar.qscript.{QScriptTotal, Injectable, QScriptCore, EquiJoin, ShiftedRead, ::/::, ::\::}

// import java.io.OutputStream
import java.io.BufferedWriter
// import java.io.OutputStreamWriter
import java.net.{URLDecoder, URI}

import org.http4s.{ParseFailure, Uri}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem => HdfsFileSystem, Path}
import org.apache.hadoop.util.Progressable;
import org.apache.spark._
// import org.apache.spark.rdd._
import pathy.Path._
import scalaz.{Failure => _, _}, Scalaz._
import scalaz.concurrent.Task

final case class HdfsWriteCursor(hdfs: HdfsFileSystem, bw: BufferedWriter)

final case class HdfsConfig(sparkConf: SparkConf, hdfsUriStr: String, prefix: ADir)

object SparkHdfsBackendModule extends SparkCoreBackendModule {

  import corequeryfile.RddState

  val Type = FileSystemType("spark-hdfs")

  type Eff1[A]  = Coproduct[KeyValueStore[ResultHandle, RddState, ?], Read[SparkContext, ?], A]
  type Eff2[A]  = Coproduct[KeyValueStore[ReadHandle, SparkCursor, ?], Eff1, A]
  type Eff3[A]  = Coproduct[KeyValueStore[WriteHandle, HdfsWriteCursor, ?], Eff2, A]
  type Eff4[A]  = Coproduct[Task, Eff3, A]
  type Eff5[A]  = Coproduct[PhysErr, Eff4, A]
  type Eff6[A]  = Coproduct[MonotonicSeq, Eff5, A]
  type Eff7[A]  = Coproduct[SparkConnectorDetails, Eff6, A]
  type Eff[A]   = Coproduct[Read[HdfsFileSystem, ?], Eff7, A]

  implicit def qScriptToQScriptTotal[T[_[_]]]: Injectable.Aux[QSM[T, ?], QScriptTotal[T, ?]] =
        ::\::[QScriptCore[T, ?]](::/::[T, EquiJoin[T, ?], Const[ShiftedRead[AFile], ?]])

  def ReadSparkContextInj = Inject[Read[SparkContext, ?], Eff]
  def RFKeyValueStoreInj = Inject[KeyValueStore[ReadFile.ReadHandle, SparkCursor, ?], Eff]
  def MonotonicSeqInj = Inject[MonotonicSeq, Eff]
  def TaskInj = Inject[Task, Eff]
  def SparkConnectorDetailsInj = Inject[SparkConnectorDetails, Eff]
  def QFKeyValueStoreInj = Inject[KeyValueStore[QueryFile.ResultHandle, corequeryfile.RddState, ?], Eff]

  def writersOps = KeyValueStore.Ops[WriteHandle, HdfsWriteCursor, Eff]
  def sequenceOps = MonotonicSeq.Ops[Eff]
  def hdfsFSOps = Read.Ops[HdfsFileSystem, Eff]

  def generateHdfsFS(sfsConf: HdfsConfig): Task[HdfsFileSystem] =
    for {
      fs <- Task.delay {
        val conf = new Configuration()
        conf.setBoolean("fs.hdfs.impl.disable.cache", true)
        HdfsFileSystem.get(new URI(sfsConf.hdfsUriStr), conf)
      }
      uriStr = fs.getUri().toASCIIString()
      _ <- if(uriStr.startsWith("file:///")) Task.fail(new RuntimeException("Provided URL is not valid HDFS URL")) else ().point[Task]
    } yield fs

  def hdfsPathStr(sfsConf: HdfsConfig): AFile => ReaderT[Task, SparkContext, String] = (afile: AFile) => ReaderT(κ(Task.delay {
    sfsConf.hdfsUriStr + posixCodec.unsafePrintPath(afile)
  }))

  def toLowerLevel[S[_]](sc: SparkContext, config: HdfsConfig)(implicit
    S0: Task :<: S, S1: PhysErr :<: S
  ): Task[Free[Eff, ?] ~> Free[S, ?]] =
    (TaskRef(0L) |@|
      TaskRef(Map.empty[ResultHandle, RddState]) |@|
      TaskRef(Map.empty[ReadHandle, SparkCursor]) |@|
      TaskRef(Map.empty[WriteHandle, HdfsWriteCursor]) |@|
      generateHdfsFS(config)
    ) {
      (genState, rddStates, sparkCursors, writeCursors, hdfsFS) =>

      val interpreter: Eff ~> S =
        (Read.constant[Task, HdfsFileSystem](hdfsFS) andThen injectNT[Task, S]) :+:
        (queryfile.detailsInterpreter[ReaderT[Task, SparkContext, ?]](ReaderT(κ(hdfsFS.point[Task])), hdfsPathStr(config)) andThen  runReaderNT(sc) andThen injectNT[Task, S]) :+:
      (MonotonicSeq.fromTaskRef(genState) andThen injectNT[Task, S]) :+:
      injectNT[PhysErr, S] :+:
      injectNT[Task, S]  :+:
      (KeyValueStore.impl.fromTaskRef[WriteHandle, HdfsWriteCursor](writeCursors) andThen injectNT[Task, S])  :+:
      (KeyValueStore.impl.fromTaskRef[ReadHandle, SparkCursor](sparkCursors) andThen injectNT[Task, S]) :+:
      (KeyValueStore.impl.fromTaskRef[ResultHandle, RddState](rddStates) andThen injectNT[Task, S]) :+:
      (Read.constant[Task, SparkContext](sc) andThen injectNT[Task, S])

      mapSNT[Eff, S](interpreter)
    }

  type Config = HdfsConfig
  def parseConfig(connUri: ConnectionUri): DefErrT[Task, HdfsConfig] = {

    def liftErr(msg: String): DefinitionError = NonEmptyList(msg).left[EnvironmentError]

    def master(host: String, port: Int): State[SparkConf, Unit] =
      State.modify(_.setMaster(s"spark://$host:$port"))

    def appName: State[SparkConf, Unit] = State.modify(_.setAppName("quasar"))

    def config(name: String, uri: Uri): State[SparkConf, Unit] =
      State.modify(c => uri.params.get(name).fold(c)(c.set(name, _)))

    val uriOrErr: DefErrT[Task, Uri] =
      EitherT(Uri.fromString(connUri.value).leftMap((pf: ParseFailure) => liftErr(pf.toString)).point[Task])

    val sparkConfOrErr: DefErrT[Task, SparkConf] = for {
      uri <- uriOrErr
      host <- EitherT(uri.host.fold(liftErr("host not provided").left[Uri.Host])(_.right[DefinitionError]).point[Task])
      port <- EitherT(uri.port.fold(liftErr("port not provided").left[Int])(_.right[DefinitionError]).point[Task])
    } yield {
      (master(host.value, port) *> appName *>
        config("spark.executor.memory", uri) *>
        config("spark.executor.cores", uri) *>
        config("spark.executor.extraJavaOptions", uri) *>
        config("spark.default.parallelism", uri) *>
        config("spark.files.maxPartitionBytes", uri) *>
        config("spark.driver.cores", uri) *>
        config("spark.driver.maxResultSize", uri) *>
        config("spark.driver.memory", uri) *>
        config("spark.local.dir", uri) *>
        config("spark.reducer.maxSizeInFlight", uri) *>
        config("spark.reducer.maxReqsInFlight", uri) *>
        config("spark.shuffle.file.buffer", uri) *>
        config("spark.shuffle.io.retryWait", uri) *>
        config("spark.memory.fraction", uri) *>
        config("spark.memory.storageFraction", uri) *>
        config("spark.cores.max", uri) *>
        config("spark.speculation", uri) *>
        config("spark.task.cpus", uri)
      ).exec(new SparkConf())
    }

    val hdfsUrlOrErr: DefErrT[Task, String] = uriOrErr.flatMap(uri =>
      EitherT(uri.params.get("hdfsUrl").map(url => URLDecoder.decode(url, "UTF-8")).fold(liftErr("'hdfsUrl' parameter not provided").left[String])(_.right[DefinitionError]).point[Task])
    )

    val rootPathOrErr: DefErrT[Task, ADir] =
      uriOrErr
        .flatMap(uri =>
          EitherT(uri.params.get("rootPath").fold(liftErr("'rootPath' parameter not provided").left[String])(_.right[DefinitionError]).point[Task])
        )
        .flatMap(pathStr =>
          EitherT(posixCodec.parseAbsDir(pathStr)
            .map(unsafeSandboxAbs)
            .fold(liftErr("'rootPath' is not a valid path").left[ADir])(_.right[DefinitionError]).point[Task])
        )

    for {
      sparkConf <- sparkConfOrErr
      hdfsUrl <- hdfsUrlOrErr
      rootPath <- rootPathOrErr
    } yield HdfsConfig(sparkConf, hdfsUrl, rootPath)
  }

  private def sparkCoreJar: DefErrT[Task, APath] = {
    /* Points to quasar-web.jar or target/classes if run from sbt repl/run */
    val fetchProjectRootPath = Task.delay {
      val pathStr = URLDecoder.decode(this.getClass().getProtectionDomain.getCodeSource.getLocation.toURI.getPath, "UTF-8")
      posixCodec.parsePath[Option[APath]](_ => None, Some(_).map(unsafeSandboxAbs), _ => None, Some(_).map(unsafeSandboxAbs))(pathStr)
    }
    val jar: Task[Option[APath]] =
      fetchProjectRootPath.map(_.flatMap(s => parentDir(s).map(_ </> file("sparkcore.jar"))))
    OptionT(jar).toRight(NonEmptyList("Could not fetch sparkcore.jar").left[EnvironmentError])
  }

  private def initSC: HdfsConfig => DefErrT[Task, SparkContext] = (config: HdfsConfig) => EitherT(Task.delay {
    new SparkContext(config.sparkConf).right[DefinitionError]
  }.handleWith {
    case ex : SparkException if ex.getMessage.contains("SPARK-2243") =>
      NonEmptyList("You can not mount second Spark based connector... " +
        "Please unmount existing one first.").left[EnvironmentError].left[SparkContext].point[Task]
  })

  def generateSC: HdfsConfig => DefErrT[Task, SparkContext] = (config: HdfsConfig) => for {
    sc  <- initSC(config)
    jar <- sparkCoreJar
  } yield {
    sc.addJar(posixCodec.printPath(jar))
    sc
  }

  def rebaseAFile(f: AFile): Configured[AFile] =
    Kleisli((config: HdfsConfig) => rebaseA(config.prefix).apply(f).point[Free[Eff, ?]])

  def stripPrefixAFile(f: AFile): Configured[AFile] =
    Kleisli((config: HdfsConfig) => stripPrefixA(config.prefix).apply(f).point[Free[Eff, ?]])

  def rebaseADir(d: ADir): Configured[ADir] =
    Kleisli((config: HdfsConfig) => rebaseA(config.prefix).apply(d).point[Free[Eff, ?]])

  def stripPrefixADir(d: ADir): Configured[ADir] =
    Kleisli((config: HdfsConfig) => stripPrefixA(config.prefix).apply(d).point[Free[Eff, ?]])

  private def toPath(apath: APath): Free[Eff, Path] = lift(Task.delay {
    new Path(posixCodec.unsafePrintPath(apath))
  }).into[Eff]

  object HdfsWriteFileModule extends SparkCoreWriteFileModule {
    import WriteFile._

    def rebasedOpen(file: AFile): Backend[WriteHandle] = {
      def createCursor: Free[Eff, HdfsWriteCursor] = for {
        path <- toPath(file)
        hdfs <- hdfsFSOps.ask
      } yield {
        val os: OutputStream = hdfs.create(path, new Progressable() {
          override def progress(): Unit = {}
        })
        val bw = new BufferedWriter( new OutputStreamWriter( os, "UTF-8" ) )
        HdfsWriteCursor(hdfs, bw)
      }

      (for {
        hwc <- createCursor
        id <- sequenceOps.next
        h = WriteHandle(file, id)
        _ <- writersOps.put(h, hwc)
      } yield h).liftB
    }

    def rebasedWrite(h: WriteHandle, chunk: Vector[Data]): Configured[Vector[FileSystemError]] = {

      implicit val codec: DataCodec = DataCodec.Precise

      def _write(bw: BufferedWriter): Free[Eff, Vector[FileSystemError]] = {

        val lines: Vector[(String, Data)] =
          chunk.map(data => DataCodec.render(data) strengthR data).unite

        lift(Task.delay(lines.flatMap {
          case (line, data) =>
            \/.fromTryCatchNonFatal{
              bw.write(line)
              bw.newLine()
            }.fold(
              ex => Vector(writeFailed(data, ex.getMessage)),
              u => Vector.empty[FileSystemError]
            )
        })).into[Eff]
      }

      val findAndWrite: OptionT[Free[Eff, ?], Vector[FileSystemError]] = for {
        HdfsWriteCursor(_, bw) <- writersOps.get(h)
        errors                 <- _write(bw).liftM[OptionT]
      } yield errors

      findAndWrite.fold (
        errs => errs,
        Vector[FileSystemError](unknownWriteHandle(h))
      ).liftM[ConfiguredT]
    }

    def rebasedClose(h: WriteHandle): Configured[Unit] = (for {
      HdfsWriteCursor(hdfs, br) <- writersOps.get(h)
      _                         <- writersOps.delete(h).liftM[OptionT]
    } yield {
      br.close()
      hdfs.close()
    }).run.void.liftM[ConfiguredT]

  }

  def WriteFileModule = HdfsWriteFileModule

  object HdfsManageFileModule extends SparkCoreManageFileModule {

    def moveFile(src: AFile, dst: AFile): Free[Eff, Unit] = {
      val move: Free[Eff, PhysicalError \/ Unit] = (for {
        hdfs <- hdfsFSOps.ask
        srcPath <- toPath(src)
        dstPath <- toPath(dst)
        dstParent <- toPath(fileParent(dst))
      } yield {
        val deleted = hdfs.delete(dstPath, true)
        val _ = hdfs.mkdirs(dstParent)
        hdfs.rename(srcPath, dstPath)
      }).as(().right[PhysicalError])
      Failure.Ops[PhysicalError, Eff].unattempt(move)
    }

    def moveDir(src: ADir, dst: ADir): Free[Eff, Unit] = {
      val move: Free[Eff, PhysicalError \/ Unit] = (for {
        hdfs <- hdfsFSOps.ask
        srcPath <- toPath(src)
        dstPath <- toPath(dst)
      } yield {
        val deleted = hdfs.delete(dstPath, true)
        hdfs.rename(srcPath, dstPath)
      }).as(().right[PhysicalError])

      Failure.Ops[PhysicalError, Eff].unattempt(move)
    }

    def doesPathExist: APath => Free[Eff, Boolean] = (ap) => for {
      path <- toPath(ap)
      hdfs <- hdfsFSOps.ask
    } yield hdfs.exists(path)

    private def deletePath(p: APath): Backend[Unit] = {

      val delete: Free[Eff, FileSystemError \/ Unit] = for {
        path <- toPath(p)
        hdfs <- hdfsFSOps.ask
      } yield (if(hdfs.exists(path)) {
        hdfs.delete(path, true).right[FileSystemError]
      }
      else {
        pathErr(pathNotFound(p)).left[Unit]
      }).as(())

      val deleteHandled: Free[Eff, PhysicalError \/ (FileSystemError \/ Unit)] =
        delete.map(_.right[PhysicalError])

      includeError(Failure.Ops[PhysicalError, Eff].unattempt(deleteHandled).liftB)
    }

    def deleteFile(f: AFile): Backend[Unit] = deletePath(f)
    def deleteDir(d: ADir): Backend[Unit]  = deletePath(d)

    private def tempFileNearPath(near: APath): Free[Eff, FileSystemError \/ AFile] = lift(Task.delay {
      val parent: ADir = refineType(near).fold(d => d, fileParent(_))
      val random = scala.util.Random.nextInt().toString
        (parent </> file(s"quasar-$random.tmp")).right[FileSystemError]
    }
    ).into[Eff]

    def tempFileNearFile(f: AFile): Backend[AFile] = includeError(tempFileNearPath(f).liftB)
    def tempFileNearDir(d: ADir): Backend[AFile]  = includeError(tempFileNearPath(d).liftB)
  }

  def ManageFileModule: ManageFileModule = HdfsManageFileModule


}
