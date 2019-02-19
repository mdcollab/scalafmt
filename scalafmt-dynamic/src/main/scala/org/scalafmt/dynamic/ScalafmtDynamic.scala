package org.scalafmt.dynamic

import java.net.URLClassLoader
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.scalafmt.dynamic.ScalafmtDynamic.FormatResult
import org.scalafmt.dynamic.ScalafmtDynamicDownloader.{DownloadResolutionError, DownloadSuccess, DownloadUnknownError}
import org.scalafmt.dynamic.exceptions._
import org.scalafmt.dynamic.utils.ConsoleScalafmtReporter
import org.scalafmt.interfaces._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try
import scala.util.control.NonFatal

final case class ScalafmtDynamic(
    reporter: ScalafmtReporter,
    respectVersion: Boolean,
    respectExcludeFilters: Boolean,
    defaultVersion: String,
    fmtsCache: mutable.Map[String, ScalafmtReflect],
    cacheConfigs: Boolean,
    configsCache: mutable.Map[Path, (ScalafmtReflectConfig, FileTime)]
) extends Scalafmt {

  def this() = this(
    ConsoleScalafmtReporter,
    true,
    true,
    BuildInfo.stable,
    TrieMap.empty,
    true,
    TrieMap.empty
  )

  override def clear(): Unit = {
    fmtsCache.values.foreach(_.classLoader.close())
    fmtsCache.clear()
  }

  override def withReporter(reporter: ScalafmtReporter): Scalafmt =
    copy(reporter = reporter)

  override def withRespectProjectFilters(
      respectExcludeFilters: Boolean): Scalafmt =
    copy(respectExcludeFilters = respectExcludeFilters)

  override def withRespectVersion(respectVersion: Boolean): Scalafmt =
    copy(respectVersion = respectVersion)

  override def withDefaultVersion(defaultVersion: String): Scalafmt =
    copy(defaultVersion = defaultVersion)

  def withConfigCaching(cacheConfigs: Boolean): Scalafmt =
    copy(cacheConfigs = cacheConfigs)

  override def format(config: Path, file: Path, code: String): String = {
    formatDetailed(config, file, code) match {
      case Right(codeFormatted) =>
        codeFormatted
      case Left(error) =>
        reportError(file, error)
        code
    }
  }

  private def reportError(file: Path, error: ScalafmtDynamicError): Unit = {
    error match {
      case ScalafmtDynamicError.ConfigParseError(configPath, cause) =>
        reporter.error(configPath, cause.getMessage)
      case ScalafmtDynamicError.ConfigDoesNotExist(configPath) =>
        reporter.error(configPath, "file does not exist")
      case ScalafmtDynamicError.ConfigMissingVersion(configPath) =>
        reporter.missingVersion(configPath, defaultVersion)
      case ScalafmtDynamicError.CannotDownload(configPath, version, cause) =>
        val message = s"failed to resolve Scalafmt version '$version'"
        cause match {
          case Some(e) => reporter.error(configPath, message, e)
          case None => reporter.error(configPath, message)
        }
      case ScalafmtDynamicError.CorruptedClassPath(configPath, version, _, cause) =>
        reporter.error(configPath, s"scalafmt version $version classpath is corrupted", cause)
      case ScalafmtDynamicError.UnknownError(cause) =>
        reporter.error(file, cause)
    }
  }

  def formatDetailed(configPath: Path, file: Path, code: String): FormatResult = {
    def tryFormat(reflect: ScalafmtReflect, config: ScalafmtReflectConfig): FormatResult = {
      Try {
        val filename = file.toString
        val configWithDialect: ScalafmtReflectConfig =
          if (filename.endsWith(".sbt") || filename.endsWith(".sc")) {
            config.withSbtDialect
          } else {
            config
          }
        if (isIgnoredFile(filename, configWithDialect)) {
          reporter.excluded(file)
          code
        } else {
          reflect.format(code, configWithDialect, Some(file))
        }
      }.toEither.left.map {
        case ReflectionException(e) => ScalafmtDynamicError.UnknownError(e)
        case e => ScalafmtDynamicError.UnknownError(e)
      }
    }

    for {
      config <- resolveConfig(configPath)
      codeFormatted <- tryFormat(config.fmtReflect, config)
    } yield codeFormatted
  }

  private def isIgnoredFile(filename: String, config: ScalafmtReflectConfig): Boolean = {
    respectExcludeFilters && !config.isIncludedInProject(filename)
  }

  private def resolveConfig(configPath: Path): Either[ScalafmtDynamicError, ScalafmtReflectConfig] = {
    if(!Files.exists(configPath)) {
      Left(ScalafmtDynamicError.ConfigDoesNotExist(configPath))
    } else if (cacheConfigs) {
      val currentTimestamp: FileTime = Files.getLastModifiedTime(configPath)
      configsCache.get(configPath) match {
        case Some((config, lastModified)) if lastModified.compareTo(currentTimestamp) == 0 =>
          Right(config)
        case _ =>
          for {
            config <- resolveConfigWithScalafmt(configPath)
          } yield {
            configsCache(configPath) = (config, currentTimestamp)
            reporter.parsedConfig(configPath, config.version)
            config
          }
      }
    } else {
      resolveConfigWithScalafmt(configPath)
    }
  }
  private def resolveConfigWithScalafmt(configPath: Path): Either[ScalafmtDynamicError, ScalafmtReflectConfig] = {
    for {
      version <- readVersion(configPath).toRight(ScalafmtDynamicError.ConfigMissingVersion(configPath))
      fmtReflect <- resolveFormatter(configPath, version)
      config <- parseConfig(configPath, fmtReflect)
    } yield config
  }

  private def parseConfig(configPath: Path, fmtReflect: ScalafmtReflect): Either[ScalafmtDynamicError, ScalafmtReflectConfig] = {
    Try(fmtReflect.parseConfig(configPath)).toEither.left.map {
      case ex: ScalafmtConfigException =>
        ScalafmtDynamicError.ConfigParseError(configPath, ex)
      case ex =>
        ScalafmtDynamicError.UnknownError(ex)
    }
  }

  // TODO: there can be issues if resolveFormatter is called multiple times (e.g. formatter is called twice)
  //  in such cases download process can be started multiple times,
  //  possible solution: keep information about download process in fmtsCache
  private def resolveFormatter(configPath: Path, version: String): Either[ScalafmtDynamicError, ScalafmtReflect] = {
    fmtsCache.get(version) match {
      case Some(value) =>
        Right(value)
      case None =>
        val downloader = new ScalafmtDynamicDownloader(reporter.downloadWriter())
        downloader.download(version)
          .left.map {
            case f@DownloadResolutionError(_, _) =>
              ScalafmtDynamicError.CannotDownload(configPath, f.version, None)
            case f@DownloadUnknownError(_, _) =>
              ScalafmtDynamicError.CannotDownload(configPath, f.version, Option(f.cause))
          }
          .flatMap(resolveClassPath(configPath, _))
          .map { scalafmt: ScalafmtReflect =>
            fmtsCache(version) = scalafmt
            scalafmt
          }
    }
  }

  private def resolveClassPath(configPath: Path, downloadSuccess: DownloadSuccess): Either[ScalafmtDynamicError, ScalafmtReflect] = {
    val DownloadSuccess(version, urls) = downloadSuccess
    Try {
      val classloader = new URLClassLoader(urls.toArray, null)
      ScalafmtReflect(classloader, version, respectVersion)
    }.toEither.left.map {
      case e: ReflectiveOperationException =>
        ScalafmtDynamicError.CorruptedClassPath(configPath, version, urls, e)
      case e =>
        ScalafmtDynamicError.UnknownError(e)
    }
  }

  private def readVersion(config: Path): Option[String] = {
    try {
      Some(ConfigFactory.parseFile(config.toFile).getString("version"))
    } catch {
      case _: ConfigException.Missing if !respectVersion =>
        Some(defaultVersion)
      case NonFatal(_) =>
        None
    }
  }
}

object ScalafmtDynamic {
  type FormatResult = Either[ScalafmtDynamicError, String]
}
