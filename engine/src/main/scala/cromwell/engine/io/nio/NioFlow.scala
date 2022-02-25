package cromwell.engine.io.nio

import akka.stream.scaladsl.Flow
import cats.effect.{IO, Timer}
import cloud.nio.impl.drs.DrsCloudNioFileSystemProvider
import scala.util.Try
import cloud.nio.spi.{ChecksumFailure, ChecksumResult, ChecksumSuccess, FileHash, HashType}
import com.typesafe.config.Config
import common.util.IORetry
import cromwell.core.io._
import cromwell.core.path.Path
import cromwell.engine.io.IoActor._
import cromwell.engine.io.RetryableRequestSupport.{isInfinitelyRetryable, isRetryable}
import cromwell.engine.io.{IoAttempts, IoCommandContext, IoCommandStalenessBackpressuring}
import cromwell.filesystems.drs.DrsPath
import cromwell.filesystems.gcs.GcsPath
import cromwell.filesystems.oss.OssPath
import cromwell.filesystems.s3.S3Path
import cromwell.util.TryWithResource._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

import java.io._
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration


/**
  * Flow that executes IO operations by calling java.nio.Path methods
  */
class NioFlow(parallelism: Int,
              onRetryCallback: IoCommandContext[_] => Throwable => Unit,
              onBackpressure: Option[Double] => Unit,
              numberOfAttempts: Int,
              commandBackpressureStaleness: FiniteDuration
              )(implicit ec: ExecutionContext) extends IoCommandStalenessBackpressuring {

  implicit private val timer: Timer[IO] = IO.timer(ec)

  override def maxStaleness: FiniteDuration = commandBackpressureStaleness

  private val processCommand: DefaultCommandContext[_] => IO[IoResult] = commandContext => {

    val onRetry: (Throwable, IoAttempts) => IoAttempts = (t, s) => {
      onRetryCallback(commandContext)
      IoAttempts.updateState(t, s)
    }

    val operationResult = IORetry.withRetry(
      handleSingleCommand(commandContext.request),
      IoAttempts(1),
      maxRetries = Option(numberOfAttempts),
      backoff = IoCommand.defaultBackoff,
      isRetryable = isRetryable,
      isInfinitelyRetryable = isInfinitelyRetryable,
      onRetry = onRetry
    )

    val io = for {
      _ <- IO.shift(ec)
      result <- operationResult
    } yield (result, commandContext)

    io handleErrorWith {
      failure => IO.pure(commandContext.fail(failure))
    }
  }

  private [nio] def handleSingleCommand(ioSingleCommand: IoCommand[_]): IO[IoSuccess[_]] = {
    val ret = ioSingleCommand match {
      case copyCommand: IoCopyCommand => copy(copyCommand) map copyCommand.success
      case writeCommand: IoWriteCommand => write(writeCommand) map writeCommand.success
      case deleteCommand: IoDeleteCommand => delete(deleteCommand) map deleteCommand.success
      case sizeCommand: IoSizeCommand => size(sizeCommand) map sizeCommand.success
      case readAsStringCommand: IoContentAsStringCommand => readAsString(readAsStringCommand) map readAsStringCommand.success
      case hashCommand: IoHashCommand => hash(hashCommand) map hashCommand.success
      case touchCommand: IoTouchCommand => touch(touchCommand) map touchCommand.success
      case existsCommand: IoExistsCommand => exists(existsCommand) map existsCommand.success
      case readLinesCommand: IoReadLinesCommand => readLines(readLinesCommand) map readLinesCommand.success
      case isDirectoryCommand: IoIsDirectoryCommand => isDirectory(isDirectoryCommand) map isDirectoryCommand.success
      case _ => IO.raiseError(new UnsupportedOperationException("Method not implemented"))
    }

    // Apply backpressure if this command has been waiting too long to execute.
    backpressureIfStale(ioSingleCommand, onBackpressure)
    ret
  }

  private[io] val flow =
    Flow[DefaultCommandContext[_]].mapAsyncUnordered[IoResult](parallelism)(processCommand.andThen(_.unsafeToFuture()))

  private def copy(copy: IoCopyCommand) = IO {
    createDirectories(copy.destination)
    copy.source.copyTo(copy.destination, overwrite = true)
    ()
  }

  private def write(write: IoWriteCommand) = IO {
    createDirectories(write.file)
    write.file.writeContent(write.content)(write.openOptions, StandardCharsets.UTF_8, write.compressPayload)
    ()
  }

  private def delete(delete: IoDeleteCommand) = IO {
    delete.file.delete(delete.swallowIOExceptions)
    ()
  }

  private def readAsString(read: IoContentAsStringCommand): IO[String] = {
    def checkHash(value: String, fileHash: FileHash): IO[ChecksumResult] = {
      // Disable checksum validation if failOnOverflow is false. We might not read
      // the entire stream, in which case the checksum will definitely fail.
      // Ideally, we would only disable checksum validation if there was an actual
      // overflow, but we don't know that here.
      if (!read.options.failOnOverflow) return IO.pure(ChecksumSuccess())

      val hash = fileHash.hashType.calculateHash(value)
      if (hash == fileHash.hash) IO.pure(ChecksumSuccess())
      else IO.pure(ChecksumFailure(hash))
    }

    def readFile: IO[String] = {
      for {
        fileHash <- getHash(read.file)
        uncheckedValue <- IO {
          new String(
            read.file.limitFileContent(read.options.maxBytes, read.options.failOnOverflow),
            StandardCharsets.UTF_8
          )
        }
        checksumResult <- checkHash(uncheckedValue, fileHash)
        verifiedValue <- checksumResult match {
          case _: ChecksumSuccess => IO.pure(uncheckedValue)
          case failure: ChecksumFailure => IO.raiseError(
            ChecksumFailedException(
              s"Failed checksum for: ${read.file}. expected: '$fileHash' actual: '${failure.calculatedHash}'"))
        }
      } yield verifiedValue
    }

    readFile.map(_.replaceAll("\\r\\n", "\\\n"))
  }

  private def size(size: IoSizeCommand) = IO {
    size.file.size
  }

  private def hash(hash: IoHashCommand): IO[String] = {
    getHash(hash.file).map(_.hash)
  }

  private def getHash(file: Path): IO[FileHash] = {
    file match {
      case gcsPath: GcsPath => getFileHashForGcsPath(gcsPath)
      case drsPath: DrsPath => getFileHashForDrsPath(drsPath)
      case s3Path: S3Path => IO {
        FileHash(HashType.Etag, s3Path.eTag)
      }
      case ossPath: OssPath => IO {
        FileHash(HashType.Etag, ossPath.eTag)
      }
      case path => getMd5FileHashForPath(path)
    }
  }

  private def touch(touch: IoTouchCommand) = IO {
    touch.file.touch()
  }

  private def exists(exists: IoExistsCommand) = IO {
    exists.file.exists
  }

  private def readLines(exists: IoReadLinesCommand) = IO {
    exists.file.withReader { reader =>
      Stream.continually(reader.readLine()).takeWhile(_ != null).toList
    }
  }

  private def isDirectory(isDirectory: IoIsDirectoryCommand) = IO {
    isDirectory.file.isDirectory
  }

  private def createDirectories(path: Path) = path.parent.createDirectories()

  /**
    * Lazy evaluation of a Try in a delayed IO. This avoids accidentally eagerly evaluating the Try.
    *
    * IMPORTANT: Use this instead of IO.fromTry to make sure the Try will be reevaluated if the
    * IoCommand is retried.
    */
  private def delayedIoFromTry[A](t: => Try[A]): IO[A] = IO[A] { t.get }

  private def getFileHashForGcsPath(gcsPath: GcsPath): IO[FileHash] = delayedIoFromTry {
    gcsPath.objectBlobId.map(id => FileHash(HashType.Crc32c, gcsPath.cloudStorage.get(id).getCrc32c))
  }

  private def getFileHashForDrsPath(drsPath: DrsPath): IO[FileHash] = IO {
    val drsFileSystemProvider = drsPath.drsPath.getFileSystem.provider.asInstanceOf[DrsCloudNioFileSystemProvider]

    val fileAttributesOption = drsFileSystemProvider.fileProvider.fileAttributes(drsPath.drsPath.cloudHost, drsPath.drsPath.cloudPath)

    fileAttributesOption match {
      case Some(fileAttributes) =>
        fileAttributes.fileHash match {
          case Some(fileHash) => fileHash
          case None => throw new IOException(s"Error while resolving DRS path $drsPath. The response from Martha doesn't contain the 'md5' hash for the file.")
        }
      case None => throw new IOException(s"Error getting file hash of DRS path $drsPath. Reason: File attributes class DrsCloudNioRegularFileAttributes wasn't defined in DrsCloudNioFileProvider.")
    }
  }

  private def getMd5FileHashForPath(path: Path): IO[FileHash] = delayedIoFromTry {
    tryWithResource(() => path.newInputStream) { inputStream =>
      FileHash(HashType.Md5, org.apache.commons.codec.digest.DigestUtils.md5Hex(inputStream))
    }
  }
}

object NioFlow {
  case class NioFlowConfig(parallelism: Int)

  implicit val nioFlowConfigReader: ValueReader[NioFlowConfig] = (config: Config, path: String) => {
    val base = config.as[Config](path)
    val parallelism = base.as[Int]("parallelism")
    NioFlowConfig(parallelism)
  }
}

case class ChecksumFailedException(message: String) extends IOException(message)
